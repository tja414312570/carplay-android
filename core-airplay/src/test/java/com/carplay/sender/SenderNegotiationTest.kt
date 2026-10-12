package com.carplay.sender

import com.carplay.airplay.BplistCodec
import org.junit.Assert.*
import org.junit.Test
import java.net.ProtocolException

class SenderNegotiationTest {
    @Test fun absentOrUnacceptedStreamRetainsOnlyAlreadyAuthenticatedBluetooth() {
        for (reply in listOf(emptyMap<String,Any>(),mapOf("streams" to emptyList<Any>()),mapOf("streams" to listOf(mapOf("type" to 110))))) {
            assertEquals(SenderControlKind.AUTHENTICATED_BLUETOOTH,SenderNegotiation.control(reply).kind)
        }
    }
    @Test fun anExplicitlyAcceptedNoPortStreamKeepsCommandAuthentication() {
        assertEquals(SenderControlKind.WIFI_COMMAND,SenderNegotiation.control(mapOf("streams" to listOf(mapOf("type" to 130)))).kind)
        assertEquals(SenderControlKind.WIFI_COMMAND,SenderNegotiation.control(mapOf("streams" to listOf(mapOf("type" to 130,"dataPort" to 0)))).kind)
    }
    @Test fun acceptedDedicatedPortUsesTheRealNegotiatedValue() {
        assertEquals(SenderControlTransport(SenderControlKind.WIFI_DATASTREAM,49123),
            SenderNegotiation.control(mapOf("streams" to listOf(mapOf("type" to 130,"dataPort" to 49123)))))
    }
    @Test fun malformedAcceptedStreamsCannotBeDowngradedToBluetooth() {
        for (port in listOf<Any>(-1,65536,"49123",1.5)) {
            assertThrows(ProtocolException::class.java) {
                SenderNegotiation.control(mapOf("streams" to listOf(mapOf("type" to 130,"dataPort" to port))))
            }
        }
        assertThrows(ProtocolException::class.java) { SenderNegotiation.control(mapOf("streams" to "invalid")) }
        assertThrows(ProtocolException::class.java) { SenderNegotiation.control(mapOf("streams" to listOf(mapOf("type" to 130),mapOf("type" to 130)))) }
    }
    @Test fun alternateMediaStreamAnd44100AreNegotiatedFromPlaybackCapabilities() {
        val format = SenderNegotiation.audio(mapOf("audioFormats" to listOf(
            mapOf("type" to 100,"audioType" to "media","audioOutputFormats" to 0xc000),
            mapOf("type" to 102,"audioType" to "media","audioOutputFormats" to 0x400000))))
        assertEquals(SenderAudioFormat(102,44100,2,0x400000),format)
    }
    @Test fun preferred48000AndExistingMainStreamRemainCompatible() {
        val format = SenderNegotiation.audio(mapOf("audioFormats" to listOf(
            mapOf("type" to 102,"audioType" to "media","audioOutputFormats" to 0xc00000),
            mapOf("type" to 100,"audioOutputFormats" to 0x800000))))
        assertEquals(SenderAudioFormat(100,48000,2,0x800000),format)
    }
    @Test fun microphoneFormatsAndVoiceOnlyStreamsDoNotAuthorizeMediaPlayback() {
        assertThrows(ProtocolException::class.java) { SenderNegotiation.audio(mapOf("audioFormats" to listOf(
            mapOf("type" to 100,"audioType" to "media","audioInputFormats" to 0x800000,"audioOutputFormats" to 0xc000),
            mapOf("type" to 100,"audioType" to "telephony","audioOutputFormats" to 0x800000)))) }
    }
    @Test fun emptyCommandAcknowledgementDoesNotPretendToContainIapProof() {
        assertNull(IapCommandData.payload(BplistCodec.decode(BplistCodec.encode(emptyMap<String,Any>())),response = true))
    }
    @Test fun authenticatedCommandRepliesAndEventCommandsShareBinaryDataRouting() {
        val data = byteArrayOf(1,2,3)
        assertArrayEquals(data,IapCommandData.payload(mapOf("type" to "iAPSendMessage","params" to mapOf("data" to data))))
        assertArrayEquals(data,IapCommandData.payload(mapOf("params" to mapOf("data" to data)),response = true))
        assertArrayEquals(data,IapCommandData.payload(mapOf("data" to data),response = true))
        assertArrayEquals(data,IapCommandData.payload(data,response = true))
        assertNull(IapCommandData.payload(mapOf("type" to "changeModes","params" to mapOf("data" to data)),response = true))
    }
    @Test fun malformedAndOversizedIapDataFailsWithoutFabricatingBytes() {
        assertThrows(ProtocolException::class.java) { IapCommandData.payload(mapOf("params" to mapOf("data" to "invalid")),response = true) }
        assertThrows(ProtocolException::class.java) { IapCommandData.payload(ByteArray(1_048_577),response = true) }
    }
    @Test fun codecMicrosecondPtsReconstructsSampleClockAndWrapsRtpNaturally() {
        for (rate in listOf(44100,48000)) {
            val format = SenderAudioFormat(102,rate,2,if (rate == 48000) 0x800000 else 0x400000)
            for (samples in listOf(1024L,2048L,4096L,0x1_0000_0000L + 1024)) {
                assertEquals(samples.toInt(),format.rtpTimestamp(samples * 1_000_000 / rate))
            }
        }
    }
}
