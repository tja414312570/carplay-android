package com.carplay.adapter.vivo

import android.content.Context
import android.os.Build
import android.util.Log
import com.carplay.adapter.PhoneProjectionAdapter

/**
 * Vivo / iQOO 手机专用适配器 (基于 Jovi InCar / 蓝厂车联底层)。
 * 对接 OriginOS 的后台投屏通道与触控分发服务。
 */
class VivoProjectionAdapter : PhoneProjectionAdapter {
    companion object {
        private const val TAG = "VivoProjectionAdapter"
    }

    override val name: String = "Vivo Jovi InCar Adapter"

    override fun isSupported(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")
    }

    override suspend fun prepare(context: Context, width: Int, height: Int, fps: Int): Boolean {
        Log.i(TAG, "Preparing Vivo projection pipe: ${width}x${height}@${fps}fps")
        // 初始化 Vivo 车联后台 VirtualDisplay 或与 Jovi InCar 服务的 AIDL 绑定
        return true
    }

    override suspend fun startProjection(onFrameEncoded: (data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) -> Unit) {
        Log.i(TAG, "Starting Vivo projection video stream capture")
        // 挂载 MediaCodec / Surface 捕获 Vivo 独立虚拟屏输出流，输出 H.264 NALU 单元
    }

    override fun onTouchEvent(action: Int, x: Float, y: Float, pointerId: Int) {
        Log.d(TAG, "Vivo dispatch touch: action=$action x=$x y=$y pointerId=$pointerId")
        // 将车机触控映射回 Vivo 系统虚拟屏的 InputManager 注入
    }

    override fun onKeyEvent(keyCode: Int, isDown: Boolean) {
        Log.d(TAG, "Vivo dispatch key: keyCode=$keyCode isDown=$isDown")
    }

    override suspend fun stopProjection() {
        Log.i(TAG, "Stopping Vivo projection")
    }
}
