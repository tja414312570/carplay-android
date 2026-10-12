package com.carplay.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.os.*
import android.provider.Settings
import android.view.Surface
import com.carplay.adapter.vivo.VivoHostCallPolicy
import com.carplay.adapter.vivo.VivoHostRequest
import com.carplay.adapter.vivo.VivoHostSurface
import com.carplay.app.diagnostics.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A local consumer of Jovi's authorized Surface. No car networking or sender pipeline is created. */
class ComponentPreviewService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var current: PreviewRun? = null
    private class PreviewRun(val id: String, val lease: ProjectionSessionOwnership.Lease) {
        val session = ComponentPreviewSession()
        var window: ComponentPreviewWindow? = null
        var request: VivoHostRequest? = null
        var hostGeneration = 0L
        var source: VivoHostSurface? = null
        var surface: Surface? = null
        var texture: SurfaceTexture? = null
        val audioCallbacks = AtomicLong()
        val startNanos = SystemClock.elapsedRealtimeNanos()
        var sampleNanos = startNanos
        var sampleFrames = 0L
        var fps = 0.0
        var firstFrameReported = false
    }
    override fun onCreate() { super.onCreate(); Diagnostics.initialize(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_HOST) {
            if (current == null || current?.hostGeneration == intent.getLongExtra("hostGeneration",-1)) {
                finish(current, "Jovi 宿主已结束组件调用", null); stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            finish(current, "组件验证已停止", null); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) { stopSelf(startId); return START_NOT_STICKY }
        if (!BuildConfig.IS_CARLIFE_COMPONENT || SettingsStore(this).componentMode != ComponentMode.VERIFY_COMPONENT) {
            finish(current, "已切换到 CarPlay 模式", null); stopSelf(startId); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Diagnostics.event("WARN", "组件验证需要显示在其他应用上层权限，请打开组件并授权")
            finish(current, "悬浮窗权限未授予", null); stopSelf(startId); return START_NOT_STICKY
        }
        val run = current ?: try {
            val lease = ProjectionSessions.ownership.claim(ComponentMode.VERIFY_COMPONENT)
            val id = try { Diagnostics.begin(ComponentMode.VERIFY_COMPONENT.name) }
                catch (error: Exception) { ProjectionSessions.ownership.release(lease); throw error }
            PreviewRun(id, lease).also { current = it; showForeground(); createWindow(it) }
        } catch (error: Exception) {
            if (current != null) finish(current, VivoHostCallPolicy.failureMessage(error), error)
            else Diagnostics.event("WARN", error.message ?: "上一投屏尚未停止")
            stopSelf(startId); return START_NOT_STICKY
        }
        try {
            val launch = hostExtra(intent)
            val accepted = NativeComponentState.request
            if (launch != null) check(accepted == launch) { "请等待 Jovi 的真实组件调用" }
            accepted?.let { request ->
                val generation = NativeComponentState.generation
                val identity = "${request.packageName}/${request.className}/${request.version}/${request.usbType}/$generation"
                if (run.session.hostArrived(identity)) {
                    run.request = request
                    run.hostGeneration = generation
                    Diagnostics.event("INFO", "组件验证收到真实 Jovi 宿主入口 ${request.packageName}/${request.className}，版本 ${request.version}", run.id)
                }
            }
            startHostIfReady(run)
        } catch (error: Exception) { finish(run, VivoHostCallPolicy.failureMessage(error), error); stopSelf(startId) }
        return START_NOT_STICKY
    }

    private fun showForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("component-preview", "组件验证", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, ComponentPreviewService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "component-preview").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CarLife 组件验证").setContentText("Jovi 原生画面在手机悬浮窗显示")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止验证", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(20, notification)
    }
    private fun createWindow(run: PreviewRun) {
        Diagnostics.event("INFO", "版本 ${BuildConfig.VERSION_NAME}，手机 ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}", run.id)
        Diagnostics.stage(run.id, Stage.IDLE, "等待 Jovi 调用组件；本次仅在手机预览原生画面，无需选择或连接车机")
        val window = ComponentPreviewWindow(this,
            surfaceReady = { surface, texture ->
                if (current !== run || !run.session.surfaceArrived()) { surface.release(); texture.release() }
                else { run.surface = surface; run.texture = texture; startHostIfReady(run) }
            },
            frame = { timestamp ->
                if (current === run && run.session.frame(timestamp, SystemClock.elapsedRealtimeNanos())) reportFirstFrame(run)
            },
            surfaceLost = { if (current === run) { finish(run, "预览窗口画面已关闭", null); stopSelf() } },
            stop = { finish(run, "用户停止组件验证", null); stopSelf() },
        )
        run.window = window
        window.show()
        main.postDelayed(object : Runnable {
            override fun run() {
                if (current !== run) return
                val now = SystemClock.elapsedRealtimeNanos()
                run.fps = (run.session.frames - run.sampleFrames) * 1_000_000_000.0 / (now - run.sampleNanos).coerceAtLeast(1)
                run.sampleNanos = now; run.sampleFrames = run.session.frames
                updateMetrics(run)
                run.window?.status("${run.session.phase.label}\n1280×720 · 已显示 ${run.session.frames} 帧 · ${"%.1f".format(Locale.US, run.fps)} fps\n原生音频回调 ${run.audioCallbacks.get()} 次")
                if (run.session.frames > 0) Diagnostics.event("INFO", "组件本地预览：显示 ${run.session.frames} 帧，${"%.1f".format(Locale.US, run.fps)} fps，音频回调 ${run.audioCallbacks.get()} 次", run.id)
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }
    private fun startHostIfReady(run: PreviewRun) {
        if (current !== run || !run.session.takeStart()) return
        val source = VivoHostSurface(checkNotNull(run.request))
        run.source = source
        Diagnostics.stage(run.id, Stage.MEDIA, "组件验证：绑定 Jovi 原生投屏接口，等待真实宿主画面")
        worker.execute {
            try {
                source.attach(applicationContext,
                    { message -> Diagnostics.event("INFO", message, run.id) },
                    { error -> main.post { if (current === run) { finish(run, VivoHostCallPolicy.failureMessage(error), error); stopSelf() } } })
                if (run.session.terminal) return@execute
                source.start(checkNotNull(run.surface), ComponentPreviewWindow.WIDTH, ComponentPreviewWindow.HEIGHT, 30,
                    { _, rate, channels, encoding ->
                        if (run.audioCallbacks.incrementAndGet() == 1L) Diagnostics.event("INFO", "组件原生音频首包：$rate Hz，$channels 声道，编码 $encoding；仅记录回调", run.id)
                    })
                main.post {
                    if (current === run && run.session.hostReady()) {
                        Diagnostics.stage(run.id, Stage.MEDIA, "组件宿主显示已就绪，等待实际画面更新")
                        reportFirstFrame(run)
                        main.postDelayed({
                            if (current === run && run.session.frames == 0L) {
                                finish(run, "宿主已准备显示，但 10 秒内未收到任何原生画面", IllegalStateException("原生画面首帧超时")); stopSelf()
                            }
                        }, 10_000)
                    }
                }
            } catch (error: Exception) {
                main.post { if (current === run) { finish(run, VivoHostCallPolicy.failureMessage(error), error); stopSelf() } }
            }
        }
    }
    private fun reportFirstFrame(run: PreviewRun) {
        if (run.firstFrameReported || run.session.phase != ComponentPreviewSession.Phase.PREVIEWING) return
        run.firstFrameReported = true
        updateMetrics(run)
        Diagnostics.stage(run.id, Stage.STREAMING, "组件验证成功：已收到 Jovi 原生画面并显示在手机悬浮窗")
    }
    private fun updateMetrics(run: PreviewRun) {
        Diagnostics.preview(run.id, ComponentPreviewMetrics(ComponentPreviewWindow.WIDTH, ComponentPreviewWindow.HEIGHT,
            run.session.frames, run.fps, run.audioCallbacks.get(), run.session.firstFrameNanos?.let { (it - run.startNanos) / 1_000_000 }))
    }
    private fun finish(run: PreviewRun?, message: String, error: Throwable?) {
        if (run == null || current !== run) return
        current = null
        if (error == null) run.session.stop() else run.session.fail()
        updateMetrics(run)
        Diagnostics.stage(run.id, if (error == null) Stage.STOPPED else Stage.FAILED, message)
        runCatching { run.window?.dismiss() }.onFailure { Diagnostics.event("WARN","预览窗口移除：${it.message}",run.id) }
        run.window = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        worker.execute {
            ProjectionSessions.ownership.finish(run.lease) {
                try { run.source?.close() }
                finally { try { run.surface?.release() } finally { run.texture?.release() } }
            }
            if (SettingsStore(applicationContext).autoUpload) runCatching { DiagnosticUploader.enqueue(applicationContext, run.id) }
        }
    }
    override fun onDestroy() { finish(current, "组件验证服务已停止", null); worker.shutdown(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        current?.window?.resize()
    }

    companion object {
        const val ACTION_START = "com.carplay.COMPONENT_PREVIEW_START"
        const val ACTION_STOP = "com.carplay.COMPONENT_PREVIEW_STOP"
        const val ACTION_STOP_HOST = "com.carplay.COMPONENT_PREVIEW_STOP_HOST"
        fun start(context: Context, request: VivoHostRequest? = NativeComponentState.request): Boolean {
            if (!Settings.canDrawOverlays(context)) {
                Diagnostics.event("WARN", "Jovi 已调用组件；请打开组件、授予悬浮窗权限后开始验证")
                return false
            }
            context.startForegroundService(Intent(context, ComponentPreviewService::class.java).setAction(ACTION_START)
                .putExtra("nativeHost", request?.toIntent()))
            return true
        }
        private fun hostExtra(intent: Intent): VivoHostRequest? {
            val value = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("nativeHost", Intent::class.java)
            else { @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("nativeHost") }
            return value?.let(VivoHostRequest::from)
        }
    }
}
