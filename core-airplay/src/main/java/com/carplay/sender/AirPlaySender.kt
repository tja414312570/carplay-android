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
import java.util.ArrayDeque

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
    fun audioFormat(format: SenderAudioFormat)
    fun bluetoothControlAvailable(): Boolean
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
    private val localAddress: () -> InetAddress? = { null },
) : Closeable {
    private val closed = AtomicBoolean()
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val queue = VideoQueue()
    private val clock = NtpClock(localAddress,bindDatagram)
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
    private var commandModes = false
    private var selectedAudio: SenderAudioFormat? = null
    private val iapLock = Any()
    private var openingIap = false
    private val earlyIap = ArrayDeque<ByteArray>()
    private var earlyIapBytes = 0

    private fun receiveIap(data: ByteArray): Boolean = synchronized(iapLock) {
        if (closed.get()) return@synchronized false
        iap?.let { return@synchronized it.offer(data) }
        if (!openingIap) return@synchronized false
        check(data.size <= 1_048_576 - earlyIapBytes) { "iAP 启动接收队列超出限制" }
        earlyIap.addLast(data.copyOf()); earlyIapBytes += data.size
        true
    }

    private fun receiveIapResponse(message: ControlMessage, typedOnly: Boolean) {
        if (message.body.isEmpty()) return
        val decoded = try { BplistCodec.decode(message.body) } catch (error: Exception) {
            if (typedOnly) return
            throw java.net.ProtocolException("iAP 命令响应不是有效 plist").apply { initCause(error) }
        }
        if (typedOnly && (decoded as? Map<*, *>)?.get("type") != "iAPSendMessage") return
        val received = IapCommandData.payload(decoded,response = true)
        if (received != null) check(receiveIap(received)) { "iAP 响应数据未被控制通道接收" }
        events.diagnostic("iAP 命令回复：bodyBytes=${message.body.size}，字段数=${(decoded as? Map<*, *>)?.size ?: 0}，控制字节=${received?.size ?: 0}")
    }

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
                else if (command.status in 200..299) receiveIapResponse(command,typedOnly = true)
            }
        }
        wire.onRequest = { handleCommand(it.body) }
        wire.onResponse = { receiveIapResponse(it,typedOnly = true) }
        synchronized(iapLock) { openingIap = true }
        val seed = SecureRandom().nextLong().and(Long.MAX_VALUE)
        val iapReply = plist(wire.request("SETUP", "/", encode(mapOf("streams" to listOf(mapOf(
                "type" to 130, "clientTypeUUID" to IAP_DATASTREAM_UUID,
                "seed" to seed, "streamConnectionID" to seed,
            )))), plistHeaders).body)
        val transport = synchronized(iapLock) {
            val selectedTransport = SenderNegotiation.control(iapReply)
            if (selectedTransport.kind == SenderControlKind.AUTHENTICATED_BLUETOOTH && earlyIap.isNotEmpty()) {
                events.diagnostic("已收到车机实际发起的 iAP 命令；建立命令控制通道并执行独立验证")
                SenderControlTransport(SenderControlKind.WIFI_COMMAND)
            } else selectedTransport.also { if (it.kind == SenderControlKind.AUTHENTICATED_BLUETOOTH) openingIap = false }
        }
        events.diagnostic("type 130 协商结果：${transport.kind}，回复字段数=${iapReply.size}")
        commandModes = transport.kind != SenderControlKind.WIFI_DATASTREAM
        val tunnel = when (transport.kind) {
            SenderControlKind.WIFI_DATASTREAM -> own(PhoneIapTunnel(own(connect(checkNotNull(transport.port))).apply { tcpNoDelay = true },
                key("DataStream-Salt$seed", "DataStream-Output-Encryption-Key")))
            SenderControlKind.WIFI_COMMAND -> own(PhoneIapTunnel(sendHandler = { data ->
                wire.request("POST", "/command", encode(mapOf(
                    "type" to "iAPSendMessage",
                    "params" to mapOf("data" to data)
                )), plistHeaders) { receiveIapResponse(it,typedOnly = false) }
            }))
            SenderControlKind.AUTHENTICATED_BLUETOOTH -> {
                check(events.bluetoothControlAvailable()) { "车机未接受 type 130，且已认证的蓝牙控制通道不可用" }
                events.diagnostic("车机未接受 type 130；保留实际已认证的蓝牙控制，Wi-Fi 媒体继续使用配对验证后的加密连接")
                null
            }
        }
        synchronized(iapLock) {
            check(!closed.get()) { "iAP 通道建立前会话已关闭" }
            iap = tunnel; openingIap = false
            while (earlyIap.isNotEmpty()) check(tunnel?.offer(earlyIap.removeFirst()) == true) { "启动 iAP 数据未能交接" }
            earlyIapBytes = 0
        }
        val controlReady = tunnel?.let(events::iapControl)
        controlReady?.let { own(Closeable { it.cancel(true) }) }

        if (transport.kind == SenderControlKind.WIFI_DATASTREAM) {
            events.diagnostic("Wi-Fi iAP2 控制通道已建立，等待独立识别和认证后再启动媒体")
            checkNotNull(controlReady).get(45, TimeUnit.SECONDS)
            check(!closed.get()) { "Wi-Fi iAP2 认证完成前连接已停止" }
        }

        if (enableAudio) {
            check(legacy == null) { "此旧版车机的音频加密格式尚未支持，请关闭音频重试" }
            selectedAudio = SenderNegotiation.audio(info).also {
                events.diagnostic("音频协商：type=${it.streamType}，AAC-LC ${it.sampleRate} Hz / ${it.channels} 声道，按车机 audioOutputFormats 选择")
                events.audioFormat(it)
            }
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
        if (enableAudio) setupAudio(wire,checkNotNull(selectedAudio),shared,socket.inetAddress)
        wire.request("RECORD", "/")

        if (commandModes) {
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

        if (transport.kind == SenderControlKind.WIFI_COMMAND) {
            events.diagnostic("Wi-Fi 媒体已 RECORD，等待 Wi-Fi iAP2 独立识别和认证完成")
            checkNotNull(controlReady).get(45, TimeUnit.SECONDS)
            check(!closed.get()) { "Wi-Fi iAP2 认证完成前连接已停止" }
        }

        return selected
    }
    private fun setupAudio(wire: RtspWire, format: SenderAudioFormat, shared: ByteArray, address: InetAddress) {
        val id = SecureRandom().nextLong().and(Long.MAX_VALUE)
        val local = own(DatagramSocket(null)); bindDatagram(local); local.bind(InetSocketAddress(localAddress(),0))
        val response = streams(wire.request("SETUP", "/", encode(mapOf("streams" to listOf(mapOf(
            "type" to format.streamType, "audioType" to "media", "audioFormat" to format.formatBits, "framesPerPacket" to 1024,
            "streamConnectionID" to id, "controlPort" to local.localPort
        )))), plistHeaders).body).firstOrNull { number(it["type"]) == format.streamType } ?: error("车机拒绝协商后的音频流")
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
            put(0x80.toByte()); put(100.toByte()); putShort((audioSequence++).toShort()); putInt(checkNotNull(selectedAudio).rtpTimestamp(ptsUs)); putInt(audioSsrc)
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
                val data = IapCommandData.payload(command)
                val accepted = data != null && receiveIap(data)
                events.diagnostic("收到 iAP 控制命令：字节=${data?.size ?: 0}，已接收=$accepted")
                return ControlReply(if (accepted) 200 else 501)
            }
            "forceKeyFrame" -> events.keyFrameRequested()
            "changeModes" -> {
                val response = modes.change(command)
                events.screenOwnership(modes.ownsScreen())
                if (commandModes) {
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
        synchronized(iapLock) { openingIap = false; earlyIap.clear(); earlyIapBytes = 0 }
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
