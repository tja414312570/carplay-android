package com.carplay.sender

import com.carplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class SenderProtocolTest {
    @Test fun controllerPairsAndVerifiesAgainstOriginalAccessoryResponder() {
        val accessory = AirPlayIdentity.generate(); val store = PairingStore()
        val receiverSetup = PairSetup(accessory,store)
        val receiverVerify = PairVerify(accessory,store)
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity(keyPair.privateKey,keyPair.publicKey,UUID.randomUUID().toString())
        var saved: PairedAccessory? = null
        val phone = PhonePairing(identity,{ path,body -> if (path == "/pair-setup") receiverSetup.handle(body) else receiverVerify.handle(body) },{ saved = it })
        val peer = phone.setup("3939",accessory.publicKey)
        assertEquals(peer,saved); assertEquals(accessory.pairingId,peer.id)
        val shared = phone.verify(peer)
        assertArrayEquals(shared,receiverVerify.shared)
        assertTrue(receiverVerify.isVerified)
        assertEquals(identity.id,receiverVerify.verifiedControllerId)
    }
    @Test fun controllerRejectsChangedAdvertisedPairingKey() {
        val accessory = AirPlayIdentity.generate(); val receiver = PairSetup(accessory,PairingStore())
        val key = AirPlayCrypto.ed25519Generate()
        val phone = PhonePairing(ControllerIdentity(key.privateKey,key.publicKey,"controller"),{ _,body -> receiver.handle(body) },{ fail("Unverified pair must not persist") })
        assertThrows(IllegalStateException::class.java) { phone.setup("3939",ByteArray(32)) }
    }
    @Test fun videoPacketDecryptsUsingOriginalReceiverAndPreservesTimestamp() {
        val key = ByteArray(32) { it.toByte() }; val payload = byteArrayOf(0,0,0,2,0x65,0x42)
        val packet = VideoPackets.frame(payload,key,11,0x123456789L)
        val header = packet.copyOfRange(0,128)
        assertEquals(payload.size+16,ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(payload,ScreenCodec.decryptFrame(key,11,header,packet.copyOfRange(128,packet.size)))
        assertEquals(0x123456789L,ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(8))
        assertThrows(Exception::class.java) { ScreenCodec.decryptFrame(key,12,header,packet.copyOfRange(128,packet.size)) }
    }
    @Test fun queueRequiresRecoveryKeyframeAfterOverflow() {
        val queue = VideoQueue(2,20)
        assertFalse(queue.offer(EncodedVideo(byteArrayOf(1),0,false)))
        assertTrue(queue.offer(EncodedVideo(byteArrayOf(2),1,true)))
        assertTrue(queue.offer(EncodedVideo(byteArrayOf(3),2,false)))
        assertFalse(queue.offer(EncodedVideo(byteArrayOf(4),3,false)))
        assertTrue(queue.offer(EncodedVideo(byteArrayOf(5),4,true)))
        assertEquals(4,queue.dropped); assertEquals(4,queue.take()!!.ptsUs)
        queue.close(); assertNull(queue.take()); assertFalse(queue.offer(EncodedVideo(byteArrayOf(1),5,true)))
    }
    @Test fun controlFramingRetainsFragmentedAndCoalescedResponses() {
        val response = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nContent-Length: 3\r\n\r\nabc".toByteArray()
        val first = ControlFraming.parse(response.copyOfRange(0,response.size-1))
        assertTrue(first.messages.isEmpty())
        val complete = ControlFraming.parse(first.rest + response.last() + response)
        assertEquals(2,complete.messages.size); assertEquals(200,complete.messages.first().status)
        assertEquals("4",complete.messages.first().headers["cseq"]); assertTrue(complete.rest.isEmpty())
    }
    @Test fun invalidControlLengthsAndDuplicateHeadersAreRejected() {
        for (headers in listOf("Content-Length: -1", "Content-Length: 3000000", "Content-Length: 1\r\nContent-Length: 1")) {
            assertThrows(IllegalArgumentException::class.java) { ControlFraming.parse("RTSP/1.0 200 OK\r\n$headers\r\n\r\n".toByteArray()) }
        }
    }
    @Test fun resourceTransfersMatchReferenceAndRejectedBatchIsAtomic() {
        val state = SessionModes()
        fun change(type: Int) = state.change(mapOf("type" to "changeModes", "params" to mapOf("resources" to listOf(mapOf("resourceID" to 1, "transferType" to type)))))
        assertTrue(state.ownsScreen()); assertFalse(state.ownsAudio())
        change(3); assertFalse(state.ownsScreen()); change(4); assertTrue(state.ownsScreen())
        change(1); change(3); change(4); assertFalse(state.ownsScreen()); change(2); assertTrue(state.ownsScreen())
        assertThrows(IllegalArgumentException::class.java) { state.change(mapOf("type" to "changeModes", "params" to mapOf("resources" to listOf(mapOf("resourceID" to 1,"transferType" to 1),mapOf("resourceID" to 999,"transferType" to 1))))) }
        assertTrue(state.ownsScreen())
    }
    @Test fun missingTrustRootsCannotAuthenticate() {
        assertThrows(IllegalStateException::class.java) { AccessoryTrust(emptyList()).validate(byteArrayOf(1)) }
    }
}
