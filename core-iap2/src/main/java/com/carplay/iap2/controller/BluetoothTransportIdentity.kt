package com.carplay.iap2.controller

import java.util.Locale

/** The phone's Bluetooth transport identity is distinct from its persistent device UUID. */
object BluetoothTransportIdentity {
    fun normalizedAddress(value: String?): String? {
        val address = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (!ADDRESS.matches(address) || address in RESERVED) return null
        return address
    }

    private val ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val RESERVED = setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")
}
