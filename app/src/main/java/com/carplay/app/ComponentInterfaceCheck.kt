package com.carplay.app

import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import com.carplay.app.diagnostics.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Host interface binding check. Does not create a display or fabricate a vehicle launch. */
object ComponentInterfaceCheck {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    fun run(context: Context, completed: () -> Unit = {}) {
        check(running.compareAndSet(false,true)) { "组件接口检查正在进行" }
        val app = context.applicationContext
        val id = Diagnostics.begin()
        Diagnostics.event("INFO","组件接口检查：${BuildConfig.APPLICATION_ID} / ${BuildConfig.VERSION_NAME}",id)
        worker.execute {
            val ready = CompletableFuture<String>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    try { ready.complete(service.interfaceDescriptor) } catch (error: Exception) { ready.completeExceptionally(error) }
                }
                override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("宿主接口断开")) }
                override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("宿主接口为空")) }
                override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(IllegalStateException("宿主绑定失效")) }
            }
            var bound = false
            try {
                check(BuildConfig.IS_CARLIFE_COMPONENT) { "此检查仅适用于 vivo 组件版" }
                val pm = app.packageManager
                val entry = ComponentName(BuildConfig.APPLICATION_ID,"com.baidu.carlife.service.CarlifeConnectService")
                check(pm.getServiceInfo(entry,0).exported) { "组件入口未导出" }
                Diagnostics.event("INFO","CarLife 组件入口存在且已导出",id)
                val host = ComponentName("com.vivo.car.networking","com.vivo.vivo_car_service.CarLifeMixingService")
                val info = pm.getServiceInfo(host,0)
                check(info.enabled && info.exported) { "宿主接口未开放" }
                info.permission?.let { check(app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) { "宿主权限未授予：$it" } }
                bound = app.bindService(Intent().setComponent(host),connection,Context.BIND_AUTO_CREATE)
                check(bound) { "宿主拒绝接口绑定" }
                val descriptor = ready.get(10,TimeUnit.SECONDS)
                check(descriptor.isNullOrBlank() || descriptor == "com.baidu.carlife.mixing.aidl.ICarLifeCastManager") { "宿主接口类型不同：$descriptor" }
                Diagnostics.event("INFO","已实际绑定 vivo 宿主 Binder：${descriptor ?: "默认(无描述符)"}",id)
                Diagnostics.stage(id,Stage.STOPPED,"组件入口与宿主接口检查通过；未连接车机")
            } catch (error: Exception) {
                Diagnostics.stage(id,Stage.FAILED,"组件接口检查失败：${error.message}")
            } finally {
                if (bound) runCatching { app.unbindService(connection) }
                if (SettingsStore(app).autoUpload) runCatching { DiagnosticUploader.enqueue(app,id) }
                running.set(false)
                completed()
            }
        }
    }
}
