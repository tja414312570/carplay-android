package com.carplay.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ComponentPreviewSessionTest {
    @Test fun onlyTheComponentFlavorCanRestoreVerificationMode() {
        assertEquals(ComponentMode.CARPLAY, ComponentMode.restore(null, true))
        assertEquals(ComponentMode.CARPLAY, ComponentMode.restore("corrupt", true))
        assertEquals(ComponentMode.VERIFY_COMPONENT, ComponentMode.restore("VERIFY_COMPONENT", true))
        assertEquals(ComponentMode.CARPLAY, ComponentMode.restore("VERIFY_COMPONENT", false))
    }
    @Test fun carAndPreviewCannotOwnCaptureConcurrently() {
        ComponentMode.entries.forEach { first ->
            val ownership = ProjectionSessionOwnership()
            val lease = ownership.claim(first)
            ComponentMode.entries.forEach { next ->
                assertThrows(IllegalStateException::class.java) { ownership.claim(next) }
            }
            assertTrue(ownership.isBusy())
            ownership.release(lease)
            val next = ownership.claim(ComponentMode.VERIFY_COMPONENT)
            ownership.release(lease) // A late old cleanup must not release the new session.
            assertTrue(ownership.isBusy())
            ownership.release(next)
            assertFalse(ownership.isBusy())
        }
    }
    @Test fun hostAndSurfaceMustBothArriveBeforeCaptureInEitherOrder() {
        listOf(true, false).forEach { hostFirst ->
            val session = ComponentPreviewSession()
            assertFalse(session.takeStart())
            if (hostFirst) session.hostArrived("real-host") else session.surfaceArrived()
            assertFalse(session.takeStart())
            if (hostFirst) session.surfaceArrived() else session.hostArrived("real-host")
            assertTrue(session.takeStart())
            assertEquals(ComponentPreviewSession.Phase.BINDING, session.phase)
            assertFalse(session.takeStart())
        }
    }
    @Test fun stoppingCaptureHoldsOwnershipUntilAllNativeCleanupCompletes() {
        val ownership = ProjectionSessionOwnership()
        val lease = ownership.claim(ComponentMode.CARPLAY)
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                ownership.finish(lease) { closing.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }
            check(closing.await(3, TimeUnit.SECONDS))
            val second = pool.submit { ownership.finish(lease) {} }
            assertTrue(ownership.isBusy())
            assertThrows(IllegalStateException::class.java) { ownership.claim(ComponentMode.VERIFY_COMPONENT) }
            release.countDown()
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS)
            assertFalse(ownership.isBusy())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun duplicatedHostInvocationsDoNotRebindOrRestart() {
        val session = started()
        assertFalse(session.hostArrived("real-host"))
        assertFalse(session.surfaceArrived())
        assertFalse(session.takeStart())
        assertThrows(IllegalStateException::class.java) { session.hostArrived("another-host") }
    }
    @Test fun interfaceAndDisplayReadinessAloneDoNotReportPictureSuccess() {
        val session = started()
        assertTrue(session.hostReady())
        assertEquals(ComponentPreviewSession.Phase.WAITING_FRAME, session.phase)
        assertEquals(0L, session.frames)
        assertNull(session.firstFrameNanos)
        assertFalse(session.hostReady())
    }
    @Test fun previewRequiresARealNewBufferAndPreservesTheFirstFrameTime() {
        val session = started()
        session.hostReady()
        assertFalse(session.frame(0, 100))
        assertTrue(session.frame(10, 200))
        assertEquals(ComponentPreviewSession.Phase.PREVIEWING, session.phase)
        assertFalse(session.frame(10, 300))
        assertFalse(session.frame(9, 400))
        assertTrue(session.frame(11, 500))
        assertEquals(2L, session.frames)
        assertEquals(200L, session.firstFrameNanos)
    }
    @Test fun buffersBeforeHostReadinessAreMeasuredWithoutPrematureSuccess() {
        val session = started()
        assertTrue(session.frame(5, 100))
        assertEquals(ComponentPreviewSession.Phase.BINDING, session.phase)
        assertTrue(session.hostReady())
        assertEquals(ComponentPreviewSession.Phase.PREVIEWING, session.phase)
        assertEquals(1L, session.frames)
    }
    @Test fun anUnstartedWindowCannotSupplyCaptureEvidence() {
        val session = ComponentPreviewSession()
        session.surfaceArrived()
        assertFalse(session.frame(10, 100))
        assertEquals(0L, session.frames)
        assertFalse(session.hostReady())
    }
    @Test fun stoppingWhileWaitingPreventsLateHostOrSurfaceFromStarting() {
        val session = ComponentPreviewSession()
        assertTrue(session.stop())
        assertFalse(session.hostArrived("late-host"))
        assertFalse(session.surfaceArrived())
        assertFalse(session.takeStart())
        assertFalse(session.stop())
    }
    @Test fun stoppingDuringBindingIgnoresLateDisplayAndFrames() {
        val session = started()
        session.stop()
        assertFalse(session.hostReady())
        assertFalse(session.frame(10, 100))
        assertEquals(ComponentPreviewSession.Phase.STOPPED, session.phase)
        assertEquals(0L, session.frames)
    }
    @Test fun nativeFailureRemainsFailedAcrossCleanupAndLateCallbacks() {
        val session = started()
        assertTrue(session.fail())
        assertFalse(session.stop())
        assertFalse(session.fail())
        assertFalse(session.hostReady())
        assertFalse(session.frame(10, 100))
        assertEquals(ComponentPreviewSession.Phase.FAILED, session.phase)
    }
    @Test fun stoppedSuccessRetainsMeasuredFramesButRejectsFurtherUpdates() {
        val session = started()
        session.hostReady(); session.frame(10, 200)
        session.stop()
        assertFalse(session.frame(11, 400))
        assertEquals(1L, session.frames)
        assertEquals(200L, session.firstFrameNanos)
    }
    private fun started() = ComponentPreviewSession().apply {
        hostArrived("real-host"); surfaceArrived(); check(takeStart())
    }
}
