package com.carplay.sender
import com.carplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class HidInputTest {
    @Test fun descriptorBasedTouchDecoderMatchesOriginalReceiverEncoder() {
        val descriptor = AirPlayHid.touchHidDevice(1920,720,"main")["hidDescriptor"] as ByteArray
        val decoder = HidInput(descriptor)
        val report = AirPlayHid.touchReport(listOf(AirPlayContact(0,960.0,360.0,true),AirPlayContact(1,1920.0,720.0,false)))
        val samples = decoder.touches(report)
        assertEquals(2,samples.size); assertEquals(0.5f,samples[0].x,0f); assertEquals(0.5f,samples[0].y,0f)
        assertTrue(samples[0].down); assertFalse(samples[1].down)
        assertThrows(IllegalArgumentException::class.java) { decoder.touches(byteArrayOf(1)) }
    }
    @Test fun geometryAccountsForLetterboxingAndRejectsTouchesOutsideContent() {
        val geometry = CaptureGeometry(1080,2160,1920,720)
        val center = geometry.point(960f,360f)!!
        assertEquals(540f,center.first,0.01f); assertEquals(1080f,center.second,0.01f)
        assertNull(geometry.point(10f,360f))
    }
}
