package com.carplay.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.carplay.adapter.*
import com.carplay.app.diagnostics.*
import com.carplay.iap2.controller.*
import com.carplay.iap2.session.Iap2Session
import com.carplay.iap2.transport.BluetoothRfcommDuplexStream
import com.carplay.iap2.transport.BlockingDuplexByteStream
import com.carplay.iap2.transport.RfcommConnectAttempt
import com.carplay.iap2.transport.RfcommConnectionStrategy
import com.carplay.iap2.transport.RfcommConnectionDirection
import com.carplay.iap2.transport.RegisteredRfcommService
import com.carplay.iap2.transport.PhoneBluetoothProfile
import com.carplay.iap2.transport.PhoneBluetoothSessionTransport
import com.carplay.iap2.transport.Iap2PeerDetectionRestart
import com.carplay.sender.*
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

class WirelessConnection(private val context: Context, private val id: String, private val selectedPeer: String,
    private val adapter: PhoneProjectionAdapter, private val settings: SettingsStore) : Closeable {
    private val closed = AtomicBoolean()
    private val resources = CopyOnWriteArrayList<AutoCloseable>()
    private var sender: AirPlaySender? = null
    private val configuration = LinkedBlockingQueue<WirelessEndpoint>(8)
    private val errors = LinkedBlockingQueue<Throwable>(1)
    private var receiverThread: Thread? = null
    private var negotiatedDisplay: SenderDisplay? = null
    private val contacts = mutableSetOf<Int>()
    private val bluetoothRetired = AtomicBoolean()
    private var phoneService: RegisteredRfcommService<BluetoothServerSocket>? = null
    private var phoneListener: BluetoothServerSocket? = null
    private fun <T : AutoCloseable> own(resource: T): T {
        resources += resource
        if (closed.get()) { resource.close(); error("连接已停止") }; return resource
    }
    private fun stage(stage: Stage, detail: String) { check(!closed.get()); Diagnostics.stage(id,stage,detail) }
    fun run() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "蓝牙连接权限已撤销" }
            check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "蓝牙发现权限已撤销" }
        }
        check(Build.VERSION.SDK_INT >= 29) { "当前无线网络连接实现需要 Android 10 或更高版本" }
        val requireRoot = settings.requireRoot
        check(!requireRoot || settings.hasTrust()) { "请导入合法来源的车机认证根证书后连接" }
        val identity = settings.identity()
        val connectionDirection = settings.connectionDirection
        val bootstrapProfile = settings.bootstrapProfile
        stage(Stage.BLUETOOTH, if (connectionDirection == RfcommConnectionDirection.VEHICLE_INITIATED)
            "准备手机 iAP2 服务，等待车机选择此手机" else "主动连接已选择车机的 iAP2 服务")
        val bluetooth = context.getSystemService(BluetoothManager::class.java).adapter ?: error("手机不支持蓝牙")
        check(bluetooth.isEnabled) { "请打开蓝牙" }
        val localAddress = LocalBluetoothAddressReader.read(context, bluetooth, settings.phoneBluetoothAddress)
        Diagnostics.event("INFO", localAddress?.let { "本机蓝牙传输标识来源：${it.source}；手机 UUID 单独保留" }
            ?: "系统未提供本机蓝牙地址；现代无线发布或传输通知需要在诊断设置填写手机自身的真实地址", id)
        Diagnostics.event("INFO","连接方式=$connectionDirection，无线请求方案=$bootstrapProfile",id)
        val transportIdentifier = {
            checkNotNull(localAddress) { "系统未开放本机蓝牙地址，请在诊断设置填写手机自身的真实蓝牙地址" }.value
        }
        val peer = bluetooth.getRemoteDevice(selectedPeer)
        check(peer.bondState == BluetoothDevice.BOND_BONDED) { "所选车机没有完成系统蓝牙配对" }
        Diagnostics.event("INFO","蓝牙前置状态：enabled=${bluetooth.isEnabled}，scanMode=${bluetooth.scanMode}，bondState=${peer.bondState}",id)
        bluetooth.cancelDiscovery()
        var bluetoothLink = negotiateBluetooth(bluetooth, peer, connectionDirection)
        val socket = bluetoothLink.socket
        var session = bluetoothLink.session
        Diagnostics.event("INFO","RFCOMM 实际已连接：connected=${socket.isConnected}，bondState=${socket.remoteDevice.bondState}",id)
        val trust: AccessoryAuthentication = if (requireRoot) settings.trust() else BondedAccessoryTrust(
            settings.accessoryKey(selectedPeer), socket.isConnected && socket.remoteDevice.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED, { key ->
                settings.saveAccessoryKey(selectedPeer,key)
                Diagnostics.event("INFO","已验证签名并绑定所选配对车机的认证公钥；未验证 MFi 信任链",id)
            })
        Diagnostics.event("INFO",if (requireRoot) "认证模式：根证书信任链" else "认证模式：安全蓝牙配对与公钥绑定；未验证 MFi 信任链",id)
        stage(Stage.IDENTIFY, "蓝牙链路已同步，识别车机")
        fun publishEndpoint(endpoint: WirelessEndpoint) {
            if (!closed.get() && !bluetoothRetired.get() && !configuration.offer(endpoint)) {
                errors.offer(IllegalStateException("无线配置更新过多"))
            }
        }
        val endpointSelectionLock = Any()
        var receiverStop = AtomicBoolean()
        fun createBootstrap(currentSession: Iap2Session, stop: AtomicBoolean, expectedIdentity: ByteArray? = null,
            onEndpoint: (WirelessEndpoint) -> Unit = ::publishEndpoint) =
            PhoneBootstrap(identity.id, trust::validate, trust::verifyAuthentication, currentSession::send,
            { message -> Diagnostics.event("INFO",message,id) },
            { endpoint -> synchronized(endpointSelectionLock) {
                if (!closed.get() && !stop.get() && !bluetoothRetired.get()) onEndpoint(endpoint)
            } },
            { frame -> Diagnostics.event("INFO","未处理的车机控制消息：0x${frame.messageId.toString(16)}",id) },
            { status ->
                val phase = when (status.phase) {
                    BootstrapPhase.IDENTIFY -> Stage.IDENTIFY
                    BootstrapPhase.CERTIFICATE, BootstrapPhase.CHALLENGE -> Stage.AUTHENTICATE
                    BootstrapPhase.WIRELESS_CONFIGURATION, BootstrapPhase.READY -> Stage.NETWORK
                    BootstrapPhase.CONTROL_READY -> Stage.PAIRING
                }
                val detail = when (status.phase) {
                    BootstrapPhase.IDENTIFY -> "等待车机识别信息"
                    BootstrapPhase.CERTIFICATE -> "识别信息已收到，等待车机认证证书"
                    BootstrapPhase.CHALLENGE -> "认证挑战已发送，等待车机签名响应"
                    BootstrapPhase.WIRELESS_CONFIGURATION -> "车机签名已验证，等待无线配置"
                    BootstrapPhase.READY -> "认证通过，无线配置已收到"
                    BootstrapPhase.CONTROL_READY -> "Wi-Fi 控制通道已认证"
                }
                val current = Diagnostics.status.value
                if (!closed.get() && !stop.get() && current.id == id && current.stage !in setOf(Stage.FAILED,Stage.STOPPED) && current.stage.step <= phase.step) {
                    Diagnostics.stage(id,phase,detail)
                }
            }, profile = bootstrapProfile, transportIdentifier = transportIdentifier,
                expectedAccessoryIdentity = expectedIdentity)
        var bootstrap = createBootstrap(session, receiverStop)
        bootstrap.begin()
        fun startReceiver(currentSession: Iap2Session, currentBootstrap: PhoneBootstrap, stop: AtomicBoolean) = Thread({
            try {
                while (!closed.get() && !bluetoothRetired.get() && !stop.get()) {
                    val frame = currentSession.recv(1000)
                    if (frame != null) currentBootstrap.receive(frame) else if (currentSession.isClosed) error("iAP2 控制连接关闭")
                }
            } catch (error: Exception) { if (!closed.get() && !bluetoothRetired.get() && !stop.get()) errors.offer(error) }
        }, "carplay-controller-csm").apply { isDaemon = true; start() }
        receiverThread = startReceiver(session, bootstrap, receiverStop)
        var endpoint: WirelessEndpoint? = null
        val configDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val recovery = PhoneBluetoothRecovery(configDeadline)
        var returnAttempted = false
        fun tryReturnConnection(): IncomingControl? {
            if (returnAttempted || !bluetoothLink.outbound || !bootstrap.status().authenticated ||
                !bootstrap.status().networkRequestQueued || configuration.isNotEmpty()) return null
            val listener = phoneListener ?: return null
            val acceptBudget = minOf(100, recovery.remainingMillis()).toInt()
            if (acceptBudget <= 0) return null
            val accepted = try { listener.accept(acceptBudget) } catch (_: java.io.IOException) { return null }
            own(accepted)
            if (!accepted.remoteDevice.address.equals(selectedPeer, true) || accepted.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                accepted.close(); resources.remove(accepted)
                Diagnostics.event("WARN","拒绝非选定或未配对设备对手机服务的连接",id)
                return null
            }
            returnAttempted = true
            Diagnostics.event("INFO","手机 cafe 服务收到所选配对车机回连；独立验证，在收到有效网络配置前保留主动通道",id)
            phoneService?.close(); phoneListener = null
            val transport = own(PhoneBluetoothSessionTransport(BluetoothRfcommDuplexStream(accepted) {
                Diagnostics.event("TRACE",it,id)
            }))
            val incoming = openBluetoothSession(transport.open(), bluetoothLink.profile, 3)
            val stop = AtomicBoolean()
            var transferred = false
            var previousDetection: Iap2PeerDetectionRestart? = null
            try {
                if (!incoming.awaitPhoneBluetoothReady(bluetoothLink.profile, recovery.remainingMillis()) {
                    closed.get() || bluetoothRetired.get() || configuration.isNotEmpty()
                }) {
                    Diagnostics.event("WARN","车机回连未完成同步或主动通道已收到配置；保留原控制预算",id)
                    return null
                }
                var received: WirelessEndpoint? = null
                val controller = createBootstrap(incoming, stop, checkNotNull(bootstrap.accessoryIdentityFingerprint())) { update ->
                    if (transferred) publishEndpoint(update) else received = update
                }
                controller.begin()
                while (!closed.get() && recovery.remainingMillis() > 0) {
                    errors.poll()?.let { failure ->
                        if (failure is Iap2PeerDetectionRestart && previousDetection == null) {
                            previousDetection = failure
                            Diagnostics.event("INFO","主动通道重新检测；正在验证实际收到的车机回连，不沿用旧认证",id)
                        } else throw failure
                    }
                    if (configuration.isNotEmpty()) return null
                    val frame = incoming.recv(minOf(100, recovery.remainingMillis()))
                    if (frame != null) controller.receive(frame) else check(!incoming.isClosed) { "车机回连控制通道已关闭" }
                    received?.let {
                        transferred = true
                        return IncomingControl(BluetoothLink(accepted, incoming, transport, bluetoothLink.profile, false),
                            controller, stop, it, previousDetection)
                    }
                }
                return null
            } finally {
                if (!transferred) {
                    stop.set(true)
                    runCatching { incoming.close() }; transport.close()
                    resources.remove(incoming); resources.remove(transport); resources.remove(accepted)
                    previousDetection?.let { errors.offer(it) }
                }
            }
        }
        while (endpoint == null && !closed.get() && System.nanoTime() < configDeadline) {
            errors.poll()?.let { failure ->
                if (failure !is Iap2PeerDetectionRestart) throw failure
                if (!recovery.claim(bootstrap.accessoryVersionInfo(), bootstrap.status(), bootstrapProfile, closed.get())) {
                    Diagnostics.event("WARN","重新检测恢复未执行：${recovery.lastRejection}",id)
                    throw failure
                }
                val expectedIdentity = checkNotNull(bootstrap.accessoryIdentityFingerprint())
                receiverThread?.join(1000)
                check(receiverThread?.isAlive != true) { "旧蓝牙控制线程未结束，不能重新协商" }
                try { session.close() } catch (closeFailure: Exception) {
                    if (closeFailure !== failure) throw closeFailure
                }
                check(failure.suppressed.isEmpty()) { "旧蓝牙协议清理失败，不能重新协商" }
                resources.remove(session)
                receiverStop.set(true)
                check(!closed.get()) { "连接已停止" }
                Diagnostics.event("INFO","匹配已观察的 GM A2LL 固件：车机重新检测，允许一次新 iAP2 会话；保留同一 RFCOMM，重新验证身份，剩余控制预算=${recovery.remainingMillis()}ms",id)
                stage(Stage.IDENTIFY,"车机要求重新检测，正在重新识别和认证（1/1）")
                session = openBluetoothSession(bluetoothLink.transport.open(failure.initialInput()), bluetoothLink.profile, 2)
                check(session.awaitPhoneBluetoothReady(bluetoothLink.profile, recovery.remainingMillis())) {
                    "车机重新检测后的 iAP2 同步未完成，控制预算不延长"
                }
                check(recovery.remainingMillis() > 0 && !closed.get()) { "重新协商已超时或停止" }
                receiverStop = AtomicBoolean()
                bootstrap = createBootstrap(session, receiverStop, expectedIdentity)
                bootstrap.begin()
                receiverThread = startReceiver(session, bootstrap, receiverStop)
            }
            tryReturnConnection()?.let { incoming ->
                val chooseReturn = synchronized(endpointSelectionLock) {
                    if (configuration.isNotEmpty()) false else { receiverStop.set(true); true }
                }
                if (!chooseReturn) {
                    incoming.stop.set(true)
                    runCatching { incoming.link.session.close() }; incoming.link.transport.close()
                    resources.remove(incoming.link.session); resources.remove(incoming.link.transport); resources.remove(incoming.link.socket)
                    incoming.previousDetection?.let { throw it }
                    return@let
                }
                try { session.close() } catch (failure: Exception) {
                    if (failure !is Iap2PeerDetectionRestart || failure.suppressed.isNotEmpty()) throw failure
                }
                receiverThread?.join(1000)
                check(receiverThread?.isAlive != true) { "主动通道线程未结束，不能交接" }
                errors.poll()?.let {
                    if (it !is Iap2PeerDetectionRestart || it.suppressed.isNotEmpty()) throw it
                }
                bluetoothLink.transport.close()
                resources.remove(session); resources.remove(bluetoothLink.transport); resources.remove(bluetoothLink.socket)
                bluetoothLink = incoming.link
                session = incoming.link.session
                bootstrap = incoming.bootstrap
                receiverStop = incoming.stop
                endpoint = incoming.endpoint
                receiverThread = startReceiver(session, bootstrap, receiverStop)
                Diagnostics.event("INFO","车机回连已重新认证并提供有效无线配置，主动通道已交接",id)
            }
            if (endpoint != null) break
            endpoint = configuration.poll(250, TimeUnit.MILLISECONDS)
        }
        if (connectionDirection == RfcommConnectionDirection.PHONE_INITIATED) {
            phoneService?.close(); phoneListener = null
        }
        val initial = checkNotNull(endpoint) {
            val status = session.handshakeStatus()
            Diagnostics.event("WARN","控制阶段超时：已完成写入=${status.sentBytes}字节，已接收=${status.receivedBytes}字节，控制会话=${status.controlSessionId}；身份=${bootstrap.status().identified}，签名验证=${bootstrap.status().authenticated}",id)
            val detail = bootstrap.status().timeoutDetail()
            if (bootstrap.status().authenticated && bootstrap.accessoryVersionInfo() == PhoneBluetoothRecovery.OBSERVED_FIRMWARE) {
                "$detail；若为首次连接请在车机屏幕确认无线 CarPlay 弹窗或点击车机投屏图标触发第二阶段会话"
            } else detail
        }
        stage(Stage.NETWORK, "加入车机 WLAN 热点")
        val network = join(initial, bootstrap.accessoryVersionInfo())
        stage(Stage.DISCOVERY, "发布手机控制服务，确认车机投屏端点")
        // Use the same immutable phone transport value as 4300; keep the crypto UUID separate.
        val controlTransport = if ((bootstrapProfile.usesPhoneTransportString || bootstrapProfile == PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL) && localAddress != null) {
            localAddress.value
        } else identity.id
        val resolved = discover(network, initial, controlTransport)
        while (true) {
            val update = configuration.poll() ?: break
            endpoint = endpoint!!.merge(update)
        }
        val config = endpoint ?: initial
        val rawPeerKey = config.publicKey ?: resolved.publicKey
        val peerKey = rawPeerKey?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            ?.let { raw -> raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        val peerPairingIdentity = rawPeerKey?.takeIf { peerKey == null }
        val port = config.port ?: resolved.port
        var receiver: InetAddress? = null
        stage(Stage.PAIRING, "连接车机并验证配对身份")
        val channel = own(AirPlaySender(identity, { pairing ->
            val saved = settings.paired(selectedPeer)
            if (saved != null) {
                try {
                    if (peerKey != null) {
                        check(AccessoryTrust.equal(saved.publicKey, peerKey)) { "车机配对公钥已改变，请重新确认身份" }
                    }
                    if (peerPairingIdentity != null) {
                        check(saved.id.equals(peerPairingIdentity, ignoreCase = true)) { "车机配对标识已改变，请重新确认身份" }
                    }
                    Diagnostics.event("INFO", "使用已保存的车机配对身份进行验证", id)
                    pairing.verify(saved)
                } catch (verifyError: Exception) {
                    Diagnostics.event("WARN", "车机已保存配对验证失败 (${verifyError.message})，清除旧配对并重新执行配对流程", id)
                    settings.removePair(selectedPeer)
                    val newPaired = pairing.setup("3939", advertisedKey = peerKey, expectedIdentity = peerPairingIdentity)
                        .also { settings.savePair(selectedPeer, it) }
                    pairing.verify(newPaired)
                }
            } else {
                val newPaired = pairing.setup("3939", advertisedKey = peerKey, expectedIdentity = peerPairingIdentity)
                    .also { settings.savePair(selectedPeer, it) }
                pairing.verify(newPaired)
            }
        }, { targetPort ->
            val targets = receiver?.let(::listOf) ?: resolved.addresses
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var connected: Socket? = null
            var lastFailure: java.io.IOException? = null
            for (target in targets) {
                check(!closed.get()) { "连接已停止" }
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                if (remaining == 0L) break
                val tcp = own(Socket())
                try {
                    network.bindSocket(tcp)
                    tcp.connect(InetSocketAddress(target,targetPort),remaining.toInt())
                    receiver = target; connected = tcp
                    break
                } catch (failure: java.io.IOException) {
                    tcp.close(); resources.remove(tcp); lastFailure = failure
                }
            }
            connected ?: throw java.io.IOException("车机服务连接失败或超时，已使用所选 Wi-Fi 网络",lastFailure)
        }, { network.bindSocket(it) }, object : SenderEvents {
            override fun diagnostic(message: String) { Diagnostics.event("INFO",message,id) }
            override fun display(display: SenderDisplay) {
                negotiatedDisplay = display
                stage(Stage.MEDIA,"车机显示 ${display.width}×${display.height}，协商 ${display.fps} fps；准备投屏来源")
                runBlocking {
                    check(adapter.prepare(context,display.width,display.height,display.fps)) { "投屏来源未准备就绪" }
                    adapter.startProjection(checkNotNull(sender)::frame)
                }
                Diagnostics.event("INFO","投屏来源已启动，编码输出在有界队列中等待媒体 RECORD",id)
            }
            override fun keyFrameRequested() { adapter.requestKeyFrame() }
            override fun input(command: Map<*, *>) = dispatch(command)
            override fun screenOwnership(owned: Boolean) { Diagnostics.event("INFO",if (owned) "车机已将屏幕交给手机" else "车机使用原生界面，暂停画面发送",id); if (owned) adapter.requestKeyFrame() }
            override fun sent(bytes: Int, queuedNanos: Long) {
                if (Diagnostics.status.value.frames == 0L) Diagnostics.stage(id,Stage.STREAMING,"首帧已发送，车机显示效果需实际确认")
                Diagnostics.video(id,bytes)
            }
            override fun dropped(count: Long) { repeat(count.toInt()) { Diagnostics.video(id,0,true) } }
            override fun audioSent() { Diagnostics.audio(id) }
            override fun iapControl(stream: BlockingDuplexByteStream): CompletableFuture<Unit> {
                val completion = CompletableFuture<Unit>()
                own(AutoCloseable { completion.cancel(true) })
                val runtime = own(Iap2Session.controllerTunnel(stream) { Diagnostics.event("TRACE",it.lineSequence().first(),id) })
                val expectedIdentity = checkNotNull(bootstrap.accessoryIdentityFingerprint())
                val worker = Thread({
                    try {
                        check(runtime.awaitReady(15000)) { "Wi-Fi iAP2 链路同步超时" }
                        val ready = AtomicBoolean()
                        stage(Stage.PAIRING,"Wi-Fi 控制通道已同步，正在独立识别和验证车机")
                        val control = PhoneBootstrap(identity.id,trust::validate,trust::verifyAuthentication,runtime::send,
                            { Diagnostics.event("INFO","Wi-Fi 控制：$it",id) }, { update ->
                                config.merge(update)
                                check(update.port == null || update.port == port) { "Wi-Fi 控制通道改变车机端口" }
                                check(update.receiverId == null || config.receiverId == null || update.receiverId == config.receiverId) { "Wi-Fi 控制通道改变车机标识" }
                                check(update.addresses.isEmpty() || PeerAddresses.matches(update.addresses,checkNotNull(receiver))) { "Wi-Fi 控制通道改变车机地址" }
                                check(update.publicKey == null || peerKey?.joinToString("") { "%02x".format(it) }?.equals(update.publicKey,true) == true || peerPairingIdentity?.equals(update.publicKey,true) == true) { "Wi-Fi 控制通道改变配对公钥" }
                            }, { frame -> Diagnostics.event("INFO","Wi-Fi 控制消息 0x${frame.messageId.toString(16)} 已接收",id) },
                            profile = bootstrapProfile, transportIdentifier = transportIdentifier,
                            expectedAccessoryIdentity = expectedIdentity, purpose = PhoneBootstrapPurpose.CONTROL_SESSION)
                        control.begin()
                        val authDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                        while (!closed.get()) {
                            if (!ready.get()) check(System.nanoTime() < authDeadline) { "Wi-Fi iAP2 认证超时" }
                            val frame = runtime.recv(1000)
                            if (frame != null) control.receive(frame) else check(!runtime.isClosed) { "Wi-Fi iAP2 控制通道关闭" }
                            if (control.status().controlReady && ready.compareAndSet(false,true)) {
                                check(!closed.get()) { "连接已停止" }
                                val remaining = ((authDeadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                                check(runtime.awaitOutboundAcknowledged(minOf(5000,remaining))) { "Wi-Fi 认证完成通知未获链路确认" }
                                bluetoothRetired.set(true)
                                receiverStop.set(true)
                                session.close()
                                bluetoothLink.transport.close()
                                receiverThread?.join(1000)
                                check(receiverThread?.isAlive != true) { "蓝牙控制线程未结束，不能完成交接" }
                                Diagnostics.event("INFO","Wi-Fi iAP2 独立身份与签名验证通过，蓝牙控制已交接；此通道不重复请求无线配置",id)
                                completion.complete(Unit)
                            }
                        }
                    } catch (error: Exception) {
                        completion.completeExceptionally(error)
                        if (!closed.get()) { errors.offer(error); close() }
                    } finally { if (!completion.isDone) completion.cancel(true) }
                },"carplay-wifi-csm").apply { isDaemon = true }
                own(AutoCloseable { worker.interrupt() })
                worker.start()
                return completion
            }
            override fun failed(error: Throwable) { errors.offer(error); close() }
        }, { settings.savePair(selectedPeer,it) }, if (peerKey == null && peerPairingIdentity == null) { wire ->
            Diagnostics.event("INFO","车机使用旧版认证，验证其证书链和 MFiSAP 签名",id)
            MfiController.authenticate({ bytes -> wire.request("POST","/auth-setup",bytes,mapOf("Content-Type" to "application/octet-stream")).body },trust)
        } else null))
        sender = channel
        adapter.setObserver(object : ProjectionObserver {
            override fun videoConfig(avcC: ByteArray) { channel.config(avcC) }
            override fun audioFrame(aac: ByteArray, sampleTimeUs: Long) { try { channel.audio(aac,sampleTimeUs) } catch (e: Exception) { failed(e) } }
            override fun diagnostic(message: String) { Diagnostics.event("INFO",message,id) }
            override fun failed(error: Throwable) { errors.offer(error); close() }
        })
        channel.start(port,settings.audio)
        while (!closed.get()) {
            errors.poll(1,TimeUnit.SECONDS)?.let { throw it }
            val update = configuration.poll()
            if (update != null) check(config.merge(update) == config) { "连接期间端点发生变化，请重新连接" }
        }
        errors.poll()?.let { throw it }
    }

    private data class BluetoothLink(val socket: BluetoothSocket, val session: Iap2Session,
        val transport: PhoneBluetoothSessionTransport, val profile: PhoneBluetoothProfile, val outbound: Boolean)
    private data class IncomingControl(val link: BluetoothLink, val bootstrap: PhoneBootstrap,
        val stop: AtomicBoolean, val endpoint: WirelessEndpoint, val previousDetection: Iap2PeerDetectionRestart?)
    private data class ReceiverRoute(val addresses: List<InetAddress>, val port: Int, val publicKey: String?)

    private fun openBluetoothSession(stream: BlockingDuplexByteStream, profile: PhoneBluetoothProfile, epoch: Int) =
        own(Iap2Session.phoneBluetooth(stream, profile) {
            Diagnostics.event("TRACE","${it.lineSequence().first()} epoch=$epoch",id)
        })

    private fun negotiateBluetooth(bluetooth: BluetoothAdapter, peer: BluetoothDevice,
        direction: RfcommConnectionDirection): BluetoothLink {
        var inboundMode = false
        val selectedProfile = settings.bluetoothProfile
        val profiles = selectedProfile.attempts()
        Diagnostics.event("INFO","蓝牙兼容配置：${selectedProfile.label}；只在缺少有效 SYN 时按顺序回退",id)
        for ((index, profile) in profiles.withIndex()) {
            check(!closed.get()) { "连接已停止" }
            Diagnostics.event("INFO","蓝牙握手方案 ${index+1}/${profiles.size}：${profile.label}，同步等待 ${profile.readyTimeoutMillis}ms",id)
            var outgoing = false
            val socket = if (inboundMode) listenInbound(bluetooth,true) else RfcommConnectionStrategy.connectWithPhoneService(direction,
                { registerPhoneService(bluetooth) }, { connectOutbound(peer).also { outgoing = true } },
                { listenInbound(bluetooth,true) }, closed::get,
                { error -> Diagnostics.event("WARN","手机 SDP 注册失败：${error.javaClass.simpleName}；继续手机主动连接",id) }, { error ->
                    inboundMode = true
                    Diagnostics.event("WARN","主动 RFCOMM 未建立：${error.javaClass.simpleName}: ${error.message}；切换手机监听",id)
                })
            stage(Stage.BLUETOOTH, if (profile.accessoryInitiates)
                "RFCOMM 已建立，等待车机检测包和 SYN" else "RFCOMM 已建立，协商 iAP2 链路")
            val transport = own(PhoneBluetoothSessionTransport(BluetoothRfcommDuplexStream(socket) {
                Diagnostics.event("TRACE",it,id)
            }))
            val session = openBluetoothSession(transport.open(),profile,1)
            if (session.awaitPhoneBluetoothReady(profile)) return BluetoothLink(socket, session, transport, profile, outgoing)
            val status = session.handshakeStatus()
            Diagnostics.event("WARN","iAP2 同步未完成：方案=${profile.label}，状态=${status.state}，已发送=${status.sentBytes}字节，已接收=${status.receivedBytes}字节，车机SYN=${status.peerSynchronization != null}",id)
            // The old protocol instance and its socket must end before changing profiles.
            session.close()
            transport.close()
            resources.remove(session)
            resources.remove(transport)
            resources.remove(socket)
            check(!closed.get()) { "连接已停止" }
            check(status.peerSynchronization == null) { "车机 SYN 已收到，但未确认手机同步包" }
            check(index < profiles.lastIndex) { "iAP2 同步失败：所有握手方案均未收到有效车机 SYN" }
            Diagnostics.event("INFO","当前连接没有有效车机 SYN，已关闭旧连接，重新连接后切换握手方案",id)
        }
        error("没有可用的 iAP2 握手方案")
    }

    private fun connectOutbound(peer: BluetoothDevice): BluetoothSocket {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "蓝牙连接权限已撤销" }
        }
        check(peer.bondState == BluetoothDevice.BOND_BONDED) { "所选车机的系统蓝牙配对已失效" }
        Diagnostics.event("INFO","主动 RFCOMM：secure=true，车机服务 UUID=$ACCESSORY_UUID，超时=${OUTBOUND_TIMEOUT_MS}ms",id)
        // Creation belongs to the same IOException fallback as SDP lookup and connect.
        val outbound = own(peer.createRfcommSocketToServiceRecord(ACCESSORY_UUID))
        val attempt = own(RfcommConnectAttempt { outbound.close() })
        val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"carplay-rfcomm-deadline").apply { isDaemon = true } }
        try {
            attempt.connect(outbound::connect) { timeout ->
                val timer = scheduler.schedule({ timeout() },OUTBOUND_TIMEOUT_MS,TimeUnit.MILLISECONDS)
                AutoCloseable { timer.cancel(false) }
            }
            check(!closed.get()) { "连接已停止" }
            if (!outbound.isConnected) {
                outbound.close()
                throw java.io.IOException("主动 RFCOMM 返回后未建立连接")
            }
            Diagnostics.event("INFO","主动 RFCOMM 已连接到所选配对车机",id)
            return outbound
        } finally { scheduler.shutdownNow() }
    }

    private fun registerPhoneService(bluetooth: BluetoothAdapter): BluetoothServerSocket {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "蓝牙连接权限已撤销" }
        }
        val registration = phoneService ?: own(RegisteredRfcommService {
            bluetooth.listenUsingRfcommWithServiceRecord("CarPlay Android",PHONE_UUID).also {
                try { Diagnostics.event("INFO","手机 SDP 服务已实际注册：UUID=$PHONE_UUID；手机主动连接时仍可接受车机回连",id) }
                catch (_: Exception) { /* Diagnostics do not own the server socket. */ }
            }
        }).also { phoneService = it }
        return registration.acquire().also { phoneListener = it }
    }

    private fun listenInbound(bluetooth: BluetoothAdapter, keepRegistered: Boolean = false): BluetoothSocket {
        if (Build.VERSION.SDK_INT >= 31) {
            check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "蓝牙连接权限已撤销" }
        }
        stage(Stage.BLUETOOTH,"等待已选择车机连接手机的 iAP2 服务")
        val listener = if (keepRegistered) registerPhoneService(bluetooth)
            else own(bluetooth.listenUsingRfcommWithServiceRecord("CarPlay Android", PHONE_UUID))
        Diagnostics.event("INFO","手机 iAP2 服务等待所选配对车机，最多45秒；请在车机选择此手机",id)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        try {
            while (!closed.get() && System.nanoTime() < deadline) {
                try {
                    val accepted = own(listener.accept(1000))
                    if (accepted.remoteDevice.address.equals(selectedPeer, true) && accepted.remoteDevice.bondState == BluetoothDevice.BOND_BONDED) {
                        Diagnostics.event("INFO","手机监听已接受所选配对车机的 RFCOMM 连接",id)
                        return accepted
                    }
                    accepted.close()
                    if (keepRegistered) resources.remove(accepted)
                    Diagnostics.event("WARN","忽略非选定或未配对车机的蓝牙连接",id)
                } catch (error: java.io.IOException) { if (closed.get()) throw error }
            }
        } finally { if (!keepRegistered) listener.close() }
        error("45 秒内未收到所选车机的安全 RFCOMM 连接；请在车机选择此手机")
    }

    @android.annotation.TargetApi(29)
    private fun join(config: WirelessEndpoint, version: AccessoryVersionInfo?): Network {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val result = CompletableFuture<Network>()
        config.addresses.forEach(PeerAddresses::parse)
        val wifi = context.getSystemService(WifiManager::class.java)
        val authentication = WirelessSecurity.select(config.security,version,wifi.isWpa3SaeSupported)
        Diagnostics.event("INFO","网络接入方式=WLAN 热点；使用车机下发的原始 SSID 和密码，通过 Android Wi-Fi 网络请求连接",id)
        Diagnostics.event("INFO","车机无线安全类型=${config.security}，手机请求=$authentication；使用已认证的热点声明",id)
        val spec = WifiNetworkSpecifier.Builder().setSsid(config.ssid).apply {
            when (authentication) {
                WifiAuthentication.OPEN -> Unit
                WifiAuthentication.WPA2 -> setWpa2Passphrase(config.password)
                WifiAuthentication.WPA3 -> setWpa3Passphrase(config.password)
            }
        }.build()
        var selectedNetwork: Network? = null
        fun onAddresses(network: Network, properties: LinkProperties?) {
            if (closed.get() || properties == null) return
            val locals = properties.linkAddresses.map { it.address }
            val ready = if (config.addresses.isEmpty()) locals.any { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                else PeerAddresses.resolve(config.addresses,locals,interfaceIndex(properties)).isNotEmpty()
            if (ready) synchronized(result) {
                if (!result.isDone) {
                    selectedNetwork = network
                    Diagnostics.event("INFO","WLAN 实际已连接：interface=${properties.interfaceName}，ifIndex=${interfaceIndex(properties)}，IPv4=${locals.any { it is Inet4Address }}，IPv6=${locals.any { it is Inet6Address }}；车机 TCP/UDP 绑定此网络",id)
                    result.complete(network)
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAddresses(network,connectivity.getLinkProperties(network))
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                onAddresses(network,properties)
            }
            override fun onUnavailable() { result.completeExceptionally(IllegalStateException("无线网络请求被拒绝或超时")) }
            override fun onLost(network: Network) {
                if (!closed.get() && synchronized(result) { selectedNetwork == network }) {
                    errors.offer(IllegalStateException("车机无线网络断开")); close()
                }
            }
        }
        own(AutoCloseable { runCatching { connectivity.unregisterNetworkCallback(callback) }; result.cancel(true) })
        val cellularRequest = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Diagnostics.event("INFO", "移动蜂窝数据通道保持活跃，手机对外网络正常", id)
            }
        }
        runCatching {
            connectivity.requestNetwork(cellularRequest, cellularCallback)
            own(AutoCloseable { runCatching { connectivity.unregisterNetworkCallback(cellularCallback) } })
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        connectivity.requestNetwork(request,callback,45000)
        return result.get(50,TimeUnit.SECONDS)
    }

    private fun interfaceIndex(link: LinkProperties): Int =
        link.interfaceName?.let { NetworkInterface.getByName(it)?.index } ?: 0

    private fun discover(network: Network, config: WirelessEndpoint, transport: String): ReceiverRoute {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val link = checkNotNull(connectivity.getLinkProperties(network)) { "无线网络地址尚未就绪" }
        val candidates = link.linkAddresses.map { it.address }.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
        val direct = PeerAddresses.resolve(config.addresses,candidates,interfaceIndex(link))
        val address = direct.firstOrNull()?.local ?: candidates.firstOrNull { it is Inet4Address } ?: candidates.firstOrNull() ?: error("车机网络未分配有效地址")
        val controlListener = own(ServerSocket()).apply { bind(InetSocketAddress(address,0)); soTimeout = 1000 }
        val multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("carplay-mdns").apply { setReferenceCounted(false); acquire() }
        own(AutoCloseable { if (multicast.isHeld) multicast.release() })
        val found = CompletableFuture<ReceiverRoute>()
        val dns = try {
            own(JmDNS.create(address,"CarPlay Android")).also {
                it.registerService(ServiceInfo.create("_carplay-ctrl._tcp.local.","CarPlay Android",controlListener.localPort,0,0,mapOf("id" to transport,"srcvers" to "320.17")))
            }
        } catch (failure: java.io.IOException) {
            if (direct.isEmpty() || config.port == null) throw failure
            Diagnostics.event("WARN","手机 Bonjour 发布失败：${failure.javaClass.simpleName}；使用已认证 iAP2 端点直连",id)
            null
        }
        dns?.addServiceListener("_airplay._tcp.local.",object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) { dns.requestServiceInfo(event.type,event.name,true) }
            override fun serviceRemoved(event: ServiceEvent) {}
            override fun serviceResolved(event: ServiceEvent) {
                if (closed.get()) return
                val info = event.info
                if (info.port !in 1..65535) return
                val targets = PeerAddresses.resolve(info.inetAddresses.mapNotNull { it.hostAddress },candidates,interfaceIndex(link))
                    .filter { config.addresses.isEmpty() || PeerAddresses.matches(config.addresses,it.remote) }
                if (targets.isEmpty()) return
                val receiverId = config.receiverId
                if (receiverId != null && info.getPropertyString("deviceid")?.replace(":","")?.equals(receiverId.replace(":",""),true) != true) return
                found.complete(ReceiverRoute(targets.map { it.remote },config.port ?: info.port,info.getPropertyString("pk")))
            }
        })
        val invitation = Thread({
            try {
                while (!closed.get()) {
                    val socket = try { controlListener.accept() } catch (_: SocketTimeoutException) { continue }
                    try {
                        own(socket).use {
                            it.soTimeout = 5000
                            if (config.addresses.isNotEmpty() && !PeerAddresses.matches(config.addresses,it.inetAddress)) {
                                Diagnostics.event("WARN","忽略非已认证车机地址的控制邀请",id)
                            } else {
                                val wire = RtspWire(it)
                                val request = wire.next()
                                val accepted = request.firstLine.startsWith("GET /ctrl-int/1/connect ")
                                wire.respond(request,null,if (accepted) 200 else 400)
                                if (accepted) Diagnostics.event("INFO","收到车机控制连接邀请",id)
                            }
                        }
                    } catch (failure: java.io.IOException) {
                        if (!closed.get()) Diagnostics.event("WARN","控制邀请连接未完成：${failure.javaClass.simpleName}",id)
                    } finally {
                        resources.remove(socket)
                    }
                }
            } catch (error: Exception) { if (!closed.get()) errors.offer(error) }
        },"carplay-connect-invitation").apply { isDaemon = true; start() }
        own(AutoCloseable { invitation.interrupt(); found.cancel(true) })
        if (direct.isNotEmpty() && config.port != null) {
            Diagnostics.event("INFO","使用 0x4301 提供的 IP/端口直连，地址数量=${direct.size}，IPv6=${direct.first().remote is Inet6Address}；无需等待 Bonjour 发现结果",id)
            return ReceiverRoute(direct.map { it.remote },checkNotNull(config.port),config.publicKey)
        }
        return found.get(30,TimeUnit.SECONDS)
    }

    private fun dispatch(command: Map<*, *>): Boolean {
        try {
            if (command["type"] == "keySamples") {
                val report = command["report"] as? HidKeyReport ?: return false
                fun code(usage: HidUsage): Int? = when (usage.page to usage.usage) {
                    9 to 1 -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    0x0c to 0x223 -> android.view.KeyEvent.KEYCODE_HOME
                    0x0c to 0x224 -> android.view.KeyEvent.KEYCODE_BACK
                    0x0c to 0xb0 -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    0x0c to 0xb1 -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    0x0c to 0xcd -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    0x0c to 0xb5 -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    0x0c to 0xb6 -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> null
                }
                if ((report.held + report.changes.map { it.usage }).any { code(it) == null }) return false
                report.changes.forEach { adapter.onKeyEvent(checkNotNull(code(it.usage)),it.down); Diagnostics.input(id) }
                repeat(kotlin.math.abs(report.wheel)) {
                    val key = if (report.wheel < 0) android.view.KeyEvent.KEYCODE_DPAD_UP else android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    adapter.onKeyEvent(key,true); adapter.onKeyEvent(key,false); Diagnostics.input(id)
                }
            } else if (command["type"] == "requestSiri") {
                val action = ((command["params"] as? Map<*, *>)?.get("siriAction") as? Number)?.toInt()
                if (action !in setOf(2,3)) return false
                adapter.onKeyEvent(android.view.KeyEvent.KEYCODE_VOICE_ASSIST,action == 2); Diagnostics.input(id)
            } else if (command["type"] == "touchSamples") {
                val display = checkNotNull(negotiatedDisplay)
                (command["samples"] as? List<*>)?.filterIsInstance<TouchSample>()?.forEach { touch ->
                    val previous = touch.id in contacts
                    if (touch.down || previous) {
                        val action = if (!touch.down) 1 else if (previous) 2 else 0
                        adapter.onTouchEvent(action,touch.x*display.width,touch.y*display.height,touch.id)
                        if (touch.down) contacts += touch.id else contacts -= touch.id
                        Diagnostics.input(id)
                    }
                }
            } else if (command["type"] == "hidSendReport") {
                val report = command["hidReport"] as? ByteArray ?: return false
                val uuid = command["uuid"]?.toString()
                if (uuid == "2a2a2a2a") {
                    require(report.size == 12)
                    for (offset in listOf(0,6)) {
                        val x = ((report[offset+2].toInt() and 255) or ((report[offset+3].toInt() and 255) shl 8)).toFloat()
                        val y = ((report[offset+4].toInt() and 255) or ((report[offset+5].toInt() and 255) shl 8)).toFloat()
                        adapter.onTouchEvent(if (report[offset+1].toInt() and 1 != 0) 2 else 1,x,y,report[offset].toInt() and 255)
                    }
                    Diagnostics.input(id)
                } else { Diagnostics.event("INFO","当前输入未支持此报告：类型=$uuid，长度=${report.size}",id); return false }
            } else { Diagnostics.event("INFO","当前投屏来源未支持车机操作：${command["type"]}",id); return false }
            return true
        } catch (error: Exception) { Diagnostics.event("WARN","车机操作未执行：${error.message}",id); return false }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        resources.reversed().forEach { runCatching { it.close() } }; resources.clear()
        receiverThread?.interrupt()
        runBlocking { adapter.stopProjection() }
    }
    companion object {
        val PHONE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val ACCESSORY_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        private const val OUTBOUND_TIMEOUT_MS = 20000L
    }
}
