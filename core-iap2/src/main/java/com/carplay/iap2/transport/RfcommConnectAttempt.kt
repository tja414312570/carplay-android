package com.carplay.iap2.transport

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Owns cancellation of one blocking secure connect; an expired socket can never be adopted. */
class RfcommConnectAttempt(private val abort: () -> Unit) : AutoCloseable {
    enum class State { PENDING, CONNECTED, FAILED, TIMED_OUT, CANCELLED }
    private val state = AtomicReference(State.PENDING)

    fun connect(connect: () -> Unit, scheduleTimeout: (() -> Unit) -> AutoCloseable) {
        var deadline: AutoCloseable? = null
        try {
            ensurePending()
            deadline = scheduleTimeout(::expire)
            ensurePending()
            connect()
            if (!state.compareAndSet(State.PENDING, State.CONNECTED)) ensurePending()
        } catch (failure: Exception) {
            if (state.compareAndSet(State.PENDING, State.FAILED)) {
                try { abort() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            if (state.get() == State.TIMED_OUT && failure !is SocketTimeoutException) {
                throw SocketTimeoutException("主动 RFCOMM 连接超时").apply { initCause(failure) }
            }
            if (state.get() == State.CANCELLED && failure !is CancellationException) {
                throw CancellationException("主动 RFCOMM 连接已取消").apply { initCause(failure) }
            }
            throw failure
        } finally { deadline?.close() }
    }

    private fun ensurePending() {
        when (state.get()) {
            State.PENDING -> Unit
            State.TIMED_OUT -> throw SocketTimeoutException("主动 RFCOMM 连接超时")
            State.CANCELLED -> throw CancellationException("主动 RFCOMM 连接已取消")
            else -> error("RFCOMM 连接不能重复执行")
        }
    }
    private fun expire() { if (state.compareAndSet(State.PENDING, State.TIMED_OUT)) abort() }
    override fun close() { if (state.compareAndSet(State.PENDING, State.CANCELLED)) abort() }
}
