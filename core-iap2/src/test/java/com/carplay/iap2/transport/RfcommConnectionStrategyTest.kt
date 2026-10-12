package com.carplay.iap2.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class RfcommConnectionStrategyTest {
    @Test fun phoneServiceIsRegisteredBeforeSuccessfulOutgoingDialWithoutWaitingForACarToSelectThePhone() {
        val events = mutableListOf<String>()
        val socket = Any()
        val result = RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.PHONE_INITIATED,
            { events += "register" }, { events += "outgoing"; socket },
            { fail("No inbound wait after outgoing success"); Any() }, { false },
            { fail("Registration succeeded") }, { fail("Dial succeeded") })
        assertSame(socket, result)
        assertEquals(listOf("register", "outgoing"), events)
    }

    @Test fun outgoingFailureUsesTheAlreadyPreparedServiceAndKeepsItsFailureCause() {
        val events = mutableListOf<String>()
        val failure = IOException("outgoing transport failed")
        val incoming = Any()
        val result = RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.PHONE_INITIATED,
            { events += "register" }, { events += "outgoing"; throw failure },
            { events += "accept"; incoming }, { false }, { fail("Registration succeeded") },
            { assertSame(failure, it); events += "fallback" })
        assertSame(incoming, result)
        assertEquals(listOf("register", "outgoing", "fallback", "accept"), events)
    }

    @Test fun registrationIoFailureIsVisibleButDoesNotSuppressThePhoneInitiatedAttempt() {
        val events = mutableListOf<String>()
        val failure = IOException("SDP registration failed")
        val socket = Any()
        val result = RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.PHONE_INITIATED,
            { events += "register"; throw failure }, { events += "outgoing"; socket },
            { fail("No incoming fallback"); Any() }, { false },
            { assertSame(failure, it); events += "registration-error" }, { fail("Dial succeeded") })
        assertSame(socket, result)
        assertEquals(listOf("register", "registration-error", "outgoing"), events)
    }

    @Test fun cancelledOrPermissionDeniedRegistrationCannotDialAnotherTransport() {
        for (preCancelled in listOf(false, true)) {
            var cancelled = preCancelled
            assertThrows(CancellationException::class.java) {
                RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.PHONE_INITIATED,
                    { cancelled = true }, { fail("No dial after cancellation"); Any() },
                    { fail("No inbound after cancellation"); Any() }, { cancelled }, {}, {})
            }
        }
        assertThrows(SecurityException::class.java) {
            RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.PHONE_INITIATED,
                { throw SecurityException("permission revoked") }, { fail("No dial after permission error"); Any() },
                { fail("No inbound after permission error"); Any() }, { false }, {}, {})
        }
    }

    @Test fun explicitVehicleModeKeepsRegistrationFailureAndDoesNotDialTheCar() {
        val failure = IOException("registration unavailable")
        assertSame(failure, assertThrows(IOException::class.java) {
            RfcommConnectionStrategy.connectWithPhoneService(RfcommConnectionDirection.VEHICLE_INITIATED,
                { throw failure }, { fail("Vehicle-only mode"); Any() },
                { fail("No unregistered accept"); Any() }, { false }, { fail("Must fail directly") }, {})
        })
    }

    @Test fun vehicleDirectionUsesThePhoneServiceEvenWhenOutboundWouldSucceed() {
        val events = mutableListOf<String>()
        val accepted = Any()
        val result = RfcommConnectionStrategy.connect(RfcommConnectionDirection.VEHICLE_INITIATED,
            { events += "outbound"; Any() }, { events += "register-and-accept"; accepted }, { false },
            { fail("No automatic direction change") })
        assertSame(accepted,result)
        assertEquals(listOf("register-and-accept"),events)
    }

    @Test fun vehicleDirectionKeepsIncomingFailureInsteadOfSilentlyDialingTheCar() {
        for (failure in listOf(IOException("accept failed"),SecurityException("permission revoked"))) {
            var outbound = 0
            val returned = assertThrows(failure.javaClass) {
                RfcommConnectionStrategy.connect(RfcommConnectionDirection.VEHICLE_INITIATED,
                    { outbound++; Any() }, { throw failure }, { false }, { fail("No fallback") })
            }
            assertSame(failure,returned)
            assertEquals(0,outbound)
        }
    }

    @Test fun cancellationBeforeVehicleDirectionCannotRegisterAService() {
        var registrations = 0
        assertThrows(CancellationException::class.java) {
            RfcommConnectionStrategy.connect(RfcommConnectionDirection.VEHICLE_INITIATED,
                { fail("No outbound"); Any() }, { registrations++; Any() }, { true }, {})
        }
        assertEquals(0,registrations)
    }

    @Test fun successfulOutboundNeverRegistersPhoneListener() {
        val events = mutableListOf<String>()
        val socket = Any()
        val result = RfcommConnectionStrategy.connect(
            { events += "outbound"; socket },
            { events += "inbound"; Any() },
            { false },
            { events += "fallback" })
        assertSame(socket, result)
        assertEquals(listOf("outbound"), events)
    }

    @Test fun socketCreationOrConnectIOExceptionStartsListenerExactlyOnce() {
        val events = mutableListOf<String>()
        val failure = IOException("SDP unavailable")
        val inbound = Any()
        val result = RfcommConnectionStrategy.connect(
            { events += "outbound"; throw failure },
            { events += "inbound"; inbound },
            { false },
            { assertSame(failure, it); events += "fallback" })
        assertSame(inbound, result)
        assertEquals(listOf("outbound", "fallback", "inbound"), events)
    }

    @Test fun timeoutAllowsInboundFallback() {
        var listens = 0
        RfcommConnectionStrategy.connect(
            { throw SocketTimeoutException("20 second deadline") },
            { listens++ }, { false }, {})
        assertEquals(1, listens)
    }

    @Test fun securityFailureDoesNotChangeToAnotherTransport() {
        var listens = 0
        assertThrows(SecurityException::class.java) {
            RfcommConnectionStrategy.connect(
                { throw SecurityException("permission revoked") },
                { listens++ }, { false }, { fail("No fallback after a permission failure") })
        }
        assertEquals(0, listens)
    }

    @Test fun stoppedConnectionCannotRegisterListenerAfterSocketFailure() {
        var cancelled = false
        var listens = 0
        assertThrows(CancellationException::class.java) {
            RfcommConnectionStrategy.connect(
                { cancelled = true; throw IOException("closed") },
                { listens++ }, { cancelled }, { fail("No fallback after cancellation") })
        }
        assertEquals(0, listens)
    }

    @Test fun cancellationDuringFallbackDiagnosticCannotOpenListener() {
        var cancelled = false
        var listens = 0
        assertThrows(CancellationException::class.java) {
            RfcommConnectionStrategy.connect(
                { throw IOException("failed") },
                { listens++ }, { cancelled }, { cancelled = true })
        }
        assertEquals(0, listens)
    }

    @Test fun listenerFailureIsReturnedWithoutRetryingOutbound() {
        val failure = IOException("listener failed")
        var attempts = 0
        val result = assertThrows(IOException::class.java) {
            RfcommConnectionStrategy.connect(
                { attempts++; throw IOException("outbound failed") },
                { throw failure }, { false }, {})
        }
        assertSame(failure, result)
        assertEquals(1, attempts)
    }
}
