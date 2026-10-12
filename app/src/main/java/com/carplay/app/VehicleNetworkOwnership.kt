package com.carplay.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class NetworkLossSource { CAR_REQUEST, DEFAULT_NETWORK, CELLULAR_REQUEST }

/** Default-network notifications cannot replace or release the vehicle network lease. */
internal class VehicleNetworkOwnership {
    private val selected = AtomicReference<Long?>()
    private val ended = AtomicBoolean()
    val handle: Long? get() = selected.get()
    fun select(handle: Long): Boolean {
        if (ended.get()) return false
        return selected.compareAndSet(null,handle) && !ended.get()
    }
    fun lost(handle: Long, source: NetworkLossSource): Boolean = source == NetworkLossSource.CAR_REQUEST &&
        selected.get() == handle && ended.compareAndSet(false,true)
    fun replaced(handle: Long): Boolean = selected.get()?.let { it != handle && ended.compareAndSet(false,true) } ?: false
    fun close() { ended.set(true) }
}
