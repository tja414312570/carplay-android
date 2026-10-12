package com.carplay.app.diagnostics

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact UCRQ and holder-proof envelope used by the existing ICCOA log ingestion endpoint. */
object UploadProof {
    fun request(id: String, epochSeconds: Long, publicKey: ByteArray, sign: (ByteArray) -> ByteArray): ByteArray {
        require(epochSeconds > 0 && publicKey.size in 1..1024)
        val unsigned = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x55435250); out.writeShort(1)
            for (text in listOf(id, "iccoa-link")) {
                val field = text.toByteArray(); require(field.size in 1..64); out.writeShort(field.size); out.write(field)
            }
            out.writeLong(epochSeconds); out.writeShort(publicKey.size); out.write(publicKey)
        } }.toByteArray()
        val signature = sign(unsigned); require(signature.size in 1..512)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x55435251); out.writeShort(1); out.writeInt(unsigned.size); out.write(unsigned); out.writeShort(signature.size); out.write(signature)
        } }.toByteArray()
    }
    fun registration(device: String, installation: String, request: ByteArray): ByteArray {
        val prefix = "ICCOA-MANAGED-DEVICE-REGISTRATION-v1".toByteArray(Charsets.US_ASCII)
        val d = device.toByteArray(Charsets.US_ASCII); val i = installation.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(4 + prefix.size + 4 + d.size + 4 + i.size + 32).putInt(prefix.size).put(prefix)
            .putInt(d.size).put(d).putInt(i.size).put(i).put(MessageDigest.getInstance("SHA-256").digest(request)).array()
    }
}
