package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class AccessoryVersionInfoTest {
    @Test fun readsOnlyReportedModelAndVersionsWithoutInventingASdkOrLoggingUniqueIdentity() {
        val body = Iap2BodyReader.of(Iap2Messages.buildRaw(0x1d01) {
            string(0, "private-device-name"); string(1, "A2LL"); string(2, "General Motors")
            string(3, "private-serial"); string(4, "reported firmware build"); string(5, "SA8155-1.0")
            string(34, "private-product-plan")
        })
        val info = AccessoryVersionInfo.fromIdentification(body)
        assertEquals(AccessoryVersionInfo("A2LL", "General Motors", "reported firmware build", "SA8155-1.0"), info)
        val diagnostic = info.diagnostic()
        assertTrue(diagnostic.contains("reported firmware build"))
        assertFalse(diagnostic.contains("private-"))
        assertFalse(diagnostic.contains("SDK"))
    }

    @Test fun optionalMalformedMissingAndOversizedVersionFieldsRemainUnknown() {
        val body = Iap2BodyReader.of(Iap2Messages.buildRaw(0x1d01) {
            string(1, "A2LL"); bytes(4, byteArrayOf(1, 2)); string(5, "x".repeat(257))
        })
        assertEquals(AccessoryVersionInfo("A2LL", null, null, null), AccessoryVersionInfo.fromIdentification(body))
    }

    @Test fun diagnosticStringsAreBoundedAndCannotInjectLinesOrDeviceAddresses() {
        val info = AccessoryVersionInfo("model\n\"fake-log", "Vendor", "12:34:56:78:9A:BC " +
            "12345678-1234-4321-abcd-123456789012 " + "x".repeat(180), null)
        val line = info.diagnostic()
        assertFalse(line.contains('\n'))
        assertFalse(line.contains("12:34:56"))
        assertFalse(line.contains("12345678-1234"))
        assertTrue(line.contains("[address]"))
        assertTrue(line.contains("[identifier]"))
        assertTrue(line.length < 240)
    }
}
