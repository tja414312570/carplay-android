package com.carplay.adapter.xiaomi

import android.content.Context
import android.os.Build
import com.carplay.adapter.generic.ProjectionCapture

/** Authorized fallback until an OEM projection host exposes a compatible plugin contract. */
class XiaomiProjectionAdapter : ProjectionCapture("小米屏幕投射") {
    override fun isSupported(context: Context): Boolean {
        val brand = Build.BRAND.lowercase(); val manufacturer = Build.MANUFACTURER.lowercase()
        return (manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi")) && super.isSupported(context)
    }
}
