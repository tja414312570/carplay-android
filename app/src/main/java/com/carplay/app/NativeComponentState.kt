package com.carplay.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.carplay.adapter.vivo.VivoHostRequest
import com.carplay.app.diagnostics.Diagnostics

object NativeComponentState {
    @Volatile var request: VivoHostRequest? = null
        private set
    @Volatile var generation: Long = 0
        private set
    @Synchronized fun accept(context: Context, intent: Intent): VivoHostRequest {
        check(BuildConfig.IS_CARLIFE_COMPONENT)
        val launch = VivoHostRequest.from(intent)
        check(context.packageManager.checkSignatures(launch.packageName,"com.vivo.car.networking") == PackageManager.SIGNATURE_MATCH) { "vivo 宿主签名校验失败" }
        request?.let { existing ->
            if (existing == launch) return existing
            check(Diagnostics.status.value.stage.step == 0) { "已有组件会话正在运行，请先停止" }
        }
        request = launch
        generation++
        Diagnostics.event("INFO","已收到 vivo CarLife 组件启动请求，宿主 ${launch.packageName}，无线类型 ${launch.usbType}")
        return launch
    }
    @Synchronized fun clear(owned: VivoHostRequest?): Boolean {
        if (owned == null || request !== owned) return false
        request = null; return true
    }
}
