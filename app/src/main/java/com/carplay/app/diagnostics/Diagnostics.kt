package com.carplay.app.diagnostics

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Stage(val title: String, val step: Int) {
    IDLE("未连接", 0), BLUETOOTH("连接蓝牙", 1), IDENTIFY("识别车机", 2), AUTHENTICATE("验证身份", 3),
    NETWORK("连接无线网络", 4), DISCOVERY("发现车机服务", 5), PAIRING("建立安全会话", 6),
    MEDIA("协商媒体通道", 7), STREAMING("正在发送", 8), STOPPED("已停止", 0), FAILED("连接失败", 0)
}
data class LogEvent(val wallMillis: Long, val elapsedMillis: Long, val stage: String, val level: String, val message: String)
data class MetricSample(val elapsedMillis: Long, val fps: Double, val kbps: Double, val sentFrames: Long, val droppedFrames: Long)
data class ComponentPreviewMetrics(val width: Int, val height: Int, val displayedFrames: Long,
    val displayFps: Double, val audioCallbacks: Long, val firstFrameMillis: Long?)
data class SessionStatus(
    val id: String = "", val stage: Stage = Stage.IDLE, val detail: String = "选择车机并授权屏幕投射",
    val startedMillis: Long = 0, val elapsedMillis: Long = 0, val events: List<LogEvent> = emptyList(),
    val samples: List<MetricSample> = emptyList(), val frames: Long = 0, val bytes: Long = 0,
    val dropped: Long = 0, val audioPackets: Long = 0, val inputEvents: Long = 0,
    val upload: String = "尚未上传", val uploadProgress: Int? = null,
    val mode: String = "CARPLAY", val componentPreview: ComponentPreviewMetrics? = null,
)

object LogRedaction {
    fun clean(message: String): String = message.take(4000)
}

/** A bounded, disk-backed event journal shared by the UI and the uploader. */
object Diagnostics {
    private val mutable = MutableStateFlow(SessionStatus())
    val status = mutable.asStateFlow()
    private var directory: File? = null
    private var journal: File? = null
    private var startNanos = 0L
    private var sampleNanos = 0L
    private var sampleFrames = 0L
    private var sampleBytes = 0L
    private var nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos
    internal fun testStorage(path: File, clock: () -> Long) {
        directory = path.apply { mkdirs() }; journal = null; nanoTime = clock
        mutable.value = SessionStatus(); startNanos = 0L
    }

