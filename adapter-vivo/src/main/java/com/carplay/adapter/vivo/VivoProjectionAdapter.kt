package com.carplay.adapter.vivo

import android.content.Context
import android.os.Build
import com.carplay.adapter.generic.ProjectionCapture

/** Authorized fallback until an OEM projection host exposes a compatible plugin contract. */
class VivoProjectionAdapter : ProjectionCapture("vivo屏幕投射") {
    override fun isSupported(context: Context): Boolean {
        val brand = Build.BRAND.lowercase(); val manufacturer = Build.MANUFACTURER.lowercase()
        return (manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")) && super.isSupported(context)
    }
}
