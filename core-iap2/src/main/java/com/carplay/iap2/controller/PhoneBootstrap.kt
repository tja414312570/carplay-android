package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.Iap2CarPlayMessages
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.wire.Iap2Frame
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey

data class WirelessEndpoint(
    val ssid: String, val password: String, val security: Int, val channel: Int,
    val addresses: List<String> = emptyList(), val port: Int? = null,
    val receiverId: String? = null, val publicKey: String? = null,
    val sourceVersion: String? = null, val carPlaySdkVersion: String? = null,
) {
    init {
        require(ssid.toByteArray().size in 0..32) { "SSID 长度无效" }
        require(security in setOf(0, 2, 3, 4)) { "不支持的无线安全类型：$security" }
        require(security == 0 || password.length in 8..63 || password.matches(Regex("[a-fA-F0-9]{64}")))
        require(channel in 0..255)
        require(port == null || port in 1..65535)
    }

    fun merge(other: WirelessEndpoint): WirelessEndpoint {
        require(ssid == other.ssid && password == other.password && security == other.security) {
            "同一连接的无线凭据发生变化，请重新连接"
        }
        return other.copy(addresses = other.addresses.ifEmpty { addresses }, port = other.port ?: port,
            receiverId = other.receiverId ?: receiverId, publicKey = other.publicKey ?: publicKey,
            sourceVersion = other.sourceVersion ?: sourceVersion, carPlaySdkVersion = other.carPlaySdkVersion ?: carPlaySdkVersion)
    }
}

enum class BootstrapPhase { IDENTIFY, CERTIFICATE, CHALLENGE, WIRELESS_CONFIGURATION, READY, CONTROL_READY }

enum class PhoneBootstrapPurpose { WIRELESS_CONFIGURATION, CONTROL_SESSION }

enum class PhoneBootstrapProfile {
    GENERIC, VIVO_REFERENCE, VIVO_TRANSPORT_STRING, VIVO_WIRELESS_READY, VIVO_FLAT_REFERENCE,
    VIVO_FLAT_WIFI_REQUEST, VIVO_WIRELESS_MINIMAL, MODERN_WIRELESS;

    val usesFlatAvailability: Boolean
        get() = this == VIVO_FLAT_REFERENCE || this == VIVO_FLAT_WIFI_REQUEST

    val usesPhoneTransportString: Boolean
        get() = this == VIVO_TRANSPORT_STRING || this == VIVO_WIRELESS_READY || this == MODERN_WIRELESS || usesFlatAvailability
}

data class BootstrapStatus(val identified: Boolean, val authenticated: Boolean, val certificateReceived: Boolean,
    val challengePending: Boolean, val endpointReceived: Boolean, val networkRequestQueued: Boolean = false,
    val modernWirelessSupported: Boolean = false, val controlReady: Boolean = false) {
    val phase get() = when {
        endpointReceived -> BootstrapPhase.READY
        controlReady -> BootstrapPhase.CONTROL_READY
        authenticated -> BootstrapPhase.WIRELESS_CONFIGURATION
        challengePending -> BootstrapPhase.CHALLENGE
        identified -> BootstrapPhase.CERTIFICATE
        else -> BootstrapPhase.IDENTIFY
    }
    fun timeoutDetail() = when (phase) {
        BootstrapPhase.IDENTIFY -> "等待车机识别信息超时：未收到 0x1d01"
        BootstrapPhase.CERTIFICATE -> "车机识别已完成，等待认证证书超时：未收到 0xaa01"
        BootstrapPhase.CHALLENGE -> "认证挑战已发送，等待车机签名响应超时：未收到 0xaa03"
        BootstrapPhase.WIRELESS_CONFIGURATION -> "车机签名验证通过，等待无线配置超时"
        BootstrapPhase.READY -> "无线配置已收到，但连接请求未完成"
        BootstrapPhase.CONTROL_READY -> "Wi-Fi iAP2 已完成独立识别和认证"
    }
}

