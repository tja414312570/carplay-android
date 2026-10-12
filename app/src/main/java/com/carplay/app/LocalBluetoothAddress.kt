package com.carplay.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.carplay.iap2.controller.BluetoothTransportIdentity

data class LocalBluetoothAddress(val value: String, val source: String)

object LocalBluetoothAddressReader {
    @SuppressLint("HardwareIds", "MissingPermission")
    fun read(context: Context, adapter: BluetoothAdapter, configured: String?): LocalBluetoothAddress? {
        val canReadAdapter = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (canReadAdapter) {
            val address = try { adapter.address } catch (_: SecurityException) { null }
            BluetoothTransportIdentity.normalizedAddress(address)?.let { return LocalBluetoothAddress(it, "system-adapter") }
        }
        val secureAddress = try { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }
            catch (_: SecurityException) { null }
        BluetoothTransportIdentity.normalizedAddress(secureAddress)?.let { return LocalBluetoothAddress(it, "system-settings") }
        return BluetoothTransportIdentity.normalizedAddress(configured)?.let { LocalBluetoothAddress(it, "user-configured") }
    }
}
