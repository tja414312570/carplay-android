package com.carplay.app

/** Actual host/surface/frame evidence, independent of every car transport and encoder. */
internal class ComponentPreviewSession {
    enum class Phase(val label: String) {
        WAITING("等待 Jovi 调用组件"), BINDING("正在验证宿主接口"),
        WAITING_FRAME("宿主显示已就绪，等待首帧"), PREVIEWING("正在预览组件画面"),
        FAILED("组件验证失败"), STOPPED("组件验证已停止")
    }
    @Volatile var phase = Phase.WAITING
        private set
    var frames = 0L
        private set
    var firstFrameNanos: Long? = null
        private set
    private var host: String? = null
    private var surface = false
    private var started = false
    private var lastTimestamp = 0L
    val terminal get() = phase == Phase.FAILED || phase == Phase.STOPPED

    fun hostArrived(identity: String): Boolean {
        if (terminal) return false
        require(identity.isNotBlank())
        host?.let { check(it == identity) { "宿主入口已变化，请停止后重新验证" }; return false }
        host = identity
        return true
    }
    fun surfaceArrived(): Boolean {
        if (terminal || surface) return false
        surface = true
        return true
    }
    fun takeStart(): Boolean {
        if (terminal || started || host == null || !surface) return false
        started = true
        phase = Phase.BINDING
        return true
    }
    fun hostReady(): Boolean {
        if (terminal || phase != Phase.BINDING) return false
        phase = if (frames > 0) Phase.PREVIEWING else Phase.WAITING_FRAME
        return true
    }
    fun frame(timestampNanos: Long, nowNanos: Long): Boolean {
        if (terminal || !started || timestampNanos <= lastTimestamp) return false
        lastTimestamp = timestampNanos
        frames++
        if (firstFrameNanos == null) firstFrameNanos = nowNanos
        if (phase == Phase.WAITING_FRAME) phase = Phase.PREVIEWING
        return true
    }
    fun fail(): Boolean {
        if (terminal) return false
        phase = Phase.FAILED
        return true
    }
    fun stop(): Boolean {
        if (terminal) return false
        phase = Phase.STOPPED
        return true
    }
}
