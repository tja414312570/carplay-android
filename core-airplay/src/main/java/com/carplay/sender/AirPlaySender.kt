package com.carplay.sender

import com.carplay.airplay.*
import com.carplay.iap2.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class SenderDisplay(val width: Int, val height: Int, val fps: Int, val uuid: String?)
interface SenderEvents {
    fun diagnostic(message: String)
    fun display(display: SenderDisplay)
    fun keyFrameRequested()
    fun input(command: Map<*, *>): Boolean
    fun screenOwnership(owned: Boolean)
    fun sent(bytes: Int, queuedNanos: Long)
    fun dropped(count: Long)
    fun audioSent()
    fun iapControl(stream: BlockingDuplexByteStream): CompletableFuture<Unit>
    fun failed(error: Throwable)
}

/** Controller-side transport. Each instance owns all of its sockets, counters and workers. */
class AirPlaySender(
    private val identity: ControllerIdentity,
    private val pair: (PhonePairing) -> ByteArray,
    private val connect: (Int) -> Socket,
    private val bindDatagram: (DatagramSocket) -> Unit,
    private val events: SenderEvents,
    private val savePair: (PairedAccessory) -> Unit,
    private val legacyAuthentication: ((RtspWire) -> LegacySecurity)? = null,
) : Closeable {
    private val closed = AtomicBoolean()
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val queue = VideoQueue()
    private val clock = NtpClock(bindDatagram)
    private val modes = SessionModes()
    private var control: RtspWire? = null
    private var video: Socket? = null
    private var videoKey: ByteArray? = null
    @Volatile private var avcC: ByteArray? = null
    private var format: SenderDisplay? = null
    private var audioSocket: DatagramSocket? = null
    private var audioAddress: InetSocketAddress? = null
    private var audioKey: ByteArray? = null
    private var audioCounter = 0L
    private var audioSequence = SecureRandom().nextInt(65536)
    private val audioSsrc = SecureRandom().nextInt()
    private var workers = mutableListOf<Thread>()
    private val touchDevices = mutableMapOf<String,HidInput>()
    private val keyDevices = mutableMapOf<String,HidKeys>()
    @Volatile private var iap: PhoneIapTunnel? = null

    private fun <T : Closeable> own(resource: T): T {
        resources += resource
        if (closed.get()) { resource.close(); error("会话已停止") }
        return resource
    }
    private fun run(name: String, action: () -> Unit) {
        val worker = Thread({ try { action() } catch (error: Exception) { if (!closed.get()) events.failed(error) } }, name).apply { isDaemon = true }
        synchronized(workers) { workers += worker }; worker.start()
    }
    fun start(port: Int, enableAudio: Boolean): SenderDisplay {
        val socket = own(connect(port)).apply { soTimeout = 15000; tcpNoDelay = true }
        val wire = own(RtspWire(socket, events::diagnostic)); control = wire
        val pairing = PhonePairing(identity, { path, body -> wire.request("POST", path, body, pairingHeaders).body }, savePair)
        val legacy = legacyAuthentication?.invoke(wire)
        val shared = legacy?.shared ?: pair(pairing)
        fun key(salt: String, label: String) = AirPlayCrypto.hkdfSha512(shared, salt.toByteArray(), label.toByteArray())
        if (legacy == null) wire.cipher = ControlCipher(key("Control-Salt", "Control-Read-Encryption-Key"), key("Control-Salt", "Control-Write-Encryption-Key"))
        val timingPort = clock.listen(); resources += clock
        val setup = plist(wire.request("SETUP", "/", encode(mapOf(
            "deviceID" to identity.id, "name" to "CarPlay Android", "model" to "Android", "sourceVersion" to "320.17",
            "sessionUUID" to java.util.UUID.randomUUID().toString(), "timingPort" to timingPort, "keepAliveLowPower" to true
        )), plistHeaders).body)
        val timing = number(setup["timingPort"]); require(timing in 1..65535)
        clock.start(socket.inetAddress, timing)
        val info = plist(wire.request("GET", "/info").body)
        (info["hidDevices"] as? List<*>)?.forEach { entry ->
            val device = entry as? Map<*, *> ?: return@forEach
            val uuid = device["uuid"] as? String ?: return@forEach
            val descriptor = device["hidDescriptor"] as? ByteArray ?: return@forEach
            try {
                val input = HidInput(descriptor)
                if (input.isTouch) touchDevices[uuid] = input else keyDevices[uuid] = HidKeys(descriptor)
            } catch (error: Exception) { events.diagnostic("输入描述符不支持：${error.message}") }
        }
        val display = (info["displays"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull { (it["widthPixels"] as? Number)?.toInt()?.let { width -> width > 0 } == true }
            ?: error("车机未提供有效显示参数")
        val selected = SenderDisplay(number(display["widthPixels"]), number(display["heightPixels"]),
            number(display["maxFPS"]).also { require(it in 1..60) }, display["uuid"] as? String)
        require(selected.width > 0 && selected.height > 0 && selected.width % 2 == 0 && selected.height % 2 == 0)
        format = selected
        val eventPort = number(setup["eventPort"]); require(eventPort in 1..65535)
        val eventWire = own(RtspWire(own(connect(eventPort)).apply { soTimeout = 0; tcpNoDelay = true }))
        if (legacy == null) eventWire.cipher = ControlCipher(key("Events-Salt", "Events-Write-Encryption-Key"), key("Events-Salt", "Events-Read-Encryption-Key"))
        run("carplay-events") {
            while (!closed.get()) {
                val command = eventWire.next()
                if (command.status == null) { val reply = handleCommand(command.body); eventWire.respond(command,reply.body,reply.status) }
            }
        }
        wire.onRequest = { handleCommand(it.body) }
        val seed = SecureRandom().nextLong().and(Long.MAX_VALUE)
        val iapReply = runCatching {
            streams(wire.request("SETUP", "/", encode(mapOf("streams" to listOf(mapOf(
                "type" to 130, "clientTypeUUID" to IAP_DATASTREAM_UUID,
                "seed" to seed, "streamConnectionID" to seed,
            )))), plistHeaders).body).firstOrNull { number(it["type"]) == 130 }
        }.getOrNull()

        val tunnel: PhoneIapTunnel
        val iapPort = iapReply?.get("dataPort")?.let { runCatching { number(it) }.getOrNull() }?.takeIf { it in 1..65535 }
        if (iapPort != null) {
            events.diagnostic("车机支持 type 130 独立数据流 (port=$iapPort)")
            tunnel = own(PhoneIapTunnel(own(connect(iapPort)).apply { tcpNoDelay = true },
                key("DataStream-Salt$seed", "DataStream-Output-Encryption-Key")))
        } else {
            events.diagnostic("车机未提供 type 130 独立端口，使用 RTSP POST /command iAPSendMessage 传输")
            tunnel = own(PhoneIapTunnel(sendHandler = { data ->
                wire.request("POST", "/command", encode(mapOf(
                    "type" to "iAPSendMessage",
                    "params" to mapOf("data" to data)
                )), plistHeaders)
            }))
        }
        iap = tunnel
        val controlReady = events.iapControl(tunnel)
        own(Closeable { controlReady.cancel(true) })

        if (iapPort != null) {
            events.diagnostic("Wi-Fi iAP2 控制通道已建立，等待独立识别和认证后再启动媒体")
            controlReady.get(45, TimeUnit.SECONDS)
            check(!closed.get()) { "Wi-Fi iAP2 认证完成前连接已停止" }
        }

        events.display(selected)
        val connection = SecureRandom().nextLong().and(Long.MAX_VALUE)
        val stream = linkedMapOf<String, Any>("type" to 110, "streamConnectionID" to connection, "width" to selected.width, "height" to selected.height)
        selected.uuid?.let { stream["displayUUID"] = it }
        val reply = streams(wire.request("SETUP", "/", encode(mapOf("streams" to listOf(stream))), plistHeaders).body)
        val screen = reply.firstOrNull { number(it["type"]) == 110 } ?: error("车机拒绝视频流")
        val dataPort = number(screen["dataPort"]); require(dataPort in 1..65535)
        videoKey = key("DataStream-Salt$connection", "DataStream-Output-Encryption-Key")
        video = own(connect(dataPort))
        if (enableAudio) { check(legacy == null) { "此旧版车机的音频加密格式尚未支持，请关闭音频重试" }; setupAudio(wire, info, shared, socket.inetAddress) }
        wire.request("RECORD", "/")

        if (iapPort == null) {
            runCatching {
                wire.request("POST", "/command", encode(modes.snapshot()), plistHeaders)
                events.diagnostic("已发送初始 modesChanged 控制命令")
            }.onFailure { events.diagnostic("发送初始 modesChanged 失败：${it.message}") }
        }

        run("carplay-video-send") {
            var counter = 0L; var sentConfig: ByteArray? = null; var basePts: Long? = null; var baseNtp = 0L
            val aes = legacy?.videoCipher()
            while (!closed.get()) {
                val frame = queue.take() ?: break
                val config = avcC ?: error("视频编码配置尚未就绪")
                val output = checkNotNull(video).getOutputStream()
                if (sentConfig?.contentEquals(config) != true) { output.write(VideoPackets.config(config, selected.width, selected.height)); sentConfig = config.copyOf() }
                if (basePts == null) { basePts = frame.ptsUs; baseNtp = clock.syncedNtp().toLong() }
                val elapsed = frame.ptsUs - basePts!!; require(elapsed >= 0)
                val ntp = baseNtp + (elapsed / 1_000_000 shl 32) + ((elapsed % 1_000_000) * 4_294_967_296L / 1_000_000)
                val packet = if (aes == null) VideoPackets.frame(frame.bytes, checkNotNull(videoKey), counter++, ntp)
                else {
                    val encrypted = checkNotNull(aes.update(frame.bytes)); check(encrypted.size == frame.bytes.size)
                    ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0,encrypted.size); putLong(8,ntp) }.array() + encrypted
                }
                output.write(packet); output.flush(); events.sent(packet.size, System.nanoTime() - frame.queuedAtNanos)
            }
        }
        run("carplay-feedback") {
            while (!closed.get()) { Thread.sleep(3000); if (!closed.get()) wire.request("POST", "/feedback") }
        }

        if (iapPort == null) {
            events.diagnostic("Wi-Fi 媒体已 RECORD，等待 Wi-Fi iAP2 独立识别和认证完成")
            controlReady.get(45, TimeUnit.SECONDS)
            check(!closed.get()) { "Wi-Fi iAP2 认证完成前连接已停止" }
        }

        return selected
    }
    private fun setupAudio(wire: RtspWire, info: Map<*, *>, shared: ByteArray, address: InetAddress) {
        val supported = (info["audioFormats"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.any {
            (it["type"] as? Number)?.toInt() == 100 && ((it["audioOutputFormats"] as? Number)?.toLong() ?: 0) and 0x800000L != 0L
        } == true
        check(supported) { "车机未支持 AAC-LC 48kHz 双声道，请关闭音频重试" }
        val id = SecureRandom().nextLong().and(Long.MAX_VALUE)
        val local = DatagramSocket(null); bindDatagram(local); local.bind(InetSocketAddress(0)); own(local)
        val response = streams(wire.request("SETUP", "/", encode(mapOf("streams" to listOf(mapOf(
            "type" to 100, "audioType" to "media", "audioFormat" to 0x800000L, "framesPerPacket" to 1024,
            "streamConnectionID" to id, "controlPort" to local.localPort
        )))), plistHeaders).body).firstOrNull { number(it["type"]) == 100 } ?: error("车机拒绝音频流")
        val port = number(response["dataPort"]); require(port in 1..65535)
        audioAddress = InetSocketAddress(address, port); audioSocket = local
        audioKey = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt$id".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray())
        modes.audio(true)
    }
    fun config(bytes: ByteArray) { avcC = bytes.copyOf() }
    fun frame(bytes: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (closed.get() || !modes.ownsScreen()) return
        val before = queue.dropped
        if (!queue.offer(EncodedVideo(bytes, ptsUs, keyFrame))) events.keyFrameRequested()
        val dropped = queue.dropped - before
        if (dropped > 0) events.dropped(dropped)
    }
    @Synchronized fun audio(aac: ByteArray, ptsUs: Long) {
        if (closed.get() || !modes.ownsAudio()) return
        val key = audioKey ?: error("音频通道尚未就绪")
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x80.toByte()); put(100.toByte()); putShort((audioSequence++).toShort()); putInt((ptsUs * 48000 / 1_000_000).toInt()); putInt(audioSsrc)
        }.array()
        val nonce = AirPlayCrypto.nonce64(audioCounter++)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, aac, header.copyOfRange(4,12))
        val packet = header + sealed + nonce.copyOfRange(4,12)
        checkNotNull(audioSocket).send(DatagramPacket(packet, packet.size, audioAddress)); events.audioSent()
    }
    private fun handleCommand(body: ByteArray): ControlReply {
        if (body.isEmpty()) return ControlReply(501)
        val command = plist(body)
        when (command["type"]) {
            "iAPSendMessage" -> {
                val data = (command["params"] as? Map<*, *>)?.get("data") as? ByteArray
                return ControlReply(if (data != null && iap?.offer(data) == true) 200 else 501)
            }
            "forceKeyFrame" -> events.keyFrameRequested()
            "changeModes" -> {
                val response = modes.change(command)
                events.screenOwnership(modes.ownsScreen())
                if (iap?.isCommandTunnel == true) {
                    run("carplay-modes-update") {
                        runCatching { control?.request("POST", "/command", encode(modes.snapshot()), plistHeaders) }
                    }
                }
                return ControlReply(200, encode(response))
            }
            "hidSendReport" -> {
                val device = touchDevices[command["uuid"]?.toString()]
                val keys = keyDevices[command["uuid"]?.toString()]
                val report = command["hidReport"] as? ByteArray
                val accepted = if (device != null && report != null) events.input(mapOf("type" to "touchSamples", "samples" to device.touches(report)))
                    else if (keys != null && report != null) events.input(mapOf("type" to "keySamples","report" to keys.report(report))) else events.input(command)
                return ControlReply(if (accepted) 200 else 501)
            }
            "requestSiri", "requestUI", "setNightMode", "updateViewArea" -> return ControlReply(if (events.input(command)) 200 else 501)
            else -> { events.diagnostic("不支持的车机命令：${command["type"]}"); return ControlReply(501) }
        }
        return ControlReply(200)
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        queue.close(); resources.reversed().forEach { runCatching { it.close() } }; resources.clear()
        synchronized(workers) { workers.forEach { it.interrupt() }; workers.clear() }
    }
    companion object {
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private val plistHeaders = mapOf("Content-Type" to "application/x-apple-binary-plist")
        private val pairingHeaders = mapOf("Content-Type" to "application/pairing+tlv8", "X-Apple-HKP" to "3")
        private fun encode(value: Any) = BplistCodec.encode(value)
        private fun plist(bytes: ByteArray) = BplistCodec.decode(bytes) as? Map<*, *> ?: error("车机返回无效 plist")
        private fun number(value: Any?) = (value as? Number)?.toInt() ?: error("车机参数缺失")
        private fun streams(bytes: ByteArray) = (plist(bytes)["streams"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: error("车机未返回流信息")
    }
}
