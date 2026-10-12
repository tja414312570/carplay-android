package com.carplay.sender

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/** A key bound to a user-selected, authenticated Bluetooth peer; never an accept-all policy. */
class BondedAccessoryTrust(
    pinnedKey: ByteArray?,
    private val authenticatedBluetooth: Boolean,
    private val saveKey: (ByteArray) -> Unit,
) : AccessoryAuthentication {
    private var pinned = pinnedKey?.copyOf()
    private var candidate: ByteArray? = null

    @Synchronized override fun validate(encoded: ByteArray): X509Certificate {
        require(encoded.size in 1..65_000)
        check(pinned != null || authenticatedBluetooth) { "首次绑定认证身份必须通过已配对车机的安全蓝牙连接" }
        val certificates = CertificateFactory.getInstance("X.509").generateCertificates(encoded.inputStream()).map { it as X509Certificate }
        val leaf = certificates.singleOrNull { it.basicConstraints < 0 } ?: error("车机未提供唯一的认证设备证书")
        leaf.checkValidity()
        when (val key = leaf.publicKey) {
            is ECPublicKey -> require(key.params.order.bitLength() == 256)
            is RSAPublicKey -> require(key.modulus.bitLength() >= 1024)
            else -> error("不支持的车机认证密钥")
        }
        val key = leaf.publicKey.encoded
        pinned?.let { check(AccessoryTrust.equal(it,key)) { "车机认证公钥已变化，已停止连接；请确认车机身份" } }
        candidate = key.copyOf()
        return leaf
    }

    @Synchronized override fun verifyAuthentication(certificate: X509Certificate, challenge: ByteArray, response: ByteArray): Boolean {
        certificate.checkValidity()
        val key = certificate.publicKey.encoded
        check(candidate?.let { AccessoryTrust.equal(it,key) } == true) { "认证响应不属于当前车机证书" }
        pinned?.let { check(AccessoryTrust.equal(it,key)) { "车机认证身份不一致" } }
        if (!AccessoryTrust.verifyChallenge(certificate,challenge,response)) return false
        if (pinned == null) {
            check(authenticatedBluetooth) { "无线通道不能自行绑定首次认证身份" }
            saveKey(key.copyOf()); pinned = key.copyOf()
        }
        return true
    }
}
