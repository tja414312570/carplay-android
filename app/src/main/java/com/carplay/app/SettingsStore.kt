package com.carplay.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.carplay.airplay.AirPlayCrypto
import com.carplay.iap2.transport.PhoneBluetoothProfile
import com.carplay.iap2.transport.RfcommConnectionDirection
import com.carplay.iap2.controller.PhoneBootstrapProfile
import com.carplay.iap2.controller.BluetoothTransportIdentity
import com.carplay.sender.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init { WirelessStartupPreferences.upgrade(prefs, BuildConfig.IS_CARLIFE_COMPONENT) }
    var server: String
        get() {
            val stored = prefs.getString("server", DEFAULT_SERVER)!!
            if (stored.trimEnd('/') != "https://zncz.sz1.natnps.cn") return stored
            prefs.edit().putString("server", DEFAULT_SERVER).apply()
            return DEFAULT_SERVER
        }
        set(value) { val url = java.net.URI(value.trim()); require(url.scheme == "https" && url.host != null && url.userInfo == null && url.query == null && url.fragment == null); prefs.edit().putString("server", value.trim().trimEnd('/')).apply() }
    var peer: String?
        get() = prefs.getString("peer", null)
        set(value) { prefs.edit().putString("peer", value).apply() }
    var componentMode: ComponentMode
        get() = ComponentMode.restore(prefs.getString("componentMode", null), BuildConfig.IS_CARLIFE_COMPONENT)
        set(value) { check(BuildConfig.IS_CARLIFE_COMPONENT); prefs.edit().putString("componentMode", value.name).apply() }
    var audio: Boolean
        get() = prefs.getBoolean("audio", false)
        set(value) { prefs.edit().putBoolean("audio", value).apply() }
    var autoUpload: Boolean
        get() = prefs.getBoolean("autoUpload", true)
        set(value) { prefs.edit().putBoolean("autoUpload", value).apply() }
    var requireRoot: Boolean
        get() = prefs.getBoolean("requireRoot", hasTrust())
        set(value) { prefs.edit().putBoolean("requireRoot", value).apply() }
    var bluetoothProfile: PhoneBluetoothProfile
        get() = PhoneBluetoothProfile.entries.firstOrNull { it.name == prefs.getString("bluetoothProfile", null) }
            ?: if (BuildConfig.IS_CARLIFE_COMPONENT) PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST else PhoneBluetoothProfile.ACCESSORY_FIRST
        set(value) { prefs.edit().putString("bluetoothProfile", value.name).apply() }
    var connectionDirection: RfcommConnectionDirection
        get() = RfcommConnectionDirection.entries.firstOrNull { it.name == prefs.getString("connectionDirection",null) }
            ?: RfcommConnectionDirection.PHONE_INITIATED
        set(value) { prefs.edit().putString("connectionDirection",value.name).apply() }
    var bootstrapProfile: PhoneBootstrapProfile
        get() = PhoneBootstrapProfile.entries.firstOrNull { it.name == prefs.getString("bootstrapProfile",null) }
            ?: if (BuildConfig.IS_CARLIFE_COMPONENT) PhoneBootstrapProfile.MODERN_WIRELESS else PhoneBootstrapProfile.GENERIC
        set(value) { prefs.edit().putString("bootstrapProfile",value.name).apply() }
    var phoneBluetoothAddress: String?
        get() = BluetoothTransportIdentity.normalizedAddress(prefs.getString("phoneBluetoothAddress", null))
        set(value) {
            val address = if (value.isNullOrBlank()) null else
                requireNotNull(BluetoothTransportIdentity.normalizedAddress(value)) { "请填写手机自身的有效蓝牙地址" }
            prefs.edit().putString("phoneBluetoothAddress", address).apply()
        }
    fun accessoryKey(endpoint: String): ByteArray? = prefs.getString("accessoryKey:$endpoint", null)?.let(::unseal)
    fun saveAccessoryKey(endpoint: String, key: ByteArray) {
        check(prefs.edit().putString("accessoryKey:$endpoint",seal(key)).commit())
    }
    val installationId: String get() = synchronized(lock) {
        prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("installation", it).commit()) }
    }
    fun identity(): ControllerIdentity = synchronized(lock) {
        val stored = prefs.getString("identity", null)
        if (stored != null) {
            val json = JSONObject(String(unseal(stored)))
            ControllerIdentity(decode(json.getString("private")), decode(json.getString("public")), json.getString("id"))
        } else {
            val pair = AirPlayCrypto.ed25519Generate()
            val identity = ControllerIdentity(pair.privateKey, pair.publicKey, UUID.randomUUID().toString())
            val json = JSONObject().put("private", encode(pair.privateKey)).put("public", encode(pair.publicKey)).put("id", identity.id)
            check(prefs.edit().putString("identity", seal(json.toString().toByteArray())).commit()); identity
        }
    }
    fun paired(endpoint: String): PairedAccessory? = prefs.getString("pair:$endpoint", null)?.let {
        val json = JSONObject(String(unseal(it))); PairedAccessory(json.getString("id"), decode(json.getString("public")))
    }
    fun savePair(endpoint: String, peer: PairedAccessory) {
        val json = JSONObject().put("id", peer.id).put("public", encode(peer.publicKey))
        check(prefs.edit().putString("pair:$endpoint", seal(json.toString().toByteArray())).commit())
    }
    fun removePair(endpoint: String) {
        prefs.edit().remove("pair:$endpoint").apply()
    }
    fun importTrust(bytes: ByteArray): Int {
        require(bytes.size in 1..1_000_000)
        val roots = CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream()).map { it as X509Certificate }
        require(roots.isNotEmpty() && roots.all { it.basicConstraints >= 0 }) { "请选择车机认证 CA 根证书文件" }
        roots.forEach { it.checkValidity() }
        val file = File(context.filesDir, "accessory-roots.der")
        val temporary = File(context.filesDir, "accessory-roots.tmp"); temporary.writeBytes(bytes)
        check(temporary.renameTo(file)) { "根证书保存失败" }; requireRoot = true; return roots.size
    }
    fun trust(): AccessoryTrust {
        val file = File(context.filesDir, "accessory-roots.der")
        val certificates = if (file.isFile) CertificateFactory.getInstance("X.509").generateCertificates(file.inputStream()).map { it as X509Certificate } else emptyList()
        return AccessoryTrust(certificates)
    }
    fun hasTrust() = File(context.filesDir, "accessory-roots.der").isFile
    fun diagnosticPublicKey(): ByteArray = holder().certificate.encoded
    fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(holder().privateKey); update(bytes); sign() }
    private fun holder(): KeyStore.PrivateKeyEntry = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias("carplay-diagnostics")) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder("carplay-diagnostics", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        store.getEntry("carplay-diagnostics", null) as KeyStore.PrivateKeyEntry
    }
    fun uploadPublicKey(): ByteArray = holder().certificate.publicKey.encoded
    private fun secret(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias("carplay-storage")) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("carplay-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        store.getKey("carplay-storage", null) as SecretKey
    }
    private fun seal(bytes: ByteArray): String = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, secret()); encode(iv + doFinal(bytes)) }
    private fun unseal(value: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        val bytes = decode(value); require(bytes.size >= 28); init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0,12))); doFinal(bytes.copyOfRange(12,bytes.size))
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    companion object {
        const val DEFAULT_SERVER = "https://zncz.zj2.natnps.cn"
        private val lock = Any()
    }
}
