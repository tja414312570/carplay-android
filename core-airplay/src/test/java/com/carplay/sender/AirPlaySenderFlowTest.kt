package com.carplay.sender

import com.carplay.airplay.*
import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.controller.*
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.session.Iap2Session
import com.carplay.iap2.transport.BlockingDuplexByteStream
import com.carplay.iap2.wire.Iap2Frame
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.math.BigInteger
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolated sockets and fresh test identities; uses the original pairing, tunnel and video codecs. */
class AirPlaySenderFlowTest {
    @Test fun pairedFlowWaitsForIndependentWifiProofThenRecordsAndSendsDecryptableVideo() {
        Fixture().use { fixture ->
            val started = CompletableFuture.supplyAsync { fixture.sender.start(fixture.car.controlPort,false) }
            fixture.car.challengeSeen.get(8,TimeUnit.SECONDS)
            assertFalse(started.isDone)
            assertFalse(fixture.car.mediaStarted.get())
            assertFalse(fixture.car.recorded.get())
            assertEquals(0,fixture.displays.get())
            fixture.car.allowProof.countDown()
            val display = started.get(10,TimeUnit.SECONDS)
            assertEquals(SenderDisplay(480,320,30,"isolated-display"),display)
            assertTrue(fixture.controlReady.isDone)
            assertTrue(fixture.car.authenticated.get(5,TimeUnit.SECONDS))
            assertTrue(fixture.car.recorded.get())
            fixture.sender.config(VideoPackets.avcC(byteArrayOf(0,0,0,1,0x67,0x42,0,0x1e,0x12),byteArrayOf(0,0,0,1,0x68,0x56)))
            val nal = byteArrayOf(0,0,0,2,0x65,0x42)
            fixture.sender.frame(nal,1_000_000,true)
            assertArrayEquals(nal,fixture.car.videoPayload.get(5,TimeUnit.SECONDS))
            assertTrue(fixture.sent.get(5,TimeUnit.SECONDS) > 128)
            val reply = fixture.car.eventWire.get(5,TimeUnit.SECONDS).request("POST","/command",encode(mapOf("type" to "forceKeyFrame")))
            assertEquals(200,reply.status)
            assertEquals(1,fixture.keyframes.get())
            fixture.car.failure.getNow(null)?.let { throw AssertionError("Accessory failed",it) }
        }
    }

    @Test fun rejectedWifiProofCannotCreateMediaOrIssueRecord() {
        Fixture(rejectProof = true).use { fixture ->
            val started = CompletableFuture.supplyAsync { fixture.sender.start(fixture.car.controlPort,false) }
            fixture.car.challengeSeen.get(8,TimeUnit.SECONDS)
            fixture.car.allowProof.countDown()
            assertThrows(ExecutionException::class.java) { started.get(10,TimeUnit.SECONDS) }
            assertTrue(fixture.controlReady.isCompletedExceptionally)
            assertFalse(fixture.car.mediaStarted.get())
            assertFalse(fixture.car.recorded.get())
            assertEquals(0,fixture.displays.get())
        }
    }

    @Test fun closingDuringControlAuthenticationCancelsMediaStartup() {
        Fixture().use { fixture ->
            val started = CompletableFuture.supplyAsync { fixture.sender.start(fixture.car.controlPort,false) }
            fixture.car.challengeSeen.get(8,TimeUnit.SECONDS)
            fixture.sender.close()
            assertThrows(ExecutionException::class.java) { started.get(5,TimeUnit.SECONDS) }
            assertTrue(fixture.controlReady.isCancelled)
            assertFalse(fixture.car.mediaStarted.get())
            assertFalse(fixture.car.recorded.get())
        }
    }

    @Test fun capturePreparationFailureCannotIssueRecordOrCreateAVideoStream() {
        Fixture(failCapture = true).use { fixture ->
            val started = CompletableFuture.supplyAsync { fixture.sender.start(fixture.car.controlPort,false) }
            fixture.car.challengeSeen.get(8,TimeUnit.SECONDS)
            fixture.car.allowProof.countDown()
            assertThrows(ExecutionException::class.java) { started.get(10,TimeUnit.SECONDS) }
            assertTrue(fixture.controlReady.isDone)
            assertFalse(fixture.car.mediaStarted.get())
            assertFalse(fixture.car.recorded.get())
        }
    }

