package com.carplay.app

import android.content.Context
import com.carplay.adapter.PhoneProjectionAdapter
import com.carplay.adapter.generic.GenericMediaProjectionAdapter
import com.carplay.adapter.vivo.VivoProjectionAdapter
import com.carplay.adapter.xiaomi.XiaomiProjectionAdapter

object ProjectionAdapterRegistry {
    /**
     * 根据当前运行手机的品牌和系统环境，自动选择最适配的投屏内核
     */
    fun getBestAdapter(context: Context): PhoneProjectionAdapter {
        val vivo = VivoProjectionAdapter()
        if (vivo.isSupported(context)) return vivo

        val xiaomi = XiaomiProjectionAdapter()
        if (xiaomi.isSupported(context)) return xiaomi

        return GenericMediaProjectionAdapter()
    }
}
