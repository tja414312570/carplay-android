package com.carplay.iap2.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class RfcommConnectAttemptTest {
    @Test fun successCancelsDeadlineWithoutClosingAdoptedSocket() {
        var timeout: (() -> Unit)? = null; var closed = 0; var cancelled = 0
        val attempt = RfcommConnectAttempt { closed++ }
        attempt.connect({}, { action -> timeout = action; AutoCloseable { cancelled++ } })
        timeout!!(); attempt.close()
        assertEquals(0,closed); assertEquals(1,cancelled)
    }
    @Test fun expiredConnectReturningSuccessIsRejectedAndClosedExactlyOnce() {
        var timeout: (() -> Unit)? = null; var closed = 0; var cancelled = 0
        val attempt = RfcommConnectAttempt { closed++ }
        assertThrows(SocketTimeoutException::class.java) {
            attempt.connect({ timeout!!() },{ action -> timeout = action; AutoCloseable { cancelled++ } })
        }
        timeout!!(); attempt.close()
        assertEquals(1,closed); assertEquals(1,cancelled)
    }
    @Test fun failedOutboundIsReleasedAndCannotFireDelayedAbortAgain() {
        var timeout: (() -> Unit)? = null; var closed = 0
        val attempt = RfcommConnectAttempt { closed++ }
        assertThrows(IOException::class.java) {
            attempt.connect({ throw IOException("SDP lookup failed") },{ action -> timeout = action; AutoCloseable {} })
        }
        timeout!!(); attempt.close(); assertEquals(1,closed)
    }
    @Test fun userCancellationWinsOverLateConnectSuccess() {
        var closed = 0; val attempt = RfcommConnectAttempt { closed++ }
        assertThrows(CancellationException::class.java) { attempt.connect({ attempt.close() },{ AutoCloseable {} }) }
        attempt.close(); assertEquals(1,closed)
    }
    @Test fun expiryBeforeConnectStartsIsReportedAsTimeoutAndNeverOpensSocket() {
        var closed = 0
        var connected = false
        var cancelled = 0
        val attempt = RfcommConnectAttempt { closed++ }
        assertThrows(SocketTimeoutException::class.java) {
            attempt.connect({ connected = true }) { action ->
                action()
                AutoCloseable { cancelled++ }
            }
        }
        assertFalse(connected)
        assertEquals(1, closed)
        assertEquals(1, cancelled)
    }
    @Test fun timedOutSocketExceptionRetainsTransportCause() {
        var timeout: (() -> Unit)? = null
        val transport = IOException("socket closed by deadline")
        val attempt = RfcommConnectAttempt {}
        val failure = assertThrows(SocketTimeoutException::class.java) {
            attempt.connect({ timeout!!(); throw transport }) { action ->
                timeout = action
                AutoCloseable {}
            }
        }
        assertSame(transport, failure.cause)
    }
    @Test fun cancelledSocketExceptionCannotTriggerTransportFallback() {
        val transport = IOException("socket closed by user")
        val attempt = RfcommConnectAttempt {}
        val failure = assertThrows(CancellationException::class.java) {
            attempt.connect({ attempt.close(); throw transport }) { AutoCloseable {} }
        }
        assertSame(transport, failure.cause)
    }
    @Test fun cleanupFailureDoesNotReplaceConnectFailure() {
        val transport = IOException("SDP failed")
        val cleanup = IOException("close failed")
        val attempt = RfcommConnectAttempt { throw cleanup }
        val failure = assertThrows(IOException::class.java) {
            attempt.connect({ throw transport }) { AutoCloseable {} }
        }
        assertSame(transport, failure)
        assertArrayEquals(arrayOf(cleanup), failure.suppressed)
    }
}
