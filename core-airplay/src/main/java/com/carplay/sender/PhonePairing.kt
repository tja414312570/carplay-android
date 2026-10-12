package com.carplay.sender

import com.carplay.airplay.*
import java.math.BigInteger
import java.security.SecureRandom

data class ControllerIdentity(val privateKey: ByteArray, val publicKey: ByteArray, val id: String)
data class PairedAccessory(val id: String, val publicKey: ByteArray)

class PhonePairing(
    private val identity: ControllerIdentity,
    private val exchange: (String, ByteArray) -> ByteArray,
    private val save: (PairedAccessory) -> Unit,
) {
    private fun hkdf(key: ByteArray, salt: String, info: String) = AirPlayCrypto.hkdfSha512(key, salt.toByteArray(), info.toByteArray())
    private fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
    private fun request(path: String, expected: Int, vararg fields: Pair<Int, ByteArray>): Map<Int, ByteArray> {
        val reply = Tlv8Codec.decode(exchange(path, tlv(*fields)))
        check(reply[7] == null) { "车机拒绝配对：${reply[7]?.firstOrNull()}" }
        check(reply[6]?.contentEquals(byteArrayOf(expected.toByte())) == true) { "配对阶段不匹配" }
        return reply
    }
    fun setup(pin: String, advertisedKey: ByteArray?, expectedIdentity: String? = null): PairedAccessory {
        require(pin.isNotBlank())
        val second = request("/pair-setup", 2, 6 to byteArrayOf(1), 0 to byteArrayOf(0))
        val client = SrpController(second.getValue(2), second.getValue(3), pin)
        val fourth = request("/pair-setup", 4, 6 to byteArrayOf(3), 3 to client.publicKey, 4 to client.proof)
        check(AccessoryTrust.equal(fourth.getValue(4), client.serverProof)) { "SRP 服务端证明无效" }
        val key = hkdf(client.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val id = identity.id.toByteArray()
        val signKey = hkdf(client.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sign = AirPlayCrypto.ed25519Sign(identity.privateKey, signKey + id + identity.publicKey)
        val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PS-Msg05"), tlv(1 to id, 3 to identity.publicKey, 10 to sign))
        val sixth = request("/pair-setup", 6, 6 to byteArrayOf(5), 5 to encrypted)
        val result = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PS-Msg06"), sixth.getValue(5)))
        val peerKey = result.getValue(3); val peerId = result.getValue(1)
        require(peerKey.size == 32)
        check(advertisedKey == null || AccessoryTrust.equal(peerKey, advertisedKey)) { "配对公钥与已认证车机不一致" }
        val peerIdStr = String(peerId)
        check(expectedIdentity == null || peerIdStr.equals(expectedIdentity, ignoreCase = true)) { "配对标识与车机声明不一致" }
        val accessorySign = hkdf(client.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        check(AirPlayCrypto.ed25519Verify(peerKey, accessorySign + peerId + peerKey, result.getValue(10))) { "车机配对签名无效" }
        return PairedAccessory(peerIdStr, peerKey).also(save)
    }
    fun verify(peer: PairedAccessory): ByteArray {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val second = request("/pair-verify", 2, 6 to byteArrayOf(1), 3 to ephemeral.publicKey)
        val server = second.getValue(3); require(server.size == 32)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, server)
        val key = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val result = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PV-Msg02"), second.getValue(5)))
        val peerId = String(result.getValue(1))
        check(peerId.equals(peer.id, ignoreCase = true)) { "车机配对身份发生变化" }
        check(AirPlayCrypto.ed25519Verify(peer.publicKey, server + result.getValue(1) + ephemeral.publicKey, result.getValue(10))) { "车机验证签名无效" }
        val id = identity.id.toByteArray()
        val sign = AirPlayCrypto.ed25519Sign(identity.privateKey, ephemeral.publicKey + id + server)
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to sign))
        request("/pair-verify", 4, 6 to byteArrayOf(3), 5 to sealed)
        return shared
    }
}

/** Client half of the same SRP-6a group used by the preserved receiver. */
internal class SrpController(salt: ByteArray, serverPublic: ByteArray, pin: String) {
    private val n = Srp6a.modulus()
    private val g = Srp6a.generator()
    private val a = BigInteger(1, ByteArray(32).also(SecureRandom()::nextBytes))
    private val public = g.modPow(a, n)
    val publicKey: ByteArray = Srp6a.pad(public)
    val key: ByteArray
    val proof: ByteArray
    val serverProof: ByteArray
    init {
        val b = BigInteger(1, serverPublic); require(b.mod(n) != BigInteger.ZERO)
        val u = BigInteger(1, AirPlayCrypto.sha512(publicKey, Srp6a.pad(b))); require(u != BigInteger.ZERO)
        val x = BigInteger(1, AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512("Pair-Setup:$pin".toByteArray())))
        val k = BigInteger(1, AirPlayCrypto.sha512(Srp6a.toBytes(n), Srp6a.pad(g)))
        val secret = (b - k * g.modPow(x, n)).mod(n).modPow(a + u * x, n)
        key = AirPlayCrypto.sha512(Srp6a.toBytes(secret))
        val hashN = AirPlayCrypto.sha512(Srp6a.toBytes(n)); val hashG = AirPlayCrypto.sha512(Srp6a.toBytes(g))
        val xor = ByteArray(hashN.size) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
        proof = AirPlayCrypto.sha512(xor, AirPlayCrypto.sha512("Pair-Setup".toByteArray()), salt, publicKey, Srp6a.pad(b), key)
        serverProof = AirPlayCrypto.sha512(publicKey, proof, key)
    }
}
