package com.carplay.iap2.transport

import java.io.IOException
import java.util.concurrent.CancellationException

enum class RfcommConnectionDirection { PHONE_INITIATED, VEHICLE_INITIATED }

/** The selected accessory is tried first. Only transport failure permits a phone-side listener. */
object RfcommConnectionStrategy {
    fun <T> connectWithPhoneService(direction: RfcommConnectionDirection, register: () -> Unit,
        outbound: () -> T, inbound: () -> T, cancelled: () -> Boolean,
        registrationFailure: (IOException) -> Unit, fallback: (IOException) -> Unit): T {
        if (cancelled()) throw CancellationException("蓝牙连接已取消")
        try { register() } catch (failure: IOException) {
            if (cancelled()) throw CancellationException("蓝牙连接已取消").apply { initCause(failure) }
            if (direction == RfcommConnectionDirection.VEHICLE_INITIATED) throw failure
            registrationFailure(failure)
        }
        if (cancelled()) throw CancellationException("蓝牙连接已取消")
        return connect(direction, outbound, inbound, cancelled, fallback)
    }

    fun <T> connect(direction: RfcommConnectionDirection, outbound: () -> T, inbound: () -> T,
        cancelled: () -> Boolean, fallback: (IOException) -> Unit): T {
        if (direction == RfcommConnectionDirection.PHONE_INITIATED) {
            return connect(outbound, inbound, cancelled, fallback)
        }
        if (cancelled()) throw CancellationException("蓝牙连接已取消")
        return inbound()
    }

    fun <T> connect(outbound: () -> T, inbound: () -> T, cancelled: () -> Boolean,
        fallback: (IOException) -> Unit): T {
        if (cancelled()) throw CancellationException("蓝牙连接已取消")
        return try {
            outbound()
        } catch (failure: IOException) {
            if (cancelled()) throw CancellationException("蓝牙连接已取消").apply { initCause(failure) }
            fallback(failure)
            if (cancelled()) throw CancellationException("蓝牙连接已取消")
            inbound()
        }
    }
}