    @Synchronized fun initialize(context: Context) { directory = File(context.filesDir, "diagnostics").apply { mkdirs() } }
    @Synchronized fun begin(mode: String = "CARPLAY"): String {
        if (mutable.value.id.isNotBlank()) export()
        val id = UUID.randomUUID().toString()
        startNanos = nanoTime(); sampleNanos = startNanos; sampleFrames = 0; sampleBytes = 0
        journal = File(checkNotNull(directory), "$id.jsonl")
        mutable.value = SessionStatus(id = id, startedMillis = System.currentTimeMillis(), mode = mode)
        event("INFO", if (mode == "VERIFY_COMPONENT") "创建本地 CarLife 组件验证会话" else "创建无线 CarPlay 会话")
        directory!!.listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        return id
    }
    @Synchronized fun stage(id: String, stage: Stage, detail: String) {
        if (id != mutable.value.id) return
        mutable.value = mutable.value.copy(stage = stage, detail = LogRedaction.clean(detail), elapsedMillis = elapsed())
        event(if (stage == Stage.FAILED) "ERROR" else "INFO", detail)
    }
    @Synchronized fun event(level: String, message: String, sessionId: String = mutable.value.id) {
        if (mutable.value.id.isBlank()) {
            mutable.value = mutable.value.copy(id = "local-diag", startedMillis = System.currentTimeMillis())
        } else if (sessionId.isNotBlank() && sessionId != mutable.value.id) {
            return
        }
        val state = mutable.value
        val event = LogEvent(System.currentTimeMillis(), elapsed(), state.stage.name, level, LogRedaction.clean(message))
        mutable.value = state.copy(events = (state.events + event).takeLast(400), elapsedMillis = event.elapsedMillis)
        journal?.let { file ->
            if (file.length() < 4 * 1024 * 1024) file.appendText(json(event).toString() + "\n")
        }
    }
    @Synchronized fun video(id: String, count: Int, dropped: Boolean = false) {
        if (id != mutable.value.id) return
        val old = mutable.value
        mutable.value = if (dropped) old.copy(dropped = old.dropped + 1) else old.copy(frames = old.frames + 1, bytes = old.bytes + count)
        val now = nanoTime()
        if (now - sampleNanos >= 1_000_000_000) {
            val state = mutable.value; val seconds = (now - sampleNanos) / 1_000_000_000.0
            val sample = MetricSample(elapsed(), (state.frames - sampleFrames) / seconds, (state.bytes - sampleBytes) * 8 / seconds / 1000, state.frames, state.dropped)
            mutable.value = state.copy(samples = (state.samples + sample).takeLast(180), elapsedMillis = sample.elapsedMillis)
            sampleNanos = now; sampleFrames = state.frames; sampleBytes = state.bytes
        }
    }
    @Synchronized fun audio(id: String) { if (id == mutable.value.id) mutable.value = mutable.value.copy(audioPackets = mutable.value.audioPackets + 1) }
    @Synchronized fun preview(id: String, metrics: ComponentPreviewMetrics) {
        if (id != mutable.value.id) return
        require(metrics.width > 0 && metrics.height > 0 && metrics.displayedFrames >= 0 &&
            metrics.displayFps.isFinite() && metrics.displayFps >= 0 && metrics.audioCallbacks >= 0)
        mutable.value = mutable.value.copy(componentPreview = metrics, elapsedMillis = elapsed())
    }
    @Synchronized fun input(id: String) { if (id == mutable.value.id) mutable.value = mutable.value.copy(inputEvents = mutable.value.inputEvents + 1) }
    @Synchronized fun upload(message: String, progress: Int? = null) { mutable.value = mutable.value.copy(upload = message, uploadProgress = progress) }
    @Synchronized fun export(id: String = mutable.value.id): File {
        if (id != mutable.value.id) return File(checkNotNull(directory), "$id.zip").also { check(it.isFile) { "指定会话日志不存在" } }
        val state = mutable.value; check(state.id.isNotBlank()) { "暂无会话日志" }
        val result = File(checkNotNull(directory), "${state.id}.zip")
        val summary = JSONObject().put("schemaVersion", 1).put("source", "carplay-android").put("sessionId", state.id)
            .put("startedAt", state.startedMillis).put("elapsedMs", elapsed()).put("stage", state.stage.name).put("detail", state.detail)
            .put("frames", state.frames).put("bytes", state.bytes).put("dropped", state.dropped).put("audioPackets", state.audioPackets).put("inputEvents", state.inputEvents)
            .put("mode", state.mode)
            .put("events", JSONArray().apply { state.events.forEach { put(json(it)) } })
            .put("samples", JSONArray().apply { state.samples.forEach { put(JSONObject().put("elapsedMs", it.elapsedMillis).put("fps", it.fps).put("kbps", it.kbps).put("sentFrames", it.sentFrames).put("droppedFrames", it.droppedFrames)) } })
        state.componentPreview?.let { metrics -> summary.put("componentPreview", JSONObject()
            .put("width", metrics.width).put("height", metrics.height).put("displayedFrames", metrics.displayedFrames)
            .put("displayFps", metrics.displayFps).put("audioCallbacks", metrics.audioCallbacks)
            .put("firstFrameMs", metrics.firstFrameMillis ?: JSONObject.NULL)) }
        ZipOutputStream(result.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("session.json")); zip.write(summary.toString().toByteArray()); zip.closeEntry()
            journal?.takeIf { it.isFile }?.let { file -> zip.putNextEntry(ZipEntry("events.jsonl")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
        return result
    }
    private fun elapsed() = if (startNanos == 0L) 0L else (nanoTime() - startNanos) / 1_000_000
    private fun json(e: LogEvent) = JSONObject().put("time", e.wallMillis).put("elapsedMs", e.elapsedMillis).put("stage", e.stage).put("level", e.level).put("message", e.message)
}
