package com.carplay.app

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test

class ProjectionCaptureRouteTest {
    @Test fun explicitSystemConsentTakesPriorityOverAnAvailableNativeHost() {
        val route = ProjectionCaptureRoute.select(true,true,Activity.RESULT_OK,true)
        assertEquals(ProjectionCaptureRoute.SYSTEM_CONSENT,route)
        assertFalse(route.native)
    }

    @Test fun componentCanUseSystemConsentWithoutAHostLaunch() {
        assertEquals(ProjectionCaptureRoute.SYSTEM_CONSENT,
            ProjectionCaptureRoute.select(true,true,Activity.RESULT_OK,false))
    }

    @Test fun componentDefaultsToItsRealHostWithoutSystemConsent() {
        assertEquals(ProjectionCaptureRoute.VIVO_HOST,
            ProjectionCaptureRoute.select(true,false,Activity.RESULT_CANCELED,true))
        assertEquals(ProjectionCaptureRoute.WAIT_FOR_VIVO_HOST,
            ProjectionCaptureRoute.select(true,false,Activity.RESULT_CANCELED,false))
    }

    @Test fun rejectedScreenConsentCannotFallBackToNativeAuthorization() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionCaptureRoute.select(true,true,Activity.RESULT_CANCELED,true)
        }
    }

    @Test fun standaloneStillRequiresScreenConsentEvenWhenHostFieldsArePresent() {
        assertThrows(IllegalStateException::class.java) {
            ProjectionCaptureRoute.select(false,false,Activity.RESULT_CANCELED,true)
        }
        assertEquals(ProjectionCaptureRoute.SYSTEM_CONSENT,
            ProjectionCaptureRoute.select(false,true,Activity.RESULT_OK,true))
    }

    @Test fun nativeHostTeardownCannotStopIndependentSystemCapture() {
        assertFalse(ProjectionCaptureRoute.SYSTEM_CONSENT.acceptsStop(nativeHostOnly = true))
        assertTrue(ProjectionCaptureRoute.VIVO_HOST.acceptsStop(nativeHostOnly = true))
        assertTrue(ProjectionCaptureRoute.WAIT_FOR_VIVO_HOST.acceptsStop(nativeHostOnly = true))
    }

    @Test fun userStopRemainsEffectiveForEveryCaptureSource() {
        ProjectionCaptureRoute.entries.forEach { assertTrue(it.acceptsStop(nativeHostOnly = false)) }
    }
}
