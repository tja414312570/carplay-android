package com.carplay.sender

import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

class BondedAccessoryTrustTest {
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun certificate(key: KeyPair, expired: Boolean = false): X509Certificate {
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setIssuer(X500Name("CN=Isolated test accessory")); setSubject(X500Name("CN=Isolated test accessory"))
            setStartDate(Time(Date(System.currentTimeMillis()-86400000*2)))
            setEndDate(Time(Date(System.currentTimeMillis() + if (expired) -86400000 else 86400000)))
            setSignature(algorithm); setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
        }.generateTBSCertificate()
        val signed = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(tbs.encoded); sign() }
        return CertificateFactory.getInstance("X.509").generateCertificate(
            DERSequence(arrayOf(tbs,algorithm,DERBitString(signed))).encoded.inputStream()) as X509Certificate
    }
    private fun sign(key: KeyPair, challenge: ByteArray) = Signature.getInstance("NONEwithECDSA").run {
        initSign(key.private); update(challenge); sign()
    }
    @Test fun firstBindingRequiresAuthenticatedBluetoothAndSuccessfulProof() {
        val key = key(); val certificate = certificate(key); val challenge = ByteArray(32) { it.toByte() }
        var stored: ByteArray? = null
        val unpaired = BondedAccessoryTrust(null,false) { fail("An unpaired peer must never become trusted") }
        assertThrows(IllegalStateException::class.java) { unpaired.validate(certificate.encoded) }
        val trust = BondedAccessoryTrust(null,true) { stored = it }
        val candidate = trust.validate(certificate.encoded)
        assertNull(stored)
        assertFalse(trust.verifyAuthentication(candidate,challenge,sign(key,ByteArray(32) { 42 })))
        assertNull(stored)
        assertTrue(trust.verifyAuthentication(candidate,challenge,sign(key,challenge)))
        assertArrayEquals(key.public.encoded,stored)
    }
    @Test fun establishedBindingWorksAcrossWifiAndRejectsChangedKeyOrExpiredCertificate() {
        val key = key(); val cert = certificate(key)
        val trust = BondedAccessoryTrust(key.public.encoded,false) { fail("Existing identity must not be overwritten") }
        val candidate = trust.validate(cert.encoded); val nonce = ByteArray(32) { 8 }
        assertTrue(trust.verifyAuthentication(candidate,nonce,sign(key,nonce)))
        assertThrows(IllegalStateException::class.java) { trust.validate(certificate(key()).encoded) }
        assertThrows(CertificateExpiredException::class.java) { trust.validate(certificate(key,true).encoded) }
    }
    @Test fun invalidProofCannotPromoteCertificateToTrustAnchor() {
        val key = key(); val cert = certificate(key)
        val trust = BondedAccessoryTrust(null,true) { fail("Invalid proof must never persist") }
        trust.validate(cert.encoded)
        assertFalse(trust.verifyAuthentication(cert,ByteArray(32),sign(key(),ByteArray(32))))
        assertThrows(IllegalStateException::class.java) { AccessoryTrust(emptyList()).validate(cert.encoded) }
    }
}
