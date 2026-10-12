package com.carplay.app

import org.junit.Assert.*
import org.junit.Test

class VehicleNetworkOwnershipTest {
    @Test fun cellularAndDefaultLossCannotReleaseOrReplaceTheSelectedWifi() {
        val owner = VehicleNetworkOwnership()
        assertTrue(owner.select(101))
        assertFalse(owner.lost(202,NetworkLossSource.CELLULAR_REQUEST))
        assertFalse(owner.lost(101,NetworkLossSource.DEFAULT_NETWORK))
        assertFalse(owner.select(202))
        assertEquals(101L,owner.handle)
        assertTrue(owner.lost(101,NetworkLossSource.CAR_REQUEST))
    }
    @Test fun onlyTheActualSelectedWifiLossEndsItsLeaseAndOnlyOnce() {
        val owner = VehicleNetworkOwnership().apply { select(101) }
        assertFalse(owner.lost(202,NetworkLossSource.CAR_REQUEST))
        assertTrue(owner.lost(101,NetworkLossSource.CAR_REQUEST))
        assertFalse(owner.lost(101,NetworkLossSource.CAR_REQUEST))
        assertFalse(owner.select(303))
    }
    @Test fun cancellationPreventsLateCallbacksFromSelectingOrLosingANetwork() {
        val owner = VehicleNetworkOwnership()
        owner.close()
        assertFalse(owner.select(101))
        assertFalse(owner.lost(101,NetworkLossSource.CAR_REQUEST))
        assertNull(owner.handle)
    }
    @Test fun anUnselectedNetworkCannotCreateAFalseLoss() {
        assertFalse(VehicleNetworkOwnership().lost(101,NetworkLossSource.CAR_REQUEST))
    }
    @Test fun anActualWifiRequestReplacementRequiresANewSessionAndCannotRebindOldSockets() {
        val owner = VehicleNetworkOwnership()
        assertFalse(owner.replaced(101))
        assertTrue(owner.select(101))
        assertFalse(owner.replaced(101))
        assertTrue(owner.replaced(202))
        assertFalse(owner.replaced(202))
        assertFalse(owner.select(202))
        assertEquals(101L,owner.handle)
    }
}
