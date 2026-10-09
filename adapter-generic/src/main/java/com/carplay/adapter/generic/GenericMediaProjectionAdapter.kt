package com.carplay.adapter.generic

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.carplay.adapter.PhoneProjectionAdapter

/**
 * 通用 Android 适配器 (基于官方 MediaProjection 录屏 API)。
 * 适用于 OPPO、荣耀、一加、三星及其他未单独定制的 Android 10+ 机型。
 */
class GenericMediaProjectionAdapter : PhoneProjectionAdapter {
    companion object {
        private const val TAG = "GenericProjection"
    }

    override val name: String = "Generic Android MediaProjection Adapter"

    override fun isSupported(context: Context): Boolean = true

    override suspend fun prepare(context: Context, width: Int, height: Int, fps: Int): Boolean {
        Log.i(TAG, "Preparing generic MediaProjection: ${width}x${height}@${fps}fps")
        return true
    }

    override suspend fun startProjection(onFrameEncoded: (data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) -> Unit) {
        Log.i(TAG, "Starting generic screen capture via MediaProjection")
    }

    override fun onTouchEvent(action: Int, x: Float, y: Float, pointerId: Int) {
        Log.d(TAG, "Generic touch injection via AccessibilityService")
    }

    override fun onKeyEvent(keyCode: Int, isDown: Boolean) {
        Log.d(TAG, "Generic key event: $keyCode")
    }

    override suspend fun stopProjection() {
        Log.i(TAG, "Stopping generic projection")
    }
}
