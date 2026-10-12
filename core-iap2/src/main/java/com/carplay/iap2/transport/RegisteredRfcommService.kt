package com.carplay.iap2.transport

/** Owns one live registration; acquiring it does not hold a lock while the caller accepts a socket. */
class RegisteredRfcommService<T : AutoCloseable>(private val register: () -> T) : AutoCloseable {
    private var service: T? = null
    private var closed = false

    @Synchronized fun acquire(): T {
        check(!closed) { "手机蓝牙服务已关闭" }
        return service ?: register().also { service = it }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val current = service
        service = null
        current?.close()
    }
}
