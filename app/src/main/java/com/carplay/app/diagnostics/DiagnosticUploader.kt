package com.carplay.app.diagnostics

import android.content.Context
import android.util.Base64
import com.carplay.app.SettingsStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Durable queue: successful files are removed; failures remain available on the next retry. */
object DiagnosticUploader {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { generation.incrementAndGet(); active?.disconnect(); Diagnostics.upload("上传已取消，日志保留") }
    fun enqueue(context: Context, sessionId: String = Diagnostics.status.value.id) {
        val app = context.applicationContext
        val zip = Diagnostics.export(sessionId)
        val queue = File(app.filesDir, "upload-queue").apply { mkdirs() }
        zip.copyTo(File(queue, zip.name), overwrite = true)
        retry(app)
    }
    fun retry(context: Context) {
        val ticket = generation.incrementAndGet(); active?.disconnect()
        executor.execute {
            val queue = File(context.filesDir, "upload-queue")
            for (file in queue.listFiles()?.filter { it.extension == "zip" }?.sortedBy { it.lastModified() }.orEmpty()) {
                if (ticket != generation.get()) break
                try {
                    upload(context, file, ticket)
                    check(file.delete()) { "上传成功，但本地队列文件未删除" }
                    if (ticket == generation.get()) Diagnostics.upload("上传成功，后管可查看", 100)
                } catch (error: Exception) {
                    if (ticket == generation.get()) { Diagnostics.upload("上传失败，日志保留待重试"); Diagnostics.event("ERROR", "日志上传：${error.message}") }
                    break
                }
            }
        }
    }
    private fun upload(context: Context, file: File, ticket: Long) {
        require(file.length() in 1..50L * 1024 * 1024)
        val settings = SettingsStore(context)
        val key = settings.uploadPublicKey()
        val device = "carplay-" + MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        val request = UploadProof.request(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000, key, settings::sign)
        val proof = settings.sign(UploadProof.registration(device, settings.installationId, request))
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val connection = URL("${settings.server}/api/v1/carplay/log-uploads").openConnection() as HttpURLConnection
        active = connection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.connectTimeout = 10000; connection.readTimeout = 60000
        connection.setRequestProperty("Content-Type", "application/zip")
        connection.setRequestProperty("X-Device-ID", device)
        connection.setRequestProperty("X-Installation-ID", settings.installationId)
        connection.setRequestProperty("X-Request", encode(request)); connection.setRequestProperty("X-Registration-Proof", encode(proof))
        try {
            connection.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input -> connection.outputStream.use { output ->
                val bytes = ByteArray(32768); var sent = 0L
                while (true) {
                    check(ticket == generation.get()) { "上传已取消" }
                    val count = input.read(bytes); if (count < 0) break
                    output.write(bytes, 0, count); sent += count
                    if (ticket == generation.get()) Diagnostics.upload("正在上传日志", (sent * 100 / file.length()).toInt())
                }
            } }
            check(connection.responseCode == 200) { "后管响应 HTTP ${connection.responseCode}" }
        } finally { connection.disconnect(); if (active === connection) active = null }
    }
}
