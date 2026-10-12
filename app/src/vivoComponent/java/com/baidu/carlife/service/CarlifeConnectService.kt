package com.baidu.carlife.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.carplay.adapter.vivo.VivoHostRequest
import com.carplay.app.*
import com.carplay.app.diagnostics.*

/** Original 8.8.4 component entry; this service accepts the host's real wireless launch contract. */
class CarlifeConnectService : Service() {
    private var owned: VivoHostRequest? = null
    private var ownedGeneration = 0L
    private val binder = Binder()
    override fun onCreate() { super.onCreate(); Diagnostics.initialize(this) }
    private fun accept(intent: Intent?) {
        if (intent == null) return
        try {
            val request = NativeComponentState.accept(this,intent); owned = request; ownedGeneration = NativeComponentState.generation
            if (SettingsStore(this).componentMode == ComponentMode.VERIFY_COMPONENT) {
                ComponentPreviewService.start(this,request)
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Diagnostics.event("WARN","请先打开组件并授予蓝牙权限，再从 Jovi InCar 重试")
                return
            }
            val peer = SettingsStore(this).peer
            if (peer == null) {
                Diagnostics.event("WARN","组件已被宿主启动；请打开组件设置并选择已配对车机")
                return
            }
            startForegroundService(Intent(this,CarPlayTransmitterService::class.java).setAction(CarPlayTransmitterService.ACTION_START)
                .putExtra("peer",peer).putExtra("nativeHost",request.toIntent()))
        } catch (error: Exception) { Diagnostics.event("ERROR","组件启动请求未接受：${error.message}"); stopSelf() }
    }
    override fun onBind(intent: Intent?): IBinder { accept(intent); return binder }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { accept(intent); return START_NOT_STICKY }
    override fun onUnbind(intent: Intent?): Boolean { stopSelf(); return false }
    override fun onDestroy() {
        if (NativeComponentState.clear(owned)) {
            startService(Intent(this,CarPlayTransmitterService::class.java).setAction(CarPlayTransmitterService.ACTION_STOP_NATIVE_HOST))
            startService(Intent(this,ComponentPreviewService::class.java).setAction(ComponentPreviewService.ACTION_STOP_HOST)
                .putExtra("hostGeneration",ownedGeneration))
        }
        super.onDestroy()
    }
}
