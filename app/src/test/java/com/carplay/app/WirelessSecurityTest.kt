package com.carplay.app

import com.carplay.iap2.controller.PhoneBluetoothRecovery
import org.junit.Assert.*
import org.junit.Test

class WirelessSecurityTest {
    @Test fun observedGmValuesMatchTheActualHotspotConfigurationMapping() {
        val gm = PhoneBluetoothRecovery.OBSERVED_FIRMWARE
        assertEquals(WifiAuthentication.OPEN,WirelessSecurity.select(0,gm,true))
        assertEquals(WifiAuthentication.WPA2,WirelessSecurity.select(2,gm,true))
        assertEquals(WifiAuthentication.WPA3,WirelessSecurity.select(3,gm,true))
        assertEquals(WifiAuthentication.WPA3,WirelessSecurity.select(4,gm,true))
        assertEquals(WifiAuthentication.WPA2,WirelessSecurity.select(4,gm,false))
    }
    @Test fun saeOnlyCannotSilentlyDowngradeToPsk() {
        assertThrows(IllegalStateException::class.java) { WirelessSecurity.select(3,PhoneBluetoothRecovery.OBSERVED_FIRMWARE,false) }
    }
    @Test fun unrelatedPeersAndDifferentFirmwareRetainTheExistingMapping() {
        for (version in listOf(null,PhoneBluetoothRecovery.OBSERVED_FIRMWARE.copy(firmware = "different"))) {
            assertEquals(WifiAuthentication.WPA2,WirelessSecurity.select(3,version,true))
            assertEquals(WifiAuthentication.WPA3,WirelessSecurity.select(4,version,true))
        }
    }
}
