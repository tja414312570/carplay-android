package com.carplay.app

enum class ComponentMode(val label: String) {
    CARPLAY("CarPlay 模式"),
    VERIFY_COMPONENT("组件验证模式");

    companion object {
        fun restore(value: String?, component: Boolean): ComponentMode =
            if (component) entries.firstOrNull { it.name == value } ?: CARPLAY else CARPLAY
    }
}

/** Ownership extends through native cleanup, including a user stop during host preparation. */
internal class ProjectionSessionOwnership {
    class Lease internal constructor(val mode: ComponentMode)
    private var owner: Lease? = null
    @Synchronized fun isBusy() = owner != null
    @Synchronized fun claim(mode: ComponentMode): Lease {
        check(owner == null) { "上一投屏仍在清理，请稍后重新开始" }
        return Lease(mode).also { owner = it }
    }
    @Synchronized fun release(lease: Lease) {
        if (owner === lease) owner = null
    }
    fun finish(lease: Lease, cleanup: () -> Unit) {
        // WirelessConnection.close is idempotent; serialize its concurrent callers so
        // an early return from the second caller cannot release a still-closing host.
        synchronized(lease) { try { cleanup() } finally { release(lease) } }
    }
}

internal object ProjectionSessions {
    val ownership = ProjectionSessionOwnership()
}
