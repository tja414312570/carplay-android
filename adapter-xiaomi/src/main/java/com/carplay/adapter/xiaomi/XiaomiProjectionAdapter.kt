package com.carplay.adapter.xiaomi

import android.content.Context
import android.os.Build
import android.util.Log
import com.carplay.adapter.PhoneProjectionAdapter

/**
 * 小米 / Redmi 手机专用适配器 (基于 Xiaomi CarWith 底层)。
 */
class XiaomiProjectionAdapter : PhoneProjectionAdapter {
    companion object {
        private const val TAG = "XiaomiProjectionAdapter"
    }

    override val name: String = "Xiaomi CarWith Adapter"

    override fun isSupported(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    override suspend fun prepare(context: Context, width: Int, height: Int, fps: Int): Boolean {
        Log.i(TAG, "Preparing Xiaomi CarWith projection pipe: ${width}x${height}@${fps}fps")
        return true
    }

    override suspend fun startProjection(onFrameEncoded: (data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) -> Unit) {
        Log.i(TAG, "Starting Xiaomi CarWith video capture")
    }

    override fun onTouchEvent(action: Int, x: Float, y: Float, pointerId: Int) {
        Log.d(TAG, "Xiaomi dispatch touch: action=$action x=$x y=$y")
    }

    override fun onKeyEvent(keyCode: Int, isDown: Boolean) {
        Log.d(TAG, "Xiaomi dispatch key: keyCode=$keyCode isDown=$isDown")
    }

    override suspend fun stopProjection() {
        Log.i(TAG, "Stopping Xiaomi CarWith projection")
    }
}
