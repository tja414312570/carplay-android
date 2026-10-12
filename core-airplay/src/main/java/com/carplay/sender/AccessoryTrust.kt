package com.carplay.sender

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence

/** Explicit trust anchors; no development or accept-any-certificate mode. */
class AccessoryTrust(roots: Collection<X509Certificate>) : AccessoryAuthentication {
    private val anchors = roots.map { TrustAnchor(it, null) }.toSet()

    override fun validate(encoded: ByteArray): X509Certificate {
        require(encoded.size in 1..65_000)
        check(anchors.isNotEmpty()) { "请先导入合法来源的车机认证根证书" }
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = factory.generateCertificates(ByteArrayInputStream(encoded)).map { it as X509Certificate }
        val leaf = certificates.singleOrNull { it.basicConstraints < 0 } ?: error("车机证书链不包含唯一设备证书")
        certificates.forEach { it.checkValidity() }
        val selector = X509CertSelector().apply { certificate = leaf }
        val parameters = PKIXBuilderParameters(anchors, selector).apply {
            isRevocationEnabled = false // Offline accessory certificates; validity and chain are still mandatory.
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(certificates)))
        }
        CertPathBuilder.getInstance("PKIX").build(parameters)
        return leaf
    }

    companion object {
        fun verifyChallenge(certificate: X509Certificate, challenge: ByteArray, response: ByteArray): Boolean {
            val key = certificate.publicKey
            val signature = when (key) {
                is ECPublicKey -> {
                    require(key.params.order.bitLength() == 256 && challenge.size == 32)
                    Signature.getInstance("NONEwithECDSA")
                }
                is RSAPublicKey -> { require(challenge.size in setOf(20, 32)); Signature.getInstance("NONEwithRSA") }
                else -> error("不支持的车机认证密钥")
            }
            signature.initVerify(key)
            val digestInfo = if (key is RSAPublicKey) {
                val hex = if (challenge.size == 32) "3031300d060960864801650304020105000420" else "3021300906052b0e03021a05000414"
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() + challenge
            } else challenge
            signature.update(digestInfo)
            val der = if (key is ECPublicKey && response.size == 64) DERSequence(arrayOf(
                ASN1Integer(BigInteger(1, response.copyOfRange(0, 32))), ASN1Integer(BigInteger(1, response.copyOfRange(32, 64)))
            )).encoded else response
            return signature.verify(der)
        }
        fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
    }
}

interface AccessoryAuthentication {
    fun validate(encoded: ByteArray): X509Certificate
    fun verifyAuthentication(certificate: X509Certificate, challenge: ByteArray, response: ByteArray): Boolean =
        AccessoryTrust.verifyChallenge(certificate, challenge, response)
}
