package com.carplay.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.nio.ByteBuffer

class DiagnosticContractTest {
    @Test fun sensitiveFieldsAndRawFrameHexAreRedacted() {
        val safe = LogRedaction.clean("password=secret123, token=abc123; address=AA:BB:CC:DD:EE:FF payload=0123456789abcdef0123456789abcdef")
        assertFalse(safe.contains("secret123")); assertFalse(safe.contains("abc123")); assertFalse(safe.contains("AA:BB")); assertFalse(safe.contains("0123456789"))
    }
    @Test fun uploadRequestAndRegistrationProofPreserveIssuerWireContract() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val request = UploadProof.request("request-1",1000,keys.public.encoded) { bytes -> Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(bytes); sign() } }
        val input = DataInputStream(request.inputStream())
        assertEquals(0x55435251,input.readInt()); assertEquals(1,input.readUnsignedShort())
        val unsigned = ByteArray(input.readInt()).also(input::readFully)
        val signature = ByteArray(input.readUnsignedShort()).also(input::readFully)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(keys.public); update(unsigned); verify(signature) })
        val proof = ByteBuffer.wrap(UploadProof.registration("device","install",request))
        val prefix = ByteArray(proof.int).also(proof::get)
        assertEquals("ICCOA-MANAGED-DEVICE-REGISTRATION-v1",String(prefix))
        assertTrue(request.contentEquals(request.copyOf()))
    }
}
