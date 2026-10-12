package com.carplay.app

import com.carplay.adapter.vivo.VivoHostCallPolicy
import com.carplay.adapter.vivo.VivoHostVerificationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ExecutionException

class VivoHostCallPolicyTest {
    @Test fun validAuthorizedCallsPreserveTheirRealReturnValue() {
        assertEquals(42,VivoHostCallPolicy.invoke("test") { 42 })
    }

    @Test fun componentVerifierRejectionIsFatalAndIsNeverRetried() {
        val original = UnsupportedOperationException("Not yet implemented")
        var calls = 0
        val failure = assertThrows(VivoHostVerificationException::class.java) {
            VivoHostCallPolicy.invoke("注册投屏回调") { calls++; throw original }
        }
        assertEquals(1,calls)
        assertSame(original,failure.cause)
        assertTrue(failure.message!!.contains("版本／签名校验"))
        assertEquals("注册投屏回调",failure.operation)
    }

    @Test fun unrelatedUnsupportedOperationsKeepTheirOriginalMeaning() {
        val original = UnsupportedOperationException("audio capability unavailable")
        assertSame(original,assertThrows(UnsupportedOperationException::class.java) {
            VivoHostCallPolicy.invoke("test") { throw original }
        })
    }

    @Test fun optionalCallsMustNotSwallowComponentAuthorizationFailure() {
        val diagnostics = mutableListOf<String>()
        var prepareCalled = false
        assertThrows(VivoHostVerificationException::class.java) {
            VivoHostCallPolicy.optional("注册音频",diagnostics::add) {
                throw UnsupportedOperationException("Not yet implemented")
            }
            prepareCalled = true
        }
        assertFalse(prepareCalled)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun ordinaryPermissionFailuresRemainFatalForOptionalCalls() {
        val original = SecurityException("isolated permission denial")
        assertSame(original,assertThrows(SecurityException::class.java) {
            VivoHostCallPolicy.optional("test", { fail("Authorization must not be downgraded") }) { throw original }
        })
    }

    @Test fun aMissingOptionalCapabilityIsReportedWithoutStoppingValidVideoSetup() {
        val diagnostics = mutableListOf<String>()
        VivoHostCallPolicy.optional("注册音频",diagnostics::add) { throw IOException("capability unavailable") }
        assertEquals(1,diagnostics.size)
        assertTrue(diagnostics.single().contains("capability unavailable"))
        assertEquals("prepared",VivoHostCallPolicy.invoke("prepareCast") { "prepared" })
    }

    @Test fun aFutureWrapperDoesNotHideTheHostVerificationPhaseFromPhoneLogs() {
        val rejection = VivoHostVerificationException("查询宿主投屏接口",UnsupportedOperationException("Not yet implemented"))
        assertEquals(rejection.message,VivoHostCallPolicy.failureMessage(ExecutionException(rejection)))
    }

    @Test fun otherFailuresRetainTheirExistingDiagnosticMessage() {
        assertEquals("isolated timeout",VivoHostCallPolicy.failureMessage(IOException("isolated timeout")))
    }
}
