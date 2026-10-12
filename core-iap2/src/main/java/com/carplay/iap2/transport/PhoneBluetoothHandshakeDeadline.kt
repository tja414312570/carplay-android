package com.carplay.iap2.transport

/** Detection and SYN have different budgets; both use elapsed monotonic time from link startup. */
class PhoneBluetoothHandshakeDeadline(private val profile: PhoneBluetoothProfile,
    private val clockNanos: () -> Long = System::nanoTime) {
    private val start = clockNanos()
    fun remainingMillis(status: Iap2HandshakeStatus): Long {
        val detecting = status.state == Iap2LinkEngine.State.IDLE || status.state == Iap2LinkEngine.State.DETECTING
        val budget = if (profile.accessoryInitiates && detecting) 12_000L else profile.readyTimeoutMillis
        val elapsed = (clockNanos() - start).coerceAtLeast(0) / 1_000_000
        return (budget - elapsed).coerceAtLeast(0)
    }
}
