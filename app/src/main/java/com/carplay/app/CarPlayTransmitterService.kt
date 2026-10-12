package com.carplay.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.carplay.adapter.ProjectionGrant
import com.carplay.adapter.generic.ProjectionCapture
import com.carplay.adapter.vivo.VivoHostRequest
import com.carplay.adapter.vivo.VivoHostSurface
import com.carplay.adapter.vivo.DeferredVivoHostSurface
import com.carplay.app.diagnostics.*
import java.util.concurrent.Executors

class CarPlayTransmitterService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    @Volatile private var connection: WirelessConnection? = null
    @Volatile private var sessionId: String? = null
    override fun onCreate() { super.onCreate(); Diagnostics.initialize(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START || connection != null) return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("capture",Intent::class.java) else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("capture")
        }
        val address = intent.getStringExtra("peer")
        val hostIntent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("nativeHost",Intent::class.java) else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("nativeHost")
        }
        val native = if (hostIntent == null || !BuildConfig.IS_CARLIFE_COMPONENT) null else VivoHostRequest.from(hostIntent)
        if (data == null && native == null && !BuildConfig.IS_CARLIFE_COMPONENT || address == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection","无线投屏",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,CarPlayTransmitterService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this,"connection").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("CarPlay 无线投屏").setContentText("点击查看进度与日志").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(10,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if (data != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        else startForeground(10,notification)
        val settings = SettingsStore(this)
        val adapter = if (!BuildConfig.IS_CARLIFE_COMPONENT) ProjectionAdapterRegistry.getBestAdapter(this) else ProjectionCapture("vivo CarLife 原生投屏")
        if (adapter is ProjectionCapture) {
            adapter.captureAudio = settings.audio
            if (native != null) adapter.surfaceSource = VivoHostSurface(native)
            else if (BuildConfig.IS_CARLIFE_COMPONENT) adapter.surfaceSource = DeferredVivoHostSurface { NativeComponentState.request }
        }
        if (data != null) adapter.setGrant(ProjectionGrant(intent.getIntExtra("captureResult",Activity.RESULT_CANCELED),data))
        val suppliedId = intent.getStringExtra("sessionId")
        val id = if (suppliedId == Diagnostics.status.value.id && Diagnostics.status.value.stage == Stage.IDLE) suppliedId else Diagnostics.begin()
        checkNotNull(id); sessionId = id
        Diagnostics.event("INFO","发送端版本 ${BuildConfig.VERSION_NAME}，包名 ${BuildConfig.APPLICATION_ID}，Android ${Build.VERSION.RELEASE}",id)
        Diagnostics.event("INFO","启动来源：component=${BuildConfig.IS_CARLIFE_COMPONENT}，screenGrant=${data != null}，nativeHost=${native != null}",id)
        native?.let { Diagnostics.event("INFO","真实宿主入口 ${it.packageName}/${it.className}，宿主版本 ${it.version}，无线类型 ${it.usbType}",id) }
        val current = WirelessConnection(applicationContext,id,address,adapter,settings); connection = current
        worker.execute {
            try { current.run() } catch (error: Exception) {
                if (connection === current) Diagnostics.stage(id,Stage.FAILED,error.message ?: error.javaClass.simpleName)
            } finally {
                current.close()
                if (connection === current) {
                    connection = null
                    if (Diagnostics.status.value.stage != Stage.FAILED) Diagnostics.stage(id,Stage.STOPPED,"连接已结束")
                    if (settings.autoUpload) runCatching { DiagnosticUploader.enqueue(applicationContext,id) }
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun stop() {
        val old = connection; connection = null
        if (old == null) { stopForeground(STOP_FOREGROUND_REMOVE); return }
        val oldId = sessionId
        sessionId?.let { Diagnostics.stage(it,Stage.STOPPED,"用户停止连接") }
        cleanup.execute {
            old?.close()
            if (SettingsStore(applicationContext).autoUpload && oldId != null) runCatching { DiagnosticUploader.enqueue(applicationContext,oldId) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { stop(); worker.shutdown(); cleanup.shutdown(); super.onDestroy() }
    companion object {
        const val ACTION_START = "com.carplay.START"
        const val ACTION_STOP = "com.carplay.STOP"
    }
}
