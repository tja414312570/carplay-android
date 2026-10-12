package com.carplay.sender

import com.carplay.airplay.AirPlayCrypto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LegacySecurity(val shared: ByteArray, val aesKey: ByteArray, val aesIv: ByteArray) {
    fun videoCipher(): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE,SecretKeySpec(aesKey,"AES"),IvParameterSpec(aesIv)) }
}

/** MFiSAP controller. Explicit peer trust and the signature over both ephemeral keys are mandatory. */
object MfiController {
    fun authenticate(exchange: (ByteArray) -> ByteArray, trust: AccessoryTrust): LegacySecurity =
        authenticate(exchange,trust as AccessoryAuthentication)

    fun authenticate(exchange: (ByteArray) -> ByteArray, trust: AccessoryAuthentication): LegacySecurity {
        val client = AirPlayCrypto.x25519Generate()
        val response = exchange(byteArrayOf(1) + client.publicKey)
        require(response.size in 40..100_000)
        val data = ByteBuffer.wrap(response)
        val server = ByteArray(32).also(data::get)
        fun field(maximum: Int): ByteArray {
            require(data.remaining() >= 4); val size = data.int
            require(size in 1..maximum && data.remaining() >= size)
            return ByteArray(size).also(data::get)
        }
        val certificate = trust.validate(field(65_000)); val sealed = field(1024)
        require(!data.hasRemaining()) { "不支持的扩展 MFiSAP 认证格式" }
        val shared = AirPlayCrypto.x25519Shared(client.privateKey,server)
        check(shared.any { it != 0.toByte() })
        fun hash(label: String) = MessageDigest.getInstance("SHA-1").digest(label.toByteArray() + shared).copyOf(16)
        val aesKey = hash("AES-KEY"); val aesIv = hash("AES-IV")
        val signature = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE,SecretKeySpec(aesKey,"AES"),IvParameterSpec(aesIv)); doFinal(sealed)
        }
        val algorithms = if (certificate.publicKey is ECPublicKey) listOf("SHA-256") else listOf("SHA-1","SHA-256")
        check(algorithms.any { algorithm ->
            val digest = MessageDigest.getInstance(algorithm).digest(server + client.publicKey)
            trust.verifyAuthentication(certificate,digest,signature)
        }) { "车机 MFiSAP 签名无效" }
        return LegacySecurity(shared,aesKey,aesIv)
    }
}
