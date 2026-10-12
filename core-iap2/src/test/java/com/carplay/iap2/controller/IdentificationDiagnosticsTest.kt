package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class IdentificationDiagnosticsTest {
    @Test fun reportsActualNumericComponentsAndPreservesRepeatedTransportGroups() {
        val frame = Iap2Messages.buildRaw(0x1d01) {
            group(16) { u16(0, 0); string(1, "private-usb-name"); void(2) }
            group(17) { u16(0, 17); string(1, "private-bt-name"); bytes(3,byteArrayOf(1,2,3,4,5,6)) }
            group(24) { u16(0, 77); u16(3, 321); string(1, "private-ssid-one"); void(2); void(4) }
            group(24) { u16(0, 65535); u16(3, 17); string(1, "private-ssid-two"); void(5) }
        }
        val detail = IdentificationDiagnostics.describe(Iap2BodyReader.of(frame))
        assertTrue(detail.contains("p16(component=0"))
        assertTrue(detail.contains("p17(component=17"))
        assertTrue(detail.contains("p24(component=77,transportId=321"))
        assertTrue(detail.contains("p24(component=65535,transportId=17"))
        for (secret in listOf("private-usb-name","private-bt-name","private-ssid-one","private-ssid-two")) {
            assertFalse(detail.contains(secret))
        }
        assertTrue(detail.contains("3:6B"))
    }

    @Test fun malformedOrMissingNumericFieldsAreReportedWithoutDefaultValues() {
        val frame = Iap2Messages.buildRaw(0x1d01) {
            bytes(17,byteArrayOf(1,2,3))
            group(24) { bytes(0,byteArrayOf(9)); u32(3,1234) }
        }
        val detail = IdentificationDiagnostics.describe(Iap2BodyReader.of(frame))
        assertTrue(detail.contains("p17(malformed,size=3B)"))
        assertTrue(detail.contains("p24(component=size=1B,transportId=size=4B"))
        val missing = IdentificationDiagnostics.describe(Iap2BodyReader.of(Iap2Messages.buildRaw(0x1d01) { group(24) {} }))
        assertTrue(missing.contains("component=missing,transportId=missing"))
    }

    @Test fun diagnosticOutputIsBoundedWithoutDroppingProtocolInput() {
        val frame = Iap2Messages.buildRaw(0x1d01) {
            repeat(20) { group(24) { u16(0,it); u16(3,it+100); string(1,"hidden-$it") } }
        }
        val reader = Iap2BodyReader.of(frame)
        val detail = IdentificationDiagnostics.describe(reader)
        assertTrue(detail.contains(";… total=20"))
        assertFalse(detail.contains("p24(component=8,"))
        assertFalse(detail.contains("hidden-"))
        assertEquals(20,reader.all(24).size)
        assertTrue(detail.length < 2000)
    }
}