    @Test fun negotiatedAudioEncryptsActualUdpPayloadAndRespectsResourceTransfers() {
        Fixture().use { fixture ->
            val started = CompletableFuture.supplyAsync { fixture.sender.start(fixture.car.controlPort,true) }
            fixture.car.challengeSeen.get(8,TimeUnit.SECONDS)
            fixture.car.allowProof.countDown()
            started.get(10,TimeUnit.SECONDS)
            val aac = byteArrayOf(1,2,3,4,5)
            fixture.sender.audio(aac,1_000_000)
            assertArrayEquals(aac,fixture.car.audioPayload.get(5,TimeUnit.SECONDS))
            assertEquals(1,fixture.audioPackets.get())
            val change = mapOf("type" to "changeModes","params" to mapOf("resources" to listOf(mapOf("resourceID" to 2,"transferType" to 1))))
            assertEquals(200,fixture.car.eventWire.get(5,TimeUnit.SECONDS).request("POST","/command",encode(change)).status)
            fixture.sender.audio(aac,2_000_000)
            assertEquals(1,fixture.audioPackets.get())
        }
    }

    @Test fun controlInvitationResponsePreservesHttpAndRtspProtocols() {
        for (protocol in listOf("HTTP/1.1","HTTP/1.0","RTSP/1.0")) {
            ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
                Socket(InetAddress.getLoopbackAddress(),server.localPort).use { client ->
                    server.accept().use { accepted ->
                        client.soTimeout = 2000
                        val request = ControlMessage("GET /ctrl-int/1/connect $protocol",if (protocol == "RTSP/1.0") mapOf("cseq" to "9") else emptyMap(),ByteArray(0))
                        RtspWire(accepted).respond(request,null)
                        val response = RtspWire(client).next()
                        assertEquals("$protocol 200 OK",response.firstLine)
                        assertEquals(if (protocol == "RTSP/1.0") "9" else null,response.headers["cseq"])
                    }
                }
            }
        }
    }

    private class Fixture(rejectProof: Boolean = false, failCapture: Boolean = false) : Closeable {
        val car = Accessory(rejectProof)
        val controlReady = CompletableFuture<Unit>()
        val displays = AtomicInteger()
        val keyframes = AtomicInteger()
        val audioPackets = AtomicInteger()
        val sent = CompletableFuture<Int>()
        private val resources = CopyOnWriteArrayList<AutoCloseable>()
        private val closed = AtomicBoolean()
        private val controllerKey = AirPlayCrypto.ed25519Generate()
        private val identity = ControllerIdentity(controllerKey.privateKey,controllerKey.publicKey,UUID.randomUUID().toString())
        private var pinnedKey: ByteArray? = null
        private val bluetoothTrust = BondedAccessoryTrust(null,true) { pinnedKey = it }
        private val btFrames = mutableListOf<Iap2Frame>()
        private val bluetooth = PhoneBootstrap(identity.id,bluetoothTrust::validate,bluetoothTrust::verifyAuthentication,
            btFrames::add,{}, {},profile = PhoneBootstrapProfile.MODERN_WIRELESS,transportIdentifier = { "12:34:56:78:9A:BC" })
        val sender: AirPlaySender

        init {
            bluetooth.begin(); bluetooth.receive(car.identity(modern = true))
            bluetooth.receive(Iap2Messages.buildRaw(0xaa01) { bytes(0,car.certificate.encoded) })
            val challenge = Iap2BodyReader.of(btFrames.first { it.messageId == 0xaa02 }).bytes(0)
            bluetooth.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0,car.sign(challenge)) })
            assertTrue(bluetooth.status().authenticated)
            assertArrayEquals(car.authKey.public.encoded,pinnedKey)
            bluetooth.receive(Iap2Messages.buildRaw(0x4301) {
                group(1) { string(0,"isolated-car"); string(1,"isolated-passphrase"); u8(2,36); string(3,"127.0.0.1"); u8(4,2) }
                u32(2,car.controlPort); string(3,"12:34:56:78:90:AB")
                string(4,car.airplay.publicKey.joinToString("") { "%02x".format(it) }); string(5,"450.14.1")
            })
            assertTrue(bluetooth.status().endpointReceived)
            sender = AirPlaySender(identity,{ pairing -> pairing.verify(pairing.setup("3939",car.airplay.publicKey)) },
                { port -> Socket(InetAddress.getLoopbackAddress(),port) },{},object : SenderEvents {
                    override fun diagnostic(message: String) = Unit
                    override fun display(display: SenderDisplay) {
                        displays.incrementAndGet()
                        check(!failCapture) { "Isolated capture unavailable" }
                    }
                    override fun keyFrameRequested() { keyframes.incrementAndGet() }
                    override fun input(command: Map<*, *>): Boolean = false
                    override fun screenOwnership(owned: Boolean) = Unit
                    override fun sent(bytes: Int, queuedNanos: Long) { assertTrue(queuedNanos >= 0); sent.complete(bytes) }
                    override fun dropped(count: Long) { fail("Unexpected dropped video") }
                    override fun audioSent() { audioPackets.incrementAndGet() }
                    override fun failed(error: Throwable) { if (!closed.get()) controlReady.completeExceptionally(error) }
                    override fun iapControl(stream: BlockingDuplexByteStream): CompletableFuture<Unit> {
                        val session = Iap2Session.controllerTunnel(stream)
                        resources += session
                        val trust = BondedAccessoryTrust(checkNotNull(pinnedKey),false) { fail("Wi-Fi cannot enroll a new identity") }
                        Thread({
                            try {
                                check(session.awaitReady(5000))
                                val bootstrap = PhoneBootstrap(identity.id,trust::validate,trust::verifyAuthentication,
                                    session::send,{}, { fail("Wi-Fi control must not require another endpoint") },
                                    profile = PhoneBootstrapProfile.MODERN_WIRELESS,
                                    expectedAccessoryIdentity = bluetooth.accessoryIdentityFingerprint(),purpose = PhoneBootstrapPurpose.CONTROL_SESSION)
                                bootstrap.begin()
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                while (!closed.get() && !controlReady.isDone && System.nanoTime() < deadline) {
                                    session.recv(100)?.let(bootstrap::receive)
                                    if (bootstrap.status().controlReady) {
                                        check(session.awaitOutboundAcknowledged(5000))
                                        controlReady.complete(Unit)
                                    }
                                }
                                if (!controlReady.isDone) controlReady.completeExceptionally(TimeoutException("Control test timeout"))
                            } catch (failure: Throwable) { controlReady.completeExceptionally(failure) }
                        },"isolated-phone-control").apply { isDaemon = true; start() }
                        return controlReady
                    }
                },{})
        }
        override fun close() {
            closed.set(true); sender.close(); resources.reversed().forEach { runCatching { it.close() } }; car.close()
        }
    }

    private class Accessory(private val rejectProof: Boolean) : Closeable {
        val airplay = AirPlayIdentity.generate()
        val authKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val certificate = certificate(authKey)
        val challengeSeen = CompletableFuture<Unit>()
        val allowProof = CountDownLatch(1)
        val authenticated = CompletableFuture<Boolean>()
        val videoPayload = CompletableFuture<ByteArray>()
        val audioPayload = CompletableFuture<ByteArray>()
        val eventWire = CompletableFuture<RtspWire>()
        val failure = CompletableFuture<Throwable?>()
        val mediaStarted = AtomicBoolean()
        val recorded = AtomicBoolean()
        private val closed = AtomicBoolean()
        private val resources = CopyOnWriteArrayList<AutoCloseable>()
        private val server = ServerSocket(0,8,InetAddress.getLoopbackAddress()).also { resources += it }
        private val clock = NtpClock().also { resources += it }
        private val pairingStore = PairingStore()
        private val setup = PairSetup(airplay,pairingStore)
        private val verify = PairVerify(airplay,pairingStore)
        val controlPort get() = server.localPort

        init { work("isolated-car-rtsp") {
            val socket = server.accept().also { resources += it; it.soTimeout = 15000 }
            val wire = RtspWire(socket)
            while (!closed.get()) {
                val request = wire.next()
                val path = request.firstLine.split(' ')[1]
                when (path) {
                    "/pair-setup" -> {
                        assertEquals("3", request.headers["x-apple-hkp"])
                        wire.respond(request,setup.handle(request.body))
                    }
                    "/pair-verify" -> {
                        assertEquals("3", request.headers["x-apple-hkp"])
                        wire.respond(request,verify.handle(request.body))
                        if (verify.isVerified) wire.cipher = ControlCipher(key("Control-Salt","Control-Write-Encryption-Key"),key("Control-Salt","Control-Read-Encryption-Key"))
                    }
                    "/info" -> wire.respond(request,encode(mapOf(
                        "displays" to listOf(mapOf("widthPixels" to 480,"heightPixels" to 320,"maxFPS" to 30,"uuid" to "isolated-display")),
                        "audioFormats" to listOf(mapOf("type" to 100,"audioOutputFormats" to 0x800000L)))))
                    "/feedback" -> wire.respond(request,null)
                    "/" -> {
                        when (request.firstLine.substringBefore(' ')) {
                            "SETUP" -> {
                                val body = BplistCodec.decode(request.body) as Map<*, *>
                                val stream = (body["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
                                if (stream == null) {
                                    val eventServer = ServerSocket(0,1,InetAddress.getLoopbackAddress()).also { resources += it }
                                    work("isolated-car-events") {
                                        val events = RtspWire(eventServer.accept().also { resources += it; it.soTimeout = 10000 })
                                        events.cipher = ControlCipher(key("Events-Salt","Events-Read-Encryption-Key"),key("Events-Salt","Events-Write-Encryption-Key"))
                                        eventWire.complete(events)
                                    }
                                    val timingPort = clock.listen()
                                    clock.start(InetAddress.getLoopbackAddress(),(body["timingPort"] as Number).toInt())
                                    wire.respond(request,encode(mapOf("timingPort" to timingPort,"eventPort" to eventServer.localPort)))
                                } else when ((stream["type"] as Number).toInt()) {
                                    130 -> {
                                        val seed = (stream["seed"] as Number).toLong()
                                        val incoming = LinkedBlockingQueue<ByteArray>()
                                        val tunnel = IapTunnel(key("DataStream-Salt$seed","DataStream-Output-Encryption-Key")).also { resources += it }
                                        val port = tunnel.listen(object : IapTunnel.Listener {
                                            override fun onIap(bytes: ByteArray) { incoming.put(bytes) }
                                            override fun onClosed(cause: Throwable?) { incoming.offer(ByteArray(0)) }
                                        })
                                        val transport = object : BlockingDuplexByteStream {
                                            override fun send(data: ByteArray) {
                                                eventWire.get(5,TimeUnit.SECONDS).request("POST","/command",encode(mapOf("type" to "iAPSendMessage","params" to mapOf("data" to data))))
                                            }
                                            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = incoming.poll(timeoutMillis,TimeUnit.MILLISECONDS)
                                            override fun close() { incoming.offer(ByteArray(0)) }
                                        }
                                        work("isolated-car-iap2") {
                                            Iap2Session.openTunnel(transport).use { iap ->
                                                check(iap.awaitReady(5000))
                                                while (!closed.get()) {
                                                    val frame = iap.recv(100) ?: continue
                                                    when (frame.messageId) {
                                                        0x1d00 -> iap.send(identity(false))
                                                        0xaa00 -> iap.send(Iap2Messages.buildRaw(0xaa01) { bytes(0,certificate.encoded) })
                                                        0xaa02 -> {
                                                            challengeSeen.complete(Unit)
                                                            check(allowProof.await(8,TimeUnit.SECONDS))
                                                            val nonce = Iap2BodyReader.of(frame).bytes(0)
                                                            val proof = if (rejectProof) sign(ByteArray(nonce.size)) else sign(nonce)
                                                            iap.send(Iap2Messages.buildRaw(0xaa03) { bytes(0,proof) })
                                                        }
                                                        0xaa05 -> authenticated.complete(true)
                                                        0xaa04 -> authenticated.complete(false)
                                                        0x4300,0x5702 -> error("Control session unexpectedly requested wireless configuration")
                                                    }
                                                }
                                            }
                                        }
                                        wire.respond(request,encode(mapOf("streams" to listOf(mapOf("type" to 130,"dataPort" to port)))))
                                    }
                                    110 -> {
                                        check(authenticated.get(5,TimeUnit.SECONDS))
                                        mediaStarted.set(true)
                                        val videoServer = ServerSocket(0,1,InetAddress.getLoopbackAddress()).also { resources += it }
                                        val connection = (stream["streamConnectionID"] as Number).toLong()
                                        work("isolated-car-video") {
                                            videoServer.accept().use { video ->
                                                resources += video; video.soTimeout = 8000
                                                val input = DataInputStream(video.getInputStream())
                                                val configHeader = ByteArray(128); input.readFully(configHeader)
                                                assertEquals(1,configHeader[4].toInt())
                                                assertEquals(480f,ByteBuffer.wrap(configHeader).order(ByteOrder.LITTLE_ENDIAN).getFloat(16),0f)
                                                val config = ByteArray(ByteBuffer.wrap(configHeader).order(ByteOrder.LITTLE_ENDIAN).int); input.readFully(config)
                                                val header = ByteArray(128); input.readFully(header)
                                                assertNotEquals(0L,ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(8))
                                                val encrypted = ByteArray(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int); input.readFully(encrypted)
                                                videoPayload.complete(ScreenCodec.decryptFrame(key("DataStream-Salt$connection","DataStream-Output-Encryption-Key"),0,header,encrypted))
                                            }
                                        }
                                        wire.respond(request,encode(mapOf("streams" to listOf(mapOf("type" to 110,"dataPort" to videoServer.localPort)))))
                                    }
                                    100 -> {
                                        check(authenticated.get(5,TimeUnit.SECONDS))
                                        val audio = DatagramSocket(0,InetAddress.getLoopbackAddress()).also { resources += it; it.soTimeout = 8000 }
                                        val connection = (stream["streamConnectionID"] as Number).toLong()
                                        work("isolated-car-audio") {
                                            val packet = DatagramPacket(ByteArray(65535),65535); audio.receive(packet)
                                            val frame = checkNotNull(BufferedAudioStream.open(key("DataStream-Salt$connection","DataStream-Output-Encryption-Key"),packet.data.copyOf(packet.length)))
                                            assertEquals(48000L,frame.timestamp)
                                            audioPayload.complete(frame.rtp.copyOfRange(12,frame.rtp.size))
                                        }
                                        wire.respond(request,encode(mapOf("streams" to listOf(mapOf("type" to 100,"dataPort" to audio.localPort)))))
                                    }
                                    else -> error("Unexpected stream type")
                                }
                            }
                            "RECORD" -> { check(authenticated.get(5,TimeUnit.SECONDS)); recorded.set(true); wire.respond(request,null) }
                            else -> error("Unexpected control method")
                        }
                    }
                    else -> error("Unexpected request path")
                }
            }
        } }

        fun identity(modern: Boolean) = Iap2Messages.buildRaw(0x1d01) {
            string(0,"Isolated car"); string(1,"Test model"); string(2,"Test manufacturer"); string(3,"isolated-test-serial")
            u16List(6,if (modern) listOf(0x4301) else emptyList())
            u16List(7,listOf(0x4e09,0x4e0c) + if (modern) listOf(0x4300) else emptyList())
        }
        fun sign(nonce: ByteArray): ByteArray = Signature.getInstance("NONEwithECDSA").run { initSign(authKey.private); update(nonce); sign() }
        private fun key(salt: String, label: String): ByteArray = AirPlayCrypto.hkdfSha512(checkNotNull(verify.shared),salt.toByteArray(),label.toByteArray())
        private fun work(name: String, action: () -> Unit) { Thread({
            try { action() } catch (error: Throwable) { if (!closed.get()) { failure.complete(error); challengeSeen.completeExceptionally(error); authenticated.completeExceptionally(error); videoPayload.completeExceptionally(error) } }
        },name).apply { isDaemon = true; start() } }
        override fun close() { closed.set(true); allowProof.countDown(); resources.reversed().forEach { runCatching { it.close() } } }
    }

    companion object {
        private fun encode(value: Any) = BplistCodec.encode(value)
        private fun certificate(key: KeyPair): X509Certificate {
            val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
            val body = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.ONE))
                setIssuer(X500Name("CN=Isolated flow accessory")); setSubject(X500Name("CN=Isolated flow accessory"))
                setStartDate(Time(Date(System.currentTimeMillis()-86400000)))
                setEndDate(Time(Date(System.currentTimeMillis()+86400000)))
                setSignature(algorithm); setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
            }.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(body.encoded); sign() }
            return CertificateFactory.getInstance("X.509").generateCertificate(DERSequence(arrayOf(body,algorithm,DERBitString(signature))).encoded.inputStream()) as X509Certificate
        }
    }
}
