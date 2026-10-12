package com.carplay.app

import com.carplay.iap2.controller.AccessoryVersionInfo
import com.carplay.iap2.controller.PhoneBluetoothRecovery

internal enum class WifiAuthentication { OPEN, WPA2, WPA3 }

internal object WirelessSecurity {
    fun select(protocolType: Int, version: AccessoryVersionInfo?, supportsSae: Boolean): WifiAuthentication {
        if (version != PhoneBluetoothRecovery.OBSERVED_FIRMWARE) return when (protocolType) {
            0 -> WifiAuthentication.OPEN
            2, 3 -> WifiAuthentication.WPA2
            4 -> WifiAuthentication.WPA3
            else -> error("不支持的无线安全类型")
        }
        return when (protocolType) {
            0 -> WifiAuthentication.OPEN
            2 -> WifiAuthentication.WPA2
            3 -> {
                check(supportsSae) { "车机要求 WPA3-SAE，当前手机不支持" }
                WifiAuthentication.WPA3
            }
            4 -> if (supportsSae) WifiAuthentication.WPA3 else WifiAuthentication.WPA2
            else -> error("GM 车机提供了不支持的无线安全类型")
        }
    }
}