/** The controller is the only consumer of its CSM session. Trust validation is supplied by the owner. */
class PhoneBootstrap(
    private val identifier: String,
    private val validateCertificates: (ByteArray) -> X509Certificate,
    private val verifyChallenge: (X509Certificate, ByteArray, ByteArray) -> Boolean,
    private val send: (Iap2Frame) -> Unit,
    private val onStage: (String) -> Unit,
    private val onEndpoint: (WirelessEndpoint) -> Unit,
    private val onOther: (Iap2Frame) -> Unit = {},
    private val onStatus: (BootstrapStatus) -> Unit = {},
    private val profile: PhoneBootstrapProfile = PhoneBootstrapProfile.GENERIC,
    private val transportIdentifier: () -> String = { identifier },
    expectedAccessoryIdentity: ByteArray? = null,
    private val purpose: PhoneBootstrapPurpose = PhoneBootstrapPurpose.WIRELESS_CONFIGURATION,
) {
    private val expectedAccessoryIdentity = expectedAccessoryIdentity?.copyOf()
    private var identified = false
    private var authenticated = false
    private var published = false
    private var networkRequestQueued = false
    private var controlReady = false
    private var versionInfo: AccessoryVersionInfo? = null
    private var leaf: X509Certificate? = null
    private var challenge: ByteArray? = null
    private var endpoint: WirelessEndpoint? = null
    private var supportsStart = false
    private var accessoryReceives: Set<Int> = emptySet()
    private var accessorySends: Set<Int> = emptySet()
    private var identification: Iap2Frame? = null
    private var lastReportedStatus: BootstrapStatus? = null

    fun begin() { onStage("识别车机"); send(empty(0x1d00)); reportStatus() }

    @Synchronized fun status() = BootstrapStatus(identified, authenticated, leaf != null, challenge != null, endpoint != null,
        networkRequestQueued, supportsStart, controlReady)
    @Synchronized fun accessoryVersionInfo(): AccessoryVersionInfo? = versionInfo
    @Synchronized fun accessoryIdentityFingerprint(): ByteArray? = identification?.let {
        identityFingerprint(Iap2BodyReader.of(it))
    }

    private fun reportStatus() {
        val current = status()
        if (current == lastReportedStatus) return
        lastReportedStatus = current
        try { onStatus(current) } catch (_: Exception) { /* UI observations cannot modify authentication. */ }
    }

    @Synchronized fun receive(frame: Iap2Frame) {
        val body = Iap2BodyReader.of(frame)
        when (frame.messageId) {
            0x1d01 -> {
                check(!identified) { "收到重复识别信息" }
                body.string(0); body.string(1); body.string(2)
                expectedAccessoryIdentity?.let { expected ->
                    check(MessageDigest.isEqual(expected, identityFingerprint(body))) { "重新协商时车机身份信息已变化" }
                }
                val sends = body.u16List(6)
                val receives = body.u16List(7)
                accessoryReceives = receives.toSet()
                accessorySends = sends.toSet()
                identification = frame
                versionInfo = AccessoryVersionInfo.fromIdentification(body)
                supportsStart = sends.contains(0x4301) && accessoryReceives.contains(0x4300)
                if (profile != PhoneBootstrapProfile.GENERIC) {
                    fun ids(values: List<Int>) = values.take(24).joinToString(",") { "0x${it.toString(16)}" } +
                        if (values.size > 24) ",… total=${values.size}" else ""
                    diagnostic("车机消息能力：发送=${ids(sends)}；接收=${ids(receives)}；modern4300=$supportsStart")
                    diagnostic("车机识别结构：${IdentificationDiagnostics.describe(body)}")
                    diagnostic("车机版本信息：${versionInfo!!.diagnostic()}；固件及 iAP2 会话版本不等于 CarPlay SDK 版本，SDK 等待 0x4301 声明")
                }
                identified = true
                send(empty(0x1d02)); send(empty(0xaa00)); onStage("验证车机身份")
            }
            0x1d03, 0xaa04 -> error("车机拒绝识别或认证")
            0xaa01 -> {
                check(identified && leaf == null) { "认证证书时序错误" }
                val trusted = validateCertificates(body.bytes(0))
                leaf = trusted
                challenge = ByteArray(if (trusted.publicKey is ECPublicKey) 32 else 20).also(SecureRandom()::nextBytes)
                send(Iap2Messages.buildRaw(0xaa02) { bytes(0, challenge!!) })
            }
            0xaa03 -> {
                check(!authenticated) { "重复认证响应" }
                val certificate = checkNotNull(leaf) { "未请求的认证响应" }
                val nonce = checkNotNull(challenge) { "认证挑战不存在" }
                if (!verifyChallenge(certificate, nonce, body.bytes(0))) {
                    send(empty(0xaa04)); error("车机认证签名无效")
                }
                authenticated = true; challenge = null; send(empty(0xaa05)); publish()
            }
            0x4301, 0x5703 -> {
                check(identified && authenticated) { "认证完成前收到网络配置" }
                val incoming = if (frame.messageId == 0x5703) {
                    WirelessEndpoint(body.string(1), body.string(2), body.u8(3), body.u8(4))
                } else {
                    check(body.optionalU8(8) != 1) { "车机要求的双向认证尚未配置" }
                    val wireless = body.group(1)
                    WirelessEndpoint(wireless.string(0), wireless.string(1), wireless.u8(4), wireless.u8(2),
                        wireless.list().filter { it.id == 3 }.map { com.carplay.iap2.wire.Iap2WireCodec.readString(it.payload) },
                        body.u32(2).also { require(it in 1..65535) }.toInt(), body.optionalString(3), body.optionalString(4),
                        AccessoryVersionInfo.optionalVersionString(body, 5), AccessoryVersionInfo.optionalVersionString(body, 6))
                }
                if (frame.messageId == 0x4301) diagnostic("车机协议版本声明：sourceVersion=${AccessoryVersionInfo.safe(incoming.sourceVersion)} " +
                    "carPlaySDK=${AccessoryVersionInfo.safe(incoming.carPlaySdkVersion)}")
                val merged = endpoint?.merge(incoming) ?: incoming
                if (merged != endpoint) { endpoint = merged; onStage("无线配置已接收"); onEndpoint(merged) }
            }
            0x5700 -> send(Iap2Messages.buildRaw(0x5701) { u8(0, 2) })
            else -> onOther(frame)
        }
        reportStatus()
    }

    private fun publish() {
        if (published) return
        if (purpose == PhoneBootstrapPurpose.CONTROL_SESSION) {
            published = true
            publishDeviceNotifications()
            controlReady = true
            onStage("Wi-Fi iAP2 独立识别和签名验证完成")
            return
        }
        val wirelessComponent = if (profile == PhoneBootstrapProfile.VIVO_REFERENCE && supportsStart) {
            AccessoryWirelessComponents.select(Iap2BodyReader.of(checkNotNull(identification) { "车机识别信息不存在" }))
        } else null
        val publishReferenceReadiness = (profile == PhoneBootstrapProfile.VIVO_WIRELESS_READY ||
            profile.usesFlatAvailability) && supportsStart
        val publishTransport = publishReferenceReadiness || acceptsNotification(0x4e0e)
        val needsTransport = publishTransport || (profile.usesPhoneTransportString && supportsStart)
        val transport = if (needsTransport) transportIdentifier() else null
        if (profile != PhoneBootstrapProfile.GENERIC && needsTransport) {
            require(BluetoothTransportIdentity.normalizedAddress(transport) == transport) {
                "请在诊断设置填写手机自身的真实蓝牙地址"
            }
        }
        if (profile != PhoneBootstrapProfile.GENERIC && !supportsStart) {
            check(0x5702 in accessoryReceives && 0x5703 in accessorySends) {
                "车机未声明可用的无线配置请求，无法发起无线 CarPlay"
            }
        }
        if (publishReferenceReadiness) {
            diagnostic("vivo 无线就绪方案：0x4e0e 和 0x4e0d 在 0x4300 前按参考流程发送；车机声明接收：4e0e=${0x4e0e in accessoryReceives}，4e0d=${0x4e0d in accessoryReceives}；0x4300 保持手机地址字符串")
        }
        published = true
        publishDeviceNotifications()
        if (publishTransport) send(Iap2Messages.buildRaw(0x4e0e) { string(0, checkNotNull(transport)) })
        if (profile != PhoneBootstrapProfile.GENERIC) {
            if (publishReferenceReadiness || acceptsNotification(0x4e0d)) send(Iap2Messages.buildRaw(0x4e0d) { u8(0, 1) })
            if (supportsStart) {
                if (profile == PhoneBootstrapProfile.MODERN_WIRELESS && PhoneBluetoothRecovery.isObservedFirmware(versionInfo)) {
                    diagnostic("GM 完整无线声明：0x4300 同时包含 group(0) 有线传输标识与 group(1) 蓝牙传输标识，满足车机 IAPAuthenticator 属性 1/2 门控")
                }
                if (profile == PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL) {
                    val availability = Iap2CarPlayMessages.buildMinimalWirelessAvailability()
                    diagnostic("网络发布方案：0x4300，最小无线属性 group(1)/available=1，省略可选传输标识；帧=${availability.encodedFrame().size}B")
                    send(availability)
                } else if (profile.usesPhoneTransportString) {
                    val flat = profile.usesFlatAvailability
                    val includeWired = profile == PhoneBootstrapProfile.MODERN_WIRELESS
                    val availability = when {
                        flat -> Iap2CarPlayMessages.buildFlatWirelessAvailability(checkNotNull(transport))
                        includeWired -> Iap2CarPlayMessages.buildAvailability(
                            bluetoothTransportIdentifier = checkNotNull(transport),
                            wiredSupported = true,
                            usbTransportIdentifier = identifier,
                        )
                        else -> Iap2CarPlayMessages.buildAvailability(bluetoothTransportIdentifier = checkNotNull(transport))
                    }
                    val body = Iap2BodyReader.of(availability)
                    val transportBytes = (if (flat) body else body.group(1)).bytes(1).size
                    val shape = when {
                        flat -> "根参数=[0,1]，参数0=1，参数1"
                        includeWired -> "有线参数0/1=手机UUID+无线参数1/1"
                        else -> "无线参数1/1"
                    }
                    diagnostic("网络发布方案：0x4300，$shape=手机蓝牙地址字符串，长度=${transportBytes}B，帧=${availability.encodedFrame().size}B；车机组件编号与手机 UUID 不用于该字段")
                    send(availability)
                    if (profile == PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST) {
                        diagnostic("主动请求车机无线配置：按小米参考流程将 0x5702 排在 0x4300 后；车机声明接收5702=${0x5702 in accessoryReceives}，发送5703=${0x5703 in accessorySends}；是否获得配置以实际响应为准")
                        send(empty(0x5702))
                    }
                } else {
                    val component = checkNotNull(wirelessComponent)
                    diagnostic("网络发布方案：0x4300，车机无线组件=${component.id}，来源=0x1d01/24/0，supported=${component.supported} available=${component.available}")
                    send(Iap2CarPlayMessages.buildWirelessComponentAvailability(component.id))
                }
            } else send(empty(0x5702))
        } else {
            if (supportsStart) send(Iap2CarPlayMessages.buildAvailability(bluetoothTransportIdentifier = checkNotNull(transport)))
            send(empty(0x5702))
        }
        networkRequestQueued = true
        onStage("认证通过，等待无线配置")
    }

    private fun publishDeviceNotifications() {
        if (acceptsNotification(0x4e09)) send(Iap2Messages.buildRaw(0x4e09) { string(0, "CarPlay Android") })
        if (acceptsNotification(0x4e0a)) send(Iap2Messages.buildRaw(0x4e0a) { string(0, "zh") })
        if (acceptsNotification(0x4e0c)) send(Iap2Messages.buildRaw(0x4e0c) { string(0, identifier) })
    }

    private fun empty(id: Int) = Iap2Messages.buildRaw(id) {}

    private fun identityFingerprint(body: Iap2BodyReader): ByteArray = MessageDigest.getInstance("SHA-256").run {
        // Stable identity only; transport availability and message capabilities may renegotiate.
        body.list().filter { it.id in 0..5 || it.id == 34 }.sortedBy { it.id }.forEach {
            update((it.id ushr 8).toByte()); update(it.id.toByte())
            update((it.payload.size ushr 8).toByte()); update(it.payload.size.toByte()); update(it.payload)
        }
        digest()
    }

    private fun acceptsNotification(id: Int): Boolean {
        if (purpose == PhoneBootstrapPurpose.CONTROL_SESSION) return id in accessoryReceives
        if (profile == PhoneBootstrapProfile.GENERIC || id in accessoryReceives) return true
        diagnostic("车机未声明接收 0x${id.toString(16)}，跳过可选通知")
        return false
    }

    private fun diagnostic(message: String) {
        try { onStage(message) } catch (_: Exception) { /* Capability diagnostics are observational only. */ }
    }
}
