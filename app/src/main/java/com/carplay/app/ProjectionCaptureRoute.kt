package com.carplay.app

import android.app.Activity

internal enum class ProjectionCaptureRoute(val label: String) {
    SYSTEM_CONSENT("系统授权录屏"),
    VIVO_HOST("vivo 宿主原生投屏"),
    WAIT_FOR_VIVO_HOST("等待 vivo 宿主原生投屏");

    val native: Boolean get() = this != SYSTEM_CONSENT

    fun acceptsStop(nativeHostOnly: Boolean): Boolean = !nativeHostOnly || native

    companion object {
        fun select(component: Boolean, hasScreenGrant: Boolean, captureResult: Int, hasNativeHost: Boolean): ProjectionCaptureRoute {
            if (hasScreenGrant) {
                require(captureResult == Activity.RESULT_OK) { "屏幕投射授权未通过，请重新授权" }
                return SYSTEM_CONSENT
            }
            check(component) { "请先授权屏幕投射" }
            return if (hasNativeHost) VIVO_HOST else WAIT_FOR_VIVO_HOST
        }
    }
}
