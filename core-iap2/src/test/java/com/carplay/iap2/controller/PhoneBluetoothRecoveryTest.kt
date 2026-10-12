package com.carplay.iap2.controller

import org.junit.Assert.*
import org.junit.Test

class PhoneBluetoothRecoveryTest {
    private val published = BootstrapStatus(true, true, true, false, false, true, true)
    private val ready = PhoneBootstrapProfile.VIVO_WIRELESS_READY

    @Test fun observedFirmwareCanUseOneRestartWithoutResettingTheThirtySecondBudget() {
        for (observed in listOf(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL, PhoneBluetoothRecovery.OBSERVED_FIRMWARE_E2LB)) {
            var now = 0L
            val recovery = PhoneBluetoothRecovery(30_000_000_000) { now }
            now = 2_700_000_000
            assertTrue(recovery.claim(observed, published, ready, false))
            assertEquals(27_300L, recovery.remainingMillis())
            now = 5_000_000_000
            assertFalse(recovery.claim(observed, published, ready, false))
            assertTrue(recovery.lastRejection!!.contains("一次"))
            assertEquals(25_000L, recovery.remainingMillis())
            now = 30_000_000_000
            assertEquals(0L, recovery.remainingMillis())
        }
    }

    @Test fun identityProofPublicationAndNoEndpointAreAllRequired() {
        for (status in listOf(published.copy(identified = false), published.copy(authenticated = false),
            published.copy(networkRequestQueued = false), published.copy(modernWirelessSupported = false),
            published.copy(endpointReceived = true))) {
            val recovery = PhoneBluetoothRecovery(30_000_000_000) { 0L }
            assertFalse(recovery.claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL, status, ready, false))
            assertFalse(recovery.claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_E2LB, status, ready, false))
        }
    }

    @Test fun unknownMissingAndDifferentFirmwareCannotGuessACompatibilityRoute() {
        val observed = PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL
        for (version in listOf(null, observed.copy(model = "Other"), observed.copy(manufacturer = "Other"),
            observed.copy(firmware = "aegean_orange_n-user 12 SQBP5-39 68 release-keys"),
            observed.copy(hardware = null))) {
            assertFalse(PhoneBluetoothRecovery(30_000_000_000) { 0L }.claim(version, published, ready, false))
        }
        val observedE2lb = PhoneBluetoothRecovery.OBSERVED_FIRMWARE_E2LB
        for (version in listOf(observedE2lb.copy(model = "Other"), observedE2lb.copy(manufacturer = "Other"),
            observedE2lb.copy(firmware = "aegean_n-user 12 SQBP5-39 84 release-keys"))) {
            assertFalse(PhoneBluetoothRecovery(30_000_000_000) { 0L }.claim(version, published, ready, false))
        }
    }

    @Test fun otherProfilesCancellationAndAnExpiredBudgetKeepTheirOriginalFailureBehavior() {
        for (profile in PhoneBootstrapProfile.entries.filter { it !in setOf(ready,PhoneBootstrapProfile.MODERN_WIRELESS) }) {
            assertFalse(PhoneBluetoothRecovery(30_000_000_000) { 0L }
                .claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL, published, profile, false))
        }
        assertFalse(PhoneBluetoothRecovery(30_000_000_000) { 0L }
            .claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL, published, ready, true))
        assertFalse(PhoneBluetoothRecovery(30_000_000_000) { 30_000_000_000 }
            .claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL, published, ready, false))
    }

    @Test fun modernFullWirelessUsesTheSameBoundedFirmwareRecovery() {
        val recovery = PhoneBluetoothRecovery(30_000_000_000) { 5_000_000_000 }
        assertTrue(recovery.claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL,published,PhoneBootstrapProfile.MODERN_WIRELESS,false))
        assertFalse(recovery.claim(PhoneBluetoothRecovery.OBSERVED_FIRMWARE_A2LL,published,PhoneBootstrapProfile.MODERN_WIRELESS,false))
        assertEquals(25_000L,recovery.remainingMillis())
    }
}
