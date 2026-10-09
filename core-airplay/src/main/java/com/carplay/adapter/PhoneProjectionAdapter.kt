package com.carplay.adapter

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * 厂商/平台投屏适配器接口。
 * Vivo (Jovi InCar)、小米 (CarWith)、通用 Android (MediaProjection) 均实现此接口。
 */
interface PhoneProjectionAdapter {
    /** 适配器名称，例如 "Vivo Jovi InCar", "Xiaomi CarWith", "Generic Android" */
    val name: String

    /** 是否支持当前设备运行环境 */
    fun isSupported(context: Context): Boolean

    /** 准备投屏捕获通道 */
    suspend fun prepare(context: Context, width: Int, height: Int, fps: Int): Boolean

    /** 开始向 H.264 编码器/AirPlay 发送帧数据 */
    suspend fun startProjection(onFrameEncoded: (data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) -> Unit)

    /** 处理车机回传的触控事件 (CarPlay HID / Touch) */
    fun onTouchEvent(action: Int, x: Float, y: Float, pointerId: Int)

    /** 处理车机旋钮/按键事件 */
    fun onKeyEvent(keyCode: Int, isDown: Boolean)

    /** 停止并释放投屏通道 */
    suspend fun stopProjection()
}
