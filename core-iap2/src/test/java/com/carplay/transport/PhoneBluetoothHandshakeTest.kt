package com.carplay.iap2.transport

import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.message.Iap2CarPlayMessages
import com.carplay.iap2.wire.Iap2CsmFramer
import com.carplay.iap2.session.Iap2Session
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneBluetoothHandshakeTest {
    private val marker = hex("ff 55 02 00 ee 10")
    private val responderPayload = hex("01 05 08 00 05 dc 00 49 1e 03 0a 00 02")
    private val vivoPayload = hex("01 05 08 00 05 dc 00 49 1e 03 01 00 02 02 01 02")
    private val capturePayload = hex("01 7f 08 00 07 d0 01 00 1e 01 01 00 02")

    @Test fun minimalWirelessAttributesUseTheNegotiatedControlSessionAndActualLinkChecksums() {
        val engine = vivoReady()
        engine.sendControl(Iap2CarPlayMessages.buildMinimalWirelessAvailability().encodedFrame(),10)
        assertArrayEquals(hex("ff 5a 00 19 40 64 de 01 0b 40 40 00 0f 43 00 00 09 00 01 00 05 00 00 01 1e"),engine.takeOutput())
        engine.feed(packet(0x40,222,100,0),11)
        engine.advanceTime(30_000)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun explicitWifiConfigurationRequestCannotOvertakeAvailabilityOrItsAcknowledgement() {
        val engine = vivoReady()
        val availability = Iap2CarPlayMessages.buildFlatWirelessAvailability("12:34:56:78:9A:BC").encodedFrame()
        val request = Iap2Messages.buildRaw(0x5702) {}.encodedFrame()
        engine.sendControl(availability,10)
        engine.sendControl(request,11)
        assertArrayEquals(packet(0x40,100,222,1,availability),engine.takeOutput())
        engine.feed(packet(0x40,222,101,0),12) // No acknowledgement for an unsent request.
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
        engine.feed(packet(0x40,222,100,0),20)
        assertArrayEquals(hex("ff 5a 00 10 40 65 de 01 13 40 40 00 06 57 02 21"),engine.takeOutput())
        engine.feed(packet(0x40,222,101,0),21)
        engine.advanceTime(30_000)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun recordedFlatAvailabilityLinkFrameAndAckReleaseMatchObservedWirePattern() {
        val engine = Iap2LinkEngine(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST.linkConfig())
        engine.start(false,0)
        engine.feed(packet(0x80,195,0,0,vivoPayload),1)
        engine.takeOutput()
        engine.feed(packet(0x40,195,99,0),2)
        engine.takeOutput()
        while (engine.pollEvent() != null) Unit
        val availability = Iap2CarPlayMessages.buildFlatWirelessAvailability("12:34:56:78:9A:BC").encodedFrame()
        engine.sendControl(availability,10)
        // Observed 43-byte frame; private MAC replaced, sequence normalized from 61 to 100,
        // header and payload checksums recomputed independently from the captured bytes.
        assertArrayEquals(hex("ff 5a 00 2b 40 64 c3 01 14 40 40 00 21 43 00 00 05 00 00 01 00 16 00 01 31 32 3a 33 34 3a 35 36 3a 37 38 3a 39 41 3a 42 43 00 3a"),
            engine.takeOutput())
        val next = Iap2Messages.buildRaw(0x4e09) { string(0,"CarPlay Android") }.encodedFrame()
        engine.sendControl(next,11)
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
        // Captured pure ACK, with acknowledgement normalized from 61 to 100.
        engine.feed(hex("ff 5a 00 09 40 c3 64 00 37"),99)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        assertArrayEquals(packet(0x40,101,195,1,next),engine.takeOutput())
        engine.feed(packet(0x40,195,101,0),100)
        engine.advanceTime(10_580)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun phoneReferenceKeepsOneOutstandingPacketWithoutReplacingThePeerWindow() {
        val engine = vivoReady()
        val payloads = List(7) { byteArrayOf(it.toByte()) }
        payloads.forEach { engine.sendControl(it, 10) }
        assertArrayEquals(packet(0x40,100,222,1,payloads[0]), engine.takeOutput())
        assertEquals(5, engine.peerSynchronization().maxOutgoing)
        for (index in 1 until payloads.size) {
            engine.feed(packet(0x40,222,99+index,0), 10L+index)
            assertArrayEquals(packet(0x40,100+index,222,1,payloads[index]),engine.takeOutput())
        }
        engine.feed(packet(0x40,222,106,0),20)
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun phoneSendLimitNeverExceedsTheActualPeerWindowAtTheBoundary() {
        val engine = vivoReady(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST.linkConfig().copy(maximumInFlightPackets = 8))
        val payloads = List(6) { byteArrayOf(it.toByte()) }
        payloads.forEach { engine.sendControl(it,10) }
        assertArrayEquals((0..4).fold(byteArrayOf()) { bytes,index -> bytes + packet(0x40,100+index,222,1,payloads[index]) },
            engine.takeOutput())
        engine.feed(packet(0x40,222,104,0),11)
        assertArrayEquals(packet(0x40,105,222,1,payloads[5]),engine.takeOutput())
    }

    @Test fun staleAndUnsentAcksCannotReleaseQueueOrSuppressRetransmission() {
        val engine = vivoReady()
        engine.sendControl(byteArrayOf(1),10)
        engine.sendControl(byteArrayOf(2),10)
        assertArrayEquals(packet(0x40,100,222,1,byteArrayOf(1)),engine.takeOutput())
        engine.feed(packet(0x40,222,101,0),11)
        engine.feed(packet(0x40,222,98,0),12)
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
        engine.advanceTime(1510)
        assertArrayEquals(packet(0x40,100,222,1,byteArrayOf(1)),engine.takeOutput())
        engine.feed(packet(0x40,222,100,0),1511)
        assertArrayEquals(packet(0x40,101,222,1,byteArrayOf(2)),engine.takeOutput())
        engine.feedEof()
        engine.advanceTime(30_000)
        assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun stopAndWaitAcknowledgementsRemainValidAcrossSequence255ToZero() {
        val engine = vivoReady()
        for (index in 0..157) {
            val sequence = (100+index) and 255
            val payload = byteArrayOf(index.toByte())
            engine.sendControl(payload,10L+index)
            assertArrayEquals(packet(0x40,sequence,222,1,payload),engine.takeOutput())
            engine.feed(packet(0x40,222,sequence,0),10L+index)
            assertArrayEquals(byteArrayOf(),engine.takeOutput())
            assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        }
    }

    @Test fun fragmentedDetectionRestartEndsOldPhoneLinkAndPendingWrites() {
        val engine = vivoReady()
        engine.sendControl(byteArrayOf(1),10)
        engine.sendControl(byteArrayOf(2),10)
        engine.takeOutput()
        engine.feed(marker.copyOfRange(0,2),11)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        engine.feed(marker.copyOfRange(2,6),12)
        assertEquals(Iap2LinkEngine.State.DEAD,engine.state())
        assertTrue((engine.pollEvent() as Iap2LinkEngine.Event.Dead).reason!!.contains("检测包"))
        engine.advanceTime(30_000)
        assertArrayEquals(byteArrayOf(),engine.takeOutput())
    }

    @Test fun markerInsideFragmentedControlPayloadCannotBeMistakenForRestart() {
        val engine = vivoReady()
        val frame = packet(0x40,223,99,1,marker)
        engine.feed(frame.copyOfRange(0,9),10)
        engine.feed(frame.copyOfRange(9,15),11)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
        engine.feed(frame.copyOfRange(15,16),12)
        assertArrayEquals(marker,(engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        assertEquals(Iap2LinkEngine.State.NORMAL,engine.state())
    }

    @Test fun detectionPreservesACoalescedNewSynAndOversizedInputCannotBecomeARecoverySignal() {
        val nextSyn = packet(0x80, 51, 0, 0, vivoPayload)
        val engine = vivoReady()
        engine.feed(marker.copyOfRange(0, 3), 10)
        engine.feed(marker.copyOfRange(3, 6) + nextSyn, 11)
        val ended = engine.pollEvent() as Iap2LinkEngine.Event.Dead
        assertArrayEquals(marker + nextSyn, ended.restartInput)
        assertArrayEquals(byteArrayOf(), engine.takeOutput())

        val oversized = vivoReady()
        oversized.feed(marker + ByteArray(Iap2LinkEngine.MAX_WIRE_FRAME_BYTES), 10)
        val rejected = oversized.pollEvent() as Iap2LinkEngine.Event.Dead
        assertNull(rejected.restartInput)
        assertTrue(rejected.reason!!.contains("limit exceeded"))
    }

    @Test fun explicitResetNeverProducesAPeerDetectionRecoverySignal() {
        val engine = vivoReady()
        engine.feed(packet(0x10, 223, 99, 0), 10)
        val ended = engine.pollEvent() as Iap2LinkEngine.Event.Dead
        assertNull(ended.restartInput)
    }

    @Test fun newProtocolEpochKeepsOneTransportAndDoesNotReplayOldQueuedControlWrites() {
        val wire = QueueStream()
        val owner = PhoneBluetoothSessionTransport(wire)
        val first = Iap2Session.phoneBluetooth(owner.open(), PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST)
        var second: Iap2Session? = null
        try {
            wire.incoming.offer(marker)
            assertArrayEquals(marker, wire.output())
            wire.incoming.offer(packet(0x80, 222, 0, 0, vivoPayload))
            assertArrayEquals(packet(0xc0, 99, 222, 0, vivoPayload), wire.output())
            wire.incoming.offer(packet(0x40, 222, 99, 0))
            assertTrue(first.awaitPhoneBluetoothReady(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST, 2000))
            val identify = Iap2Messages.buildRaw(0x1d00) {}
            first.send(identify)
            assertArrayEquals(packet(0x40, 100, 222, 1, identify.encodedFrame()), wire.output())
            first.send(Iap2Messages.buildRaw(0x4e09) { string(0, "old queued name") })

            val nextSyn = packet(0x80, 51, 0, 0, vivoPayload)
            wire.incoming.offer(marker + nextSyn)
            val signal = assertThrows(Iap2PeerDetectionRestart::class.java) { first.recv(2000) }
            first.close() // recv has already terminated and joined the old link worker
            assertEquals(0, wire.closes.get())
            assertTrue(wire.outgoing.isEmpty())

            second = Iap2Session.phoneBluetooth(owner.open(signal.initialInput()), PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST)
            assertArrayEquals(marker + packet(0xc0, 99, 51, 0, vivoPayload), wire.output())
            wire.incoming.offer(packet(0x40, 51, 99, 0))
            assertTrue(second.awaitPhoneBluetoothReady(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST, 2000))
            second.send(identify)
            assertArrayEquals(packet(0x40, 100, 51, 1, identify.encodedFrame()), wire.output())
            assertTrue(wire.outgoing.isEmpty())
        } finally {
            runCatching { first.close() }; second?.close(); owner.close()
        }
        assertEquals(1, wire.closes.get())
    }

    @Test fun callerBudgetCanStopResynchronizationBeforeTheProfilesLongerDeadline() {
        val wire = QueueStream()
        val session = Iap2Session.phoneBluetooth(wire, PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST)
        try {
            assertFalse(session.awaitPhoneBluetoothReady(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST, 0))
        } finally { session.close() }
        assertEquals(1, wire.closes.get())
    }

    @Test fun aPrimaryEndpointCanCancelAnOptionalIncomingHandshakeWithoutWaitingForItsTimeout() {
        val wire = QueueStream()
        val session = Iap2Session.phoneBluetooth(wire, PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST)
        try {
            assertFalse(session.awaitPhoneBluetoothReady(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST, 25000) { true })
            assertTrue(wire.outgoing.isEmpty())
        } finally { session.close() }
        assertEquals(1, wire.closes.get())
    }

    @Test fun vivoDetectionRetainsTwelveSecondAndOverallTwentyFiveSecondBudgets() {
        var now = 0L
        val deadline = PhoneBluetoothHandshakeDeadline(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST) { now }
        assertEquals(12_000L, deadline.remainingMillis(Iap2HandshakeStatus(state = Iap2LinkEngine.State.DETECTING)))
        now = 11_000_000_000
        assertEquals(14_000L, deadline.remainingMillis(Iap2HandshakeStatus(state = Iap2LinkEngine.State.NEGOTIATING)))
        now = 25_000_000_000
        assertEquals(0L, deadline.remainingMillis(Iap2HandshakeStatus(state = Iap2LinkEngine.State.NEGOTIATING)))
    }

    @Test fun vivoReferenceFullWireBytesUseControlOneAndFileTwo() {
        val engine = Iap2LinkEngine(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST.linkConfig())
        engine.start(false, 0)
        assertArrayEquals(byteArrayOf(), engine.takeOutput())
        marker.forEach { engine.feed(byteArrayOf(it), 1) }
        assertArrayEquals(marker, engine.takeOutput())
        engine.feed(packet(0x80, 92, 0, 0, vivoPayload), 2)
        // Full literal bytes derived independently from vivo v11's frame/control codecs.
        assertArrayEquals(hex("ff 5a 00 1a c0 63 5c 00 0e " +
            "01 05 08 00 05 dc 00 49 1e 03 01 00 02 02 01 02 9f"), engine.takeOutput())
        assertFalse(engine.writable())
        engine.feed(packet(0xc0, 93, 99, 0, vivoPayload), 3)
        assertArrayEquals(hex("ff 5a 00 09 40 63 5d 00 9e"), engine.takeOutput())
        assertTrue(engine.writable())
        assertEquals(1, engine.negotiatedControlSessionId())
        engine.sendControl(Iap2Messages.buildRaw(0x1d00) {}.encodedFrame(), 4)
        assertArrayEquals(hex("ff 5a 00 10 40 64 5d 01 95 40 40 00 06 1d 00 5d"), engine.takeOutput())
        engine.feed(packet(0x40, 93, 100, 0), 5)
        while (engine.pollEvent() != null) Unit
        val response = Iap2CsmFramer.encodeFrame(0x1d01, byteArrayOf())
        engine.feed(packet(0x40, 94, 100, 1, response), 6)
        assertArrayEquals(response, (engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
    }

    @Test fun vivoFileSessionProcessesSetupDataSuccessAndUnsupportedTypeCancellation() {
        val stream = QueueStream()
        val channel = Iap2LinkChannel.openPhoneBluetooth(stream, PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST)
        try {
            stream.incoming.offer(packet(0x80, 92, 0, 0, vivoPayload))
            assertArrayEquals(packet(0xc0, 99, 92, 0, vivoPayload), stream.output())
            stream.incoming.offer(packet(0xc0, 93, 99, 0, vivoPayload))
            assertTrue(channel.awaitReady(2000))
            assertArrayEquals(packet(0x40, 99, 93, 0), stream.output())

            val setup = hex("81 04 00 00 00 00 00 00 00 05 00 02")
            stream.incoming.offer(packet(0x40, 94, 99, 2, setup))
            assertArrayEquals(packet(0x40, 100, 94, 2, hex("81 01")), stream.output())
            assertNull(channel.recvControl(0))
            stream.incoming.offer(packet(0x40, 94, 100, 0))
            stream.incoming.offer(packet(0x40, 95, 100, 2, hex("81 c0 01 02 03 04 05")))
            assertArrayEquals(packet(0x40, 101, 95, 2, hex("81 05")), stream.output())

            stream.incoming.offer(packet(0x40, 95, 101, 0))
            val unsupported = setup.copyOf().apply { this[lastIndex] = 9 }
            stream.incoming.offer(packet(0x40, 96, 101, 2, unsupported))
            assertArrayEquals(packet(0x40, 102, 96, 2, hex("81 02")), stream.output())
        } finally { channel.close() }
        assertEquals(1, stream.closes.get())
    }

    @Test fun existingPhoneProfileKeepsFileSessionTwelve() {
        val stream = QueueStream()
        val channel = Iap2LinkChannel.openPhoneBluetooth(stream, PhoneBluetoothProfile.ACCESSORY_FIRST)
        try {
            stream.incoming.offer(packet(0x80, 11, 0, 0, peer(7)))
            assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), stream.output())
            stream.incoming.offer(packet(0x40, 11, 99, 0))
            assertTrue(channel.awaitReady(2000))
            stream.incoming.offer(packet(0x40, 12, 99, 12, hex("81 04 00 00 00 00 00 00 00 05 00 02")))
            assertArrayEquals(packet(0x40, 100, 12, 12, hex("81 01")), stream.output())
        } finally { channel.close() }
        assertEquals(1, stream.closes.get())
    }

    @Test fun accessoryDetectionHasTwelveSecondBudget() {
        var now = 0L
        val deadline = PhoneBluetoothHandshakeDeadline(PhoneBluetoothProfile.ACCESSORY_FIRST) { now }
        val detecting = Iap2HandshakeStatus(state = Iap2LinkEngine.State.DETECTING)
        assertEquals(12_000L, deadline.remainingMillis(detecting))
        now = 12_000_000_000
        assertEquals(0L, deadline.remainingMillis(detecting))
    }

    @Test fun detectionDoesNotResetOverallTwentyFiveSecondDeadline() {
        var now = 0L
        val deadline = PhoneBluetoothHandshakeDeadline(PhoneBluetoothProfile.ACCESSORY_FIRST) { now }
        now = 11_000_000_000
        val negotiating = Iap2HandshakeStatus(state = Iap2LinkEngine.State.NEGOTIATING)
        assertEquals(14_000L, deadline.remainingMillis(negotiating))
        now = 25_000_000_000
        assertEquals(0L, deadline.remainingMillis(negotiating))
    }

    @Test fun legacyControllerRetainsOriginalTenSecondBudget() {
        var now = 0L
        val deadline = PhoneBluetoothHandshakeDeadline(PhoneBluetoothProfile.LEGACY_CONTROLLER) { now }
        now = 9_500_000_000
        assertEquals(500L, deadline.remainingMillis(Iap2HandshakeStatus()))
        now = 10_000_000_000
        assertEquals(0L, deadline.remainingMillis(Iap2HandshakeStatus()))
    }

    @Test fun legacyInitialWireBytesRemainIdentical() {
        val engine = Iap2LinkEngine()
        engine.start(true, 0)
        assertArrayEquals(hex("ff 55 02 00 ee 10 ff 5a 00 1d 80 63 00 00 a7 " +
            "01 1e ff ff 0f a0 01 f4 04 03 0a 00 01 0b 02 01 0c 01 02 10"), engine.takeOutput())
    }

    @Test fun responderNeverInitiatesBeforeAccessoryDetection() {
        val engine = responder()
        engine.start(true, 0)
        engine.advanceTime(25_000)
        assertArrayEquals(byteArrayOf(), engine.takeOutput())
        assertEquals(Iap2LinkEngine.State.DETECTING, engine.state())
        assertNull(engine.validatedPeerSynchronization())
    }

    @Test fun fragmentedMarkerIsRepliedToExactlyOnceWithoutBareSyn() {
        val engine = responder()
        engine.start(false, 0)
        marker.forEach { engine.feed(byteArrayOf(it), 1) }
        assertArrayEquals(marker, engine.takeOutput())
        engine.feed(marker, 2)
        engine.advanceTime(25_000)
        assertArrayEquals(byteArrayOf(), engine.takeOutput())
        assertFalse(engine.writable())
    }

    @Test fun responderSendsReferenceSynAckAndWaitsForMatchingConfirmation() {
        val engine = responder()
        engine.start(false, 0)
        engine.feed(marker, 1)
        engine.takeOutput()
        packet(0x80, 11, 0, 0, peer(7)).forEach { engine.feed(byteArrayOf(it), 2) }
        assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), engine.takeOutput())
        assertFalse(engine.writable())
        engine.feed(packet(0x40, 11, 98, 0), 3)
        assertFalse(engine.writable())
        engine.feed(packet(0x40, 11, 99, 0), 4)
        assertTrue(engine.writable())
        assertEquals(7, engine.negotiatedControlSessionId())
    }

    @Test fun peerSynAckReceivesReferenceLastSequenceAckBeforeDistinctDataSequence() {
        val engine = responder()
        engine.start(false, 0)
        engine.feed(packet(0x80, 11, 0, 0, peer(1)), 1)
        assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), engine.takeOutput())
        engine.feed(packet(0xc0, 11, 99, 0, peer(1)), 2)
        assertTrue(engine.writable())
        assertArrayEquals(packet(0x40, 99, 11, 0), engine.takeOutput())
        val identification = hex("40 40 00 06 1d 00")
        engine.sendControl(identification, 3)
        assertArrayEquals(packet(0x40, 100, 11, 1, identification), engine.takeOutput())
    }

    @Test fun hardwareTraceSequencePatternKeepsAckAt99AndIdentificationAt100() {
        val engine = responder()
        val syn = Iap2LinkEngine.SynchronizationPayload(5,2048,1500,73,30,3,listOf(
            Iap2LinkEngine.SessionDescriptor(1,0,2), Iap2LinkEngine.SessionDescriptor(2,2,1))).encode()
        engine.start(false,0)
        engine.feed(marker,1)
        assertArrayEquals(marker,engine.takeOutput())
        engine.feed(packet(0x80,95,0,0,syn),2)
        assertArrayEquals(packet(0xc0,99,95,0,responderPayload),engine.takeOutput())
        engine.feed(packet(0xc0,96,99,0,syn),3)
        assertArrayEquals(packet(0x40,99,96,0),engine.takeOutput())
        assertTrue(engine.writable())
        val request = hex("40 40 00 06 1d 00")
        engine.sendControl(request,4)
        assertArrayEquals(packet(0x40,100,96,1,request),engine.takeOutput())
        engine.feed(packet(0x40,96,100,0),5)
        while (engine.pollEvent() != null) Unit
        val response = Iap2CsmFramer.encodeFrame(0x1d01,byteArrayOf())
        engine.feed(packet(0x40,97,100,1,response),6)
        assertArrayEquals(response,(engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
    }

    @Test fun synAckCannotAcknowledgeAPhoneSynThatWasNotSent() {
        val engine = responder()
        engine.start(false, 0)
        engine.feed(packet(0xc0, 11, 99, 0, peer(1)), 1)
        assertFalse(engine.writable())
        assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), engine.takeOutput())
        engine.feed(packet(0x40, 11, 99, 0), 2)
        assertTrue(engine.writable())
    }

    @Test fun duplicateSynAckDoesNotRewindDataAcknowledgementOrDuplicateControl() {
        val engine = responder()
        engine.start(false, 0)
        engine.feed(packet(0x80, 11, 0, 0, peer(1)), 1)
        engine.takeOutput()
        engine.feed(packet(0xc0, 11, 99, 0, peer(1)), 2)
        engine.takeOutput()
        while (engine.pollEvent() != null) Unit
        engine.sendControl(byteArrayOf(1), 3)
        engine.takeOutput()
        engine.feed(packet(0x40, 11, 100, 0), 4)
        engine.takeOutput()
        engine.feed(packet(0xc0, 11, 99, 0, peer(1)), 5)
        assertArrayEquals(packet(0x40, 100, 11, 0), engine.takeOutput())
        engine.advanceTime(10_000)
        assertArrayEquals(byteArrayOf(), engine.takeOutput())
        assertNull(engine.pollEvent())
    }

    @Test fun headerDiagnosticFailureCannotChangeFinalAckBytes() {
        val engine = Iap2LinkEngine(PhoneBluetoothProfile.ACCESSORY_FIRST.linkConfig()) {
            throw IllegalStateException("metadata unavailable")
        }
        engine.start(false, 0)
        engine.feed(packet(0x80, 11, 0, 0, peer(1)), 1)
        engine.takeOutput()
        engine.feed(packet(0xc0, 11, 99, 0, peer(1)), 2)
        assertArrayEquals(packet(0x40, 99, 11, 0), engine.takeOutput())
    }

    @Test fun controlFacadeFlushesFinalAckBeforeStartIdentificationAndReceivesIdentity() {
        val stream = QueueStream()
        val channel = Iap2CsmChannel.openPhoneBluetooth(stream, PhoneBluetoothProfile.ACCESSORY_FIRST)
        try {
            stream.incoming.offer(marker)
            assertArrayEquals(marker, stream.output())
            stream.incoming.offer(packet(0x80, 11, 0, 0, peer(1)))
            assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), stream.output())
            stream.incoming.offer(packet(0xc0, 11, 99, 0, peer(1)))
            assertTrue(channel.awaitReady(2000))
            assertArrayEquals(packet(0x40, 99, 11, 0), stream.output())
            channel.send(Iap2Messages.buildRaw(0x1d00) {})
            assertArrayEquals(packet(0x40, 100, 11, 1, hex("40 40 00 06 1d 00")), stream.output())
            stream.incoming.offer(packet(0x40, 12, 100, 1, Iap2CsmFramer.encodeFrame(0x1d01, byteArrayOf())))
            assertEquals(0x1d01, channel.recv(2000)!!.messageId)
        } finally { channel.close() }
        assertEquals(1, stream.closes.get())
    }

    @Test fun directSynWithoutMarkerIsAcceptedByBothPhoneProfiles() {
        for (profile in listOf(PhoneBluetoothProfile.ACCESSORY_FIRST, PhoneBluetoothProfile.CAPTURE_IMMEDIATE)) {
            val engine = Iap2LinkEngine(profile.linkConfig())
            engine.start(false, 0)
            engine.takeOutput()
            engine.feed(packet(0x80, 11, 0, 0, peer(7)), 1)
            val expected = if (profile == PhoneBluetoothProfile.ACCESSORY_FIRST) responderPayload else capturePayload
            assertArrayEquals(packet(0xc0, 99, 11, 0, expected), engine.takeOutput())
        }
    }

    @Test fun captureProfileWaitsForDetectionBeforeSendingReferenceSyn() {
        val engine = Iap2LinkEngine(PhoneBluetoothProfile.CAPTURE_IMMEDIATE.linkConfig())
        engine.start(true, 0)
        assertArrayEquals(marker, engine.takeOutput())
        engine.feed(marker, 1)
        assertArrayEquals(packet(0x80, 99, 0, 0, capturePayload), engine.takeOutput())
    }

    @Test fun badSynChecksumCannotOpenControlOrSendSynAck() {
        val engine = responder()
        engine.start(false, 0)
        val damaged = packet(0x80, 11, 0, 0, peer(7)).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        engine.feed(damaged, 1)
        assertNull(engine.validatedPeerSynchronization())
        assertFalse(engine.writable())
        assertArrayEquals(byteArrayOf(), engine.takeOutput())
    }

    @Test fun malformedNegotiationOrMissingControlSessionTerminates() {
        for (payload in listOf(peer(7) + byteArrayOf(0), peer(7).apply { this[12] = 1 })) {
            val engine = responder()
            engine.start(false, 0)
            engine.feed(packet(0x80, 11, 0, 0, payload), 1)
            assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
            assertFalse(engine.writable())
            assertTrue(engine.pollEvent() is Iap2LinkEngine.Event.Dead)
        }
    }

    @Test fun accessoryResetBeforeDetectionIsTerminal() {
        val engine = responder()
        engine.start(false, 0)
        engine.feed(packet(0x10, 11, 0, 0), 1)
        assertEquals(Iap2LinkEngine.State.DEAD, engine.state())
    }

    @Test fun linkChannelRoutesNegotiatedControlAndReportsActualCounters() {
        val stream = QueueStream()
        val started = LinkedBlockingQueue<String>()
        val channel = Iap2LinkChannel.openPhoneBluetooth(stream, PhoneBluetoothProfile.ACCESSORY_FIRST) { started.offer(it) }
        try {
            assertNotNull(started.poll(2, TimeUnit.SECONDS))
            assertNull(stream.outgoing.poll())
            stream.incoming.offer(marker)
            assertArrayEquals(marker, stream.output())
            val syn = packet(0x80, 11, 0, 0, peer(7))
            stream.incoming.offer(syn)
            assertArrayEquals(packet(0xc0, 99, 11, 0, responderPayload), stream.output())
            stream.incoming.offer(packet(0x40, 11, 99, 0))
            assertTrue(channel.awaitReady(2000))
            val status = channel.handshakeStatus()
            assertEquals((marker.size + syn.size + 9).toLong(), status.receivedBytes)
            assertEquals((marker.size + 23).toLong(), status.sentBytes)
            assertEquals(333 - 10, channel.peerMaxControlPayloadBytes())
            assertEquals(7, status.controlSessionId)
            assertEquals(9, status.peerSynchronization!!.maxOutgoing)
            while (stream.outgoing.poll() != null) Unit
            val payload = byteArrayOf(1, 2, 3)
            assertTrue(channel.sendControl(payload))
            val sent = stream.output()
            assertEquals(7, sent[7].toInt() and 255)
            stream.incoming.offer(packet(0x40, 12, sent[5].toInt() and 255, 7, payload))
            assertArrayEquals(payload, channel.recvControl(2000))
        } finally { channel.close() }
        assertEquals(1, stream.closes.get())
    }

    @Test fun throwingDiagnosticCallbackCannotChangeHandshakeBytes() {
        val stream = QueueStream()
        val channel = Iap2LinkChannel.openPhoneBluetooth(stream, PhoneBluetoothProfile.CAPTURE_IMMEDIATE) {
            throw IllegalStateException("diagnostic consumer failed")
        }
        try {
            assertArrayEquals(marker, stream.output())
            stream.incoming.offer(marker)
            assertArrayEquals(packet(0x80, 99, 0, 0, capturePayload), stream.output())
            stream.incoming.offer(packet(0xc0, 11, 99, 0, peer(7)))
            assertTrue(channel.awaitReady(2000))
        } finally { channel.close() }
        assertEquals(1, stream.closes.get())
    }

    private fun responder() = Iap2LinkEngine(PhoneBluetoothProfile.ACCESSORY_FIRST.linkConfig())
    private fun vivoReady(config: Iap2LinkConfig = PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST.linkConfig()): Iap2LinkEngine {
        val engine = Iap2LinkEngine(config)
        engine.start(false,0)
        engine.feed(packet(0x80,222,0,0,vivoPayload),1)
        engine.takeOutput()
        engine.feed(packet(0x40,222,99,0),2)
        assertTrue(engine.writable())
        engine.takeOutput()
        while (engine.pollEvent() != null) Unit
        return engine
    }
    private fun peer(control: Int) = Iap2LinkEngine.SynchronizationPayload(9, 333, 1900, 73, 6, 3,
        listOf(Iap2LinkEngine.SessionDescriptor(control, 0, 2))).encode()

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun packet(flags: Int, sequence: Int, ack: Int, session: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val size = 9 + if (payload.isEmpty()) 0 else payload.size + 1
        val header = byteArrayOf(0xff.toByte(), 0x5a, (size ushr 8).toByte(), size.toByte(),
            flags.toByte(), sequence.toByte(), ack.toByte(), session.toByte())
        val checked = header + checksum(header)
        return if (payload.isEmpty()) checked else checked + payload + checksum(payload)
    }
    private fun checksum(bytes: ByteArray) = (-bytes.sumOf { it.toInt() and 255 }).toByte()

    private class QueueStream : BlockingDuplexByteStream {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val closes = AtomicInteger()
        override fun send(data: ByteArray) { outgoing.offer(data.copyOf()) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { closes.incrementAndGet(); incoming.offer(byteArrayOf()) }
        fun output(): ByteArray = checkNotNull(outgoing.poll(2, TimeUnit.SECONDS)) { "protocol output not received" }
    }
}
