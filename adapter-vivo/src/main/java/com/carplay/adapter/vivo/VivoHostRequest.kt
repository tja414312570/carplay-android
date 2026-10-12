package com.carplay.adapter.vivo

import android.content.ComponentName
import android.content.Intent

data class VivoHostRequest(val packageName: String, val className: String, val usbType: Int, val version: String) {
    val component get() = ComponentName(packageName,className)
    fun toIntent() = Intent().putExtra("bindPackageName",packageName).putExtra("bindClassName",className)
        .putExtra("usb_type",usbType).putExtra("bindVersion",version)
    companion object {
        fun from(intent: Intent): VivoHostRequest {
            val pkg = requireNotNull(intent.getStringExtra("bindPackageName")) { "宿主包名缺失" }
            val cls = requireNotNull(intent.getStringExtra("bindClassName")) { "宿主服务名缺失" }
            val version = requireNotNull(intent.getStringExtra("bindVersion")) { "宿主版本缺失" }
            val type = intent.getIntExtra("usb_type",-1)
            require(pkg.startsWith("com.vivo.") && cls.isNotBlank() && cls.length <= 256)
            require(type in 0..1 && !intent.getBooleanExtra("is_box",false)) { "组件仅支持普通无线连接" }
            require(version.isNotBlank() && version.length <= 64)
            return VivoHostRequest(pkg,cls,type,version)
        }
    }
}
