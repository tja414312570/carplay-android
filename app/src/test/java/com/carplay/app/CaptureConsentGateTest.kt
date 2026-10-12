package com.carplay.app

import org.junit.Assert.*
import org.junit.Test

class CaptureConsentGateTest {
    private val first = PendingCaptureStart("isolated-session-1","isolated-peer-1")
    private val second = PendingCaptureStart("isolated-session-2","isolated-peer-2")

    @Test fun consentResultRetainsThePeerAndSessionChosenBeforeTheDialog() {
        val gate = CaptureConsentGate()
        gate.open(first)
        assertEquals(first,gate.consume(first.sessionId,mayStart = true))
        assertNull(gate.pending)
    }

    @Test fun duplicateStartCannotReplaceAnOutstandingConsentRequest() {
        val gate = CaptureConsentGate()
        gate.open(first)
        assertThrows(IllegalStateException::class.java) { gate.open(second) }
        assertEquals(first,gate.pending)
    }

    @Test fun approvalOrCancellationCanConsumeTheRequestOnlyOnce() {
        val gate = CaptureConsentGate()
        gate.open(first)
        assertNotNull(gate.consume(first.sessionId,mayStart = true))
        assertNull(gate.consume(first.sessionId,mayStart = true))
    }

    @Test fun oldConsentCannotLaunchANewerDiagnosticSession() {
        val gate = CaptureConsentGate()
        gate.open(first)
        assertNull(gate.consume(second.sessionId,mayStart = true))
        assertNull(gate.pending)
        gate.open(second)
        assertEquals(second,gate.consume(second.sessionId,mayStart = true))
    }

    @Test fun resultArrivingAfterStopOrHostStartupIsDiscarded() {
        val gate = CaptureConsentGate()
        gate.open(first)
        assertNull(gate.consume(first.sessionId,mayStart = false))
        assertNull(gate.pending)
    }

    @Test fun cleanupFromAnOldRequestCannotClearANewRequest() {
        val gate = CaptureConsentGate()
        gate.open(second)
        gate.clear(first.sessionId)
        assertEquals(second,gate.pending)
        gate.clear(second.sessionId)
        assertNull(gate.pending)
    }

    @Test fun activityRecreationRestoresOnlyPendingIdentifiersAndNotAnApproval() {
        val old = CaptureConsentGate().apply { open(first) }
        val restored = CaptureConsentGate().apply { open(checkNotNull(old.pending)) }
        assertEquals(first,restored.consume(first.sessionId,mayStart = true))
        assertNull(restored.consume(first.sessionId,mayStart = true))
    }

    @Test fun invalidIdentifiersNeverBecomeAnOutstandingRequest() {
        val gate = CaptureConsentGate()
        assertThrows(IllegalArgumentException::class.java) { gate.open(first.copy(peer = "")) }
        assertNull(gate.pending)
    }
}
