package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.Iap2CarPlayMessages
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.message.Iap2WirelessMessages
import com.carplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

class PhonePublicationTest {
    @Test fun aNewControllerForTheSameKeyRequiresANewProofAndRejectsOldChallengeReplay() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val first = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY, key = key)
        first.identify(modern = true, uuidSupported = true)
        first.authenticate()
        assertTrue(first.bootstrap.status().networkRequestQueued)
        val priorProof = Signature.getInstance("NONEwithECDSA").run { initSign(key.private); update(first.challenge()); sign() }
        val next = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY, key = key,
            expectedIdentity = first.bootstrap.accessoryIdentityFingerprint())
        next.identify(modern = true, uuidSupported = true)
        next.requestChallenge()
        assertFalse(first.challenge().contentEquals(next.challenge()))
        assertFalse(next.bootstrap.status().authenticated)
        assertThrows(IllegalStateException::class.java) {
            next.bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0, priorProof) })
        }
        assertEquals(0xaa04, next.sent.last().messageId)
        assertFalse(next.bootstrap.status().networkRequestQueued)
        assertNull(next.endpoint)
    }

    @Test fun freshIdentificationCannotSilentlyChangeTheCarIdentityDuringRecovery() {
        val first = Fixture()
        first.identify(modern = true, uuidSupported = true, serial = "isolated-original-serial")
        val expected = first.bootstrap.accessoryIdentityFingerprint()!!
        val sameCar = Fixture(expectedIdentity = expected)
        expected.fill(0) // caller mutation cannot alter the installed identity guard
        sameCar.identify(modern = true, uuidSupported = true, serial = "isolated-original-serial")
        assertTrue(sameCar.bootstrap.status().identified)
        val next = Fixture(expectedIdentity = first.bootstrap.accessoryIdentityFingerprint())
        assertThrows(IllegalStateException::class.java) {
            next.identify(modern = true, uuidSupported = true, serial = "isolated-other-serial")
        }
        assertFalse(next.sent.any { it.messageId == 0xaa00 })
        assertFalse(next.bootstrap.status().identified)
    }

    @Test fun reportedCarPlayVersionsSurviveALaterLegacyConfigurationUpdate() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY)
        fixture.identify(modern = true, uuidSupported = true)
        fixture.authenticate()
        fixture.bootstrap.receive(Iap2CarPlayMessages.startSession(7100, publicKey = "07".repeat(32),
            sourceVersion = "reported-source.1", sdkVersion = "reported-sdk.2", wireless =
                com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car", "test-password", 36, listOf("192.168.3.1"), 2)))
        fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car", "test-password", 36, 2))
        assertEquals("reported-source.1", fixture.endpoint!!.sourceVersion)
        assertEquals("reported-sdk.2", fixture.endpoint!!.carPlaySdkVersion)
        assertEquals(7100, fixture.endpoint!!.port)
        assertEquals(listOf("192.168.3.1"), fixture.endpoint!!.addresses)
        assertTrue(fixture.stages.any { it.contains("reported-sdk.2") })
        assertFalse(fixture.stages.any { it.contains("test-password") })
    }

    @Test fun minimalWirelessAttributesPublishTheDeclaredBearerWithoutAnInventedIdentifier() {
        val fixture = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        fixture.identify(modern = true,uuidSupported = true,received = listOf(
            0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4300),
            fixture.sent.map { it.messageId })
        val availability = fixture.sent.last()
        assertArrayEquals(hex("40 40 00 0f 43 00 00 09 00 01 00 05 00 00 01"),availability.encodedFrame())
        val decoded = Iap2CarPlayMessages.availability(availability)
        assertEquals(true,decoded.wireless!!.available)
        assertNull(decoded.wireless.identifier)
        assertNull(decoded.wired)
        assertNull(decoded.themeAssets)
        assertEquals(0,fixture.transportReads)
        assertNull(fixture.endpoint)
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
        assertTrue(fixture.stages.any { it.contains("省略可选传输标识") && it.contains("帧=15B") })
    }

    @Test fun minimalAvailabilityStillValidatesAnySupportedTransportNotificationAndKeepsLegacyFlow() {
        val missing = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        missing.identify(modern = true,uuidSupported = false)
        assertThrows(IllegalArgumentException::class.java) { missing.authenticate() }
        assertFalse(missing.sent.any { it.messageId in listOf(0x4e0e,0x4e0d,0x4300,0x5702) })
        val supported = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        supported.identify(modern = true,uuidSupported = false)
        supported.authenticate()
        assertEquals(Fixture.ADDRESS,Iap2WirelessMessages.deviceTransportIdentifier(supported.sent.first { it.messageId == 0x4e0e }).bluetoothMac)
        assertNull(Iap2CarPlayMessages.availability(supported.sent.last()).wireless!!.identifier)
        assertEquals(1,supported.transportReads)
        val legacy = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        legacy.identify(modern = false,uuidSupported = false,received = listOf(0x5702))
        legacy.authenticate()
        assertEquals(listOf(0xaa05,0x5702),legacy.sent.takeLast(2).map { it.messageId })
        assertEquals(0,legacy.transportReads)
    }

    @Test fun minimalWirelessAvailabilityCannotPublishOrAcceptConfigurationWithInvalidProof() {
        val fixture = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        fixture.identify(modern = true,uuidSupported = false,received = listOf(0x4300))
        fixture.requestChallenge()
        val wrong = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signature = Signature.getInstance("NONEwithECDSA").run { initSign(wrong.private); update(fixture.challenge()); sign() }
        assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0,signature) }) }
        assertEquals(0xaa04,fixture.sent.last().messageId)
        assertFalse(fixture.sent.any { it.messageId in listOf(0x4300,0x5702) })
        assertThrows(IllegalStateException::class.java) {
            fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car","test-password",36,2))
        }
        assertNull(fixture.endpoint)
    }

    @Test fun minimalWirelessAvailabilityStillRequiresAnAuthenticatedValidStartEndpoint() {
        val fixture = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL)
        fixture.identify(modern = true,uuidSupported = false,received = listOf(0x4300))
        val start = Iap2CarPlayMessages.startSession(7234,publicKey="07".repeat(32),sourceVersion="isolated-test", wireless =
            com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car","test-password",36,listOf("192.168.3.1"),2))
        assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(start) }
        fixture.authenticate()
        fixture.bootstrap.receive(start)
        assertEquals(7234,fixture.endpoint!!.port)
        assertEquals(listOf("192.168.3.1"),fixture.endpoint!!.addresses)
        assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
        assertEquals(0,fixture.transportReads)
    }

    @Test fun explicitWifiRequestFollowsUnchangedFlatPublicationDespiteMissingAdvertisedLegacyPair() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)
        fixture.identify(modern = true, uuidSupported = true, received = listOf(
            0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4e0e,0x4e0d,0x4300,0x5702),
            fixture.sent.map { it.messageId })
        assertEquals(1,fixture.sent.count { it.messageId == 0x5702 })
        assertArrayEquals(hex("40 40 00 06 57 02"),fixture.sent.last().encodedFrame())
        assertEquals(33,fixture.sent.first { it.messageId == 0x4300 }.encodedFrame().size)
        assertEquals(Fixture.ADDRESS,Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4300 }).string(1))
        assertEquals(1,fixture.transportReads)
        assertTrue(fixture.stages.any { it.contains("接收5702=false，发送5703=false") })
        assertFalse(fixture.stages.any { it.contains(Fixture.ADDRESS) || it.contains(Fixture.UUID) })
        assertNull(fixture.endpoint)
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
    }

    @Test fun explicitWifiRequestPreservesProofAndCredentialConsistencyForBothConfigurationResponses() {
        for (modernFirst in listOf(false,true)) {
            val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)
            val wifi = Iap2WirelessMessages.accessoryWiFiConfiguration("test-car","test-password",36,2)
            val modern = Iap2CarPlayMessages.startSession(7123,publicKey = "07".repeat(32),sourceVersion = "isolated-test", wireless =
                com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car","test-password",36,listOf("192.168.3.1"),2))
            fixture.identify(modern = true,uuidSupported = false,received = listOf(0x4300))
            assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(wifi) }
            assertNull(fixture.endpoint)
            fixture.authenticate()
            fixture.bootstrap.receive(if (modernFirst) modern else wifi)
            if (!modernFirst) {
                assertNull(fixture.endpoint!!.port)
                assertTrue(fixture.endpoint!!.addresses.isEmpty())
            }
            fixture.bootstrap.receive(if (modernFirst) wifi else modern)
            val endpoint = fixture.endpoint!!
            assertEquals("test-car",endpoint.ssid)
            assertEquals("test-password",endpoint.password)
            assertEquals(36,endpoint.channel)
            assertEquals(7123,endpoint.port)
            assertEquals(listOf("192.168.3.1"),endpoint.addresses)
            assertEquals("07".repeat(32),endpoint.publicKey)
            assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
            assertThrows(IllegalArgumentException::class.java) {
                fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car","changed-password",36,2))
            }
            assertEquals(endpoint,fixture.endpoint)
            assertEquals(1,fixture.sent.count { it.messageId == 0x5702 })
        }
    }

    @Test fun explicitWifiRequestDoesNotChangeReferenceOnlyOrLegacyPublication() {
        val unchanged = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_REFERENCE)
        unchanged.identify(modern = true,uuidSupported = false,received = listOf(0x4300))
        unchanged.authenticate()
        assertEquals(0x4300,unchanged.sent.last().messageId)
        assertFalse(unchanged.sent.any { it.messageId == 0x5702 })
        val legacy = Fixture("",PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)
        legacy.identify(modern = false,uuidSupported = false,received = listOf(0x5702))
        legacy.authenticate()
        assertEquals(listOf(0xaa05,0x5702),legacy.sent.takeLast(2).map { it.messageId })
        assertEquals(1,legacy.sent.count { it.messageId == 0x5702 })
        assertEquals(0,legacy.transportReads)
        assertFalse(legacy.sent.any { it.messageId == 0x4300 })
        val rejected = Fixture("",PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)
        rejected.identify(modern = false,uuidSupported = false,received = emptyList())
        assertThrows(IllegalStateException::class.java) { rejected.authenticate() }
        assertFalse(rejected.sent.any { it.messageId in listOf(0x4300,0x5702) })
    }

    @Test fun recordedFlatReferencePublishesRootParametersWithSanitizedObservedGoldenBytes() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_REFERENCE)
        fixture.identify(modern = true, uuidSupported = true, received = listOf(
            0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4e0e,0x4e0d,0x4300),
            fixture.sent.map { it.messageId })
        val availability = fixture.sent.last()
        // Shape from attempt ea11ff1f, with the private address replaced by the isolated fixture.
        assertArrayEquals(hex("40 40 00 21 43 00 00 05 00 00 01 00 16 00 01 31 32 3a 33 34 3a 35 36 3a 37 38 3a 39 41 3a 42 43 00"),
            availability.encodedFrame())
        val body = Iap2BodyReader.of(availability)
        assertEquals(listOf(0,1),body.list().map { it.id })
        assertEquals(1,body.u8(0))
        assertEquals(Fixture.ADDRESS,body.string(1))
        assertEquals(Fixture.ADDRESS,Iap2WirelessMessages.deviceTransportIdentifier(fixture.sent.first { it.messageId == 0x4e0e }).bluetoothMac)
        assertTrue(Iap2WirelessMessages.wirelessCarPlayAvailability(fixture.sent.first { it.messageId == 0x4e0d }))
        assertEquals(1,fixture.transportReads)
        assertTrue(fixture.stages.any { it.contains("根参数=[0,1]") && it.contains("长度=18B，帧=33B") })
        assertFalse(fixture.stages.any { it.contains(Fixture.ADDRESS) || it.contains(Fixture.UUID) })
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
        assertNull(fixture.endpoint)
    }

    @Test fun recordedFlatBuilderPreservesGroupedAndNumericVariantCodecs() {
        val flat = Iap2CarPlayMessages.buildFlatWirelessAvailability(Fixture.ADDRESS)
        assertEquals(Fixture.ADDRESS,Iap2BodyReader.of(flat).string(1))
        assertThrows(com.carplay.iap2.wire.Iap2ProtocolException::class.java) { Iap2BodyReader.of(flat).group(1) }
        val grouped = Iap2CarPlayMessages.buildAvailability(bluetoothTransportIdentifier = Fixture.ADDRESS)
        assertEquals(37,grouped.encodedFrame().size)
        assertEquals(Fixture.ADDRESS,Iap2CarPlayMessages.availability(grouped).wireless!!.identifier)
        val numeric = Iap2CarPlayMessages.buildVivoReferenceAvailability()
        assertEquals(21,numeric.encodedFrame().size)
        assertEquals(2,Iap2BodyReader.of(numeric).group(1).u16(1))
    }

    @Test fun recordedFlatReferenceDoesNotDependOnAccessoryComponentOrDuplicateSupportedNotifications() {
        for (component in listOf(3,77,0,65535,null)) {
            val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_REFERENCE)
            fixture.identify(modern = true, uuidSupported = false, componentId = component)
            fixture.authenticate()
            assertEquals(Fixture.ADDRESS,Iap2BodyReader.of(fixture.sent.last()).string(1))
            assertEquals(1,fixture.sent.count { it.messageId == 0x4e0e })
            assertEquals(1,fixture.sent.count { it.messageId == 0x4e0d })
            assertEquals(1,fixture.transportReads)
            assertFalse(fixture.sent.any { it.messageId == 0x5702 })
        }
    }

    @Test fun recordedFlatReferenceKeepsLegacyNoAddressFlowAndEndpointAuthenticationGuards() {
        val legacy = Fixture("",PhoneBootstrapProfile.VIVO_FLAT_REFERENCE)
        legacy.identify(modern = false, uuidSupported = false, received = listOf(0x5702))
        legacy.authenticate()
        assertEquals(listOf(0xaa05,0x5702),legacy.sent.takeLast(2).map { it.messageId })
        assertEquals(0,legacy.transportReads)
        assertFalse(legacy.sent.any { it.messageId in listOf(0x4e0e,0x4e0d,0x4300) })
        val modern = Fixture(profile = PhoneBootstrapProfile.VIVO_FLAT_REFERENCE)
        val start = Iap2CarPlayMessages.startSession(7000, publicKey = "07".repeat(32), sourceVersion = "isolated-test", wireless =
            com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car","test-password",36,listOf("192.168.3.1"),2))
        modern.identify(modern = true, uuidSupported = false, received = listOf(0x4300))
        assertThrows(IllegalStateException::class.java) { modern.bootstrap.receive(start) }
        assertNull(modern.endpoint)
        modern.authenticate()
        assertNull(modern.endpoint)
        modern.bootstrap.receive(start)
        assertEquals(BootstrapPhase.READY,modern.bootstrap.status().phase)
        assertEquals("test-car",modern.endpoint!!.ssid)
    }

    @Test fun wirelessReadyProfilePublishesReferenceNotificationsBeforeUnchangedStringRequestForObservedCar() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY)
        fixture.identify(modern = true, uuidSupported = true, received = listOf(
            0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4e0e,0x4e0d,0x4300),
            fixture.sent.map { it.messageId })
        val transport = fixture.sent.first { it.messageId == 0x4e0e }
        assertEquals(Fixture.ADDRESS,Iap2WirelessMessages.deviceTransportIdentifier(transport).bluetoothMac)
        assertNull(Iap2WirelessMessages.deviceTransportIdentifier(transport).usbTransportIdentifier)
        assertArrayEquals(hex("40 40 00 1c 4e 0e 00 16 00 00 31 32 3a 33 34 3a 35 36 3a 37 38 3a 39 41 3a 42 43 00"),transport.encodedFrame())
        val readiness = fixture.sent.first { it.messageId == 0x4e0d }
        assertTrue(Iap2WirelessMessages.wirelessCarPlayAvailability(readiness))
        assertArrayEquals(hex("40 40 00 0b 4e 0d 00 05 00 00 01"),readiness.encodedFrame())
        assertArrayEquals(hex("40 40 00 25 43 00 00 1f 00 01 00 05 00 00 01 00 16 00 01 31 32 3a 33 34 3a 35 36 3a 37 38 3a 39 41 3a 42 43 00"),
            fixture.sent.last().encodedFrame())
        assertEquals(Fixture.ADDRESS,Iap2CarPlayMessages.availability(fixture.sent.last()).wireless!!.identifier)
        assertEquals(1,fixture.transportReads)
        assertTrue(fixture.stages.any { it.contains("4e0e=false，4e0d=false") })
        assertFalse(fixture.stages.any { it.contains(Fixture.ADDRESS) || it.contains(Fixture.UUID) })
        assertNull(fixture.endpoint)
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
    }

    @Test fun wirelessReadyProfileDoesNotDuplicateAlreadyAdvertisedNotifications() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY)
        fixture.identify(modern = true, uuidSupported = false)
        fixture.authenticate()
        assertEquals(listOf(0x4e0e,0x4e0d,0x4300),fixture.sent.takeLast(3).map { it.messageId })
        assertEquals(1,fixture.sent.count { it.messageId == 0x4e0e })
        assertEquals(1,fixture.sent.count { it.messageId == 0x4e0d })
        assertEquals(1,fixture.transportReads)
        assertFalse(fixture.sent.any { it.messageId in listOf(0x4e0c,0x5702) })
    }

    @Test fun wirelessReadyProfileDoesNotOverrideLegacyNotificationCapabilitiesOrRequireUnusedMac() {
        val fixture = Fixture("",PhoneBootstrapProfile.VIVO_WIRELESS_READY)
        fixture.identify(modern = false, uuidSupported = false, received = listOf(0x5702))
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x5702),fixture.sent.takeLast(2).map { it.messageId })
        assertEquals(0,fixture.transportReads)
        assertFalse(fixture.sent.any { it.messageId in listOf(0x4e0e,0x4e0d,0x4300) })
        fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car","test-password",36,2))
        assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
    }

    @Test fun wirelessReadyProfileRejectsNetworkEndpointUntilActualValidProof() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_WIRELESS_READY)
        val start = Iap2CarPlayMessages.startSession(7000, publicKey = "07".repeat(32), sourceVersion = "isolated-test", wireless =
            com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car","test-password",36,listOf("192.168.3.1"),2))
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300))
        assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(start) }
        assertNull(fixture.endpoint)
        assertEquals(0,fixture.transportReads)
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x4e0e,0x4e0d,0x4300),fixture.sent.takeLast(4).map { it.messageId })
        fixture.bootstrap.receive(start)
        assertEquals("test-car",fixture.endpoint!!.ssid)
        assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
    }

    @Test fun transportStringPublicationDecodesWithExistingCarPlayReaderForObservedCarCapabilities() {
        for (component in listOf(3,77,0,65535,null)) {
            val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_TRANSPORT_STRING)
            fixture.identify(modern = true, uuidSupported = true, received = listOf(
                0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300), componentId = component)
            fixture.authenticate()
            assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4300),
                fixture.sent.map { it.messageId })
            val decoded = Iap2CarPlayMessages.availability(fixture.sent.last())
            assertEquals(true,decoded.wireless!!.available)
            assertEquals(Fixture.ADDRESS,decoded.wireless.identifier)
            assertNull(decoded.wired)
            assertNull(decoded.themeAssets)
            assertEquals(1,fixture.transportReads)
            assertEquals(Fixture.UUID,Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0c }).string(0))
            assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
            assertNull(fixture.endpoint)
            assertTrue(fixture.stages.any { it.contains("长度=18B，帧=37B") })
            assertFalse(fixture.stages.any { it.contains(Fixture.ADDRESS) || it.contains(Fixture.UUID) })
        }
    }

    @Test fun transportStringPublicationHasNulTerminatedMacGoldenBytesAndRejectsNumericReferenceInStandardDecoder() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_TRANSPORT_STRING)
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300))
        fixture.authenticate()
        assertArrayEquals(hex("40 40 00 25 43 00 00 1f 00 01 00 05 00 00 01 00 16 00 01 31 32 3a 33 34 3a 35 36 3a 37 38 3a 39 41 3a 42 43 00"),
            fixture.sent.last().encodedFrame())
        for (numeric in listOf(2,3,77,65535)) {
            assertThrows(com.carplay.iap2.wire.Iap2ProtocolException::class.java) {
                Iap2CarPlayMessages.availability(Iap2CarPlayMessages.buildWirelessComponentAvailability(numeric))
            }
        }
    }

    @Test fun transportStringProfileSharesOneAddressReadWithSupportedNotificationAndAccepts4301OnlyAfterProof() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_TRANSPORT_STRING)
        val start = Iap2CarPlayMessages.startSession(7000, publicKey = "07".repeat(32), sourceVersion = "isolated-test", wireless =
            com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car", "test-password", 36, listOf("192.168.3.1"), 2))
        fixture.identify(modern = true, uuidSupported = true)
        assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(start) }
        assertNull(fixture.endpoint)
        fixture.authenticate()
        assertEquals(1,fixture.transportReads)
        assertEquals(Fixture.ADDRESS,Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0e }).string(0))
        assertEquals(Fixture.ADDRESS,Iap2CarPlayMessages.availability(fixture.sent.last()).wireless!!.identifier)
        assertFalse(fixture.sent.any { it.messageId == 0x5702 })
        fixture.bootstrap.receive(start)
        assertEquals("test-car",fixture.endpoint!!.ssid)
        assertEquals(7000,fixture.endpoint!!.port)
        assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
    }

    @Test fun transportStringProfileCannotSubstituteUuidPlaceholderOrEmptyAddressWhen4e0eIsUnsupported() {
        for (profile in listOf(PhoneBootstrapProfile.VIVO_TRANSPORT_STRING,PhoneBootstrapProfile.VIVO_WIRELESS_READY,
            PhoneBootstrapProfile.VIVO_FLAT_REFERENCE,PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)) {
            for (address in listOf("",Fixture.UUID,"02:00:00:00:00:00","00:00:00:00:00:00","FF:FF:FF:FF:FF:FF")) {
                val fixture = Fixture(address,profile)
                fixture.identify(modern = true, uuidSupported = true, received = listOf(0x4e09,0x4e0a,0x4e0c,0x4300))
                assertThrows(IllegalArgumentException::class.java) { fixture.authenticate() }
                assertTrue(fixture.bootstrap.status().authenticated)
                assertEquals(0xaa05,fixture.sent.last().messageId)
                assertFalse(fixture.sent.any { it.messageId in 0x4e09..0x4e0e || it.messageId in listOf(0x4300,0x5702) })
                assertNull(fixture.endpoint)
            }
        }
    }

    @Test fun transportStringProfileLegacyWifiDoesNotReadUnusedAddress() {
        val fixture = Fixture("",PhoneBootstrapProfile.VIVO_TRANSPORT_STRING)
        fixture.identify(modern = false, uuidSupported = false, received = listOf(0x5702))
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x5702),fixture.sent.takeLast(2).map { it.messageId })
        assertEquals(0,fixture.transportReads)
        fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car","test-password",36,2))
        assertEquals(BootstrapPhase.READY,fixture.bootstrap.status().phase)
    }

    @Test fun transportStringProfileInvalidProofCannotReadOrPublishPhoneAddress() {
        for (profile in listOf(PhoneBootstrapProfile.VIVO_TRANSPORT_STRING,PhoneBootstrapProfile.VIVO_WIRELESS_READY,
            PhoneBootstrapProfile.VIVO_FLAT_REFERENCE,PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)) {
            val fixture = Fixture(profile = profile)
            fixture.identify(modern = true, uuidSupported = true, received = listOf(0x4300))
            fixture.requestChallenge()
            val wrongKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val invalid = Signature.getInstance("NONEwithECDSA").run { initSign(wrongKey.private); update(fixture.challenge()); sign() }
            assertThrows(IllegalStateException::class.java) { fixture.bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0,invalid) }) }
            assertEquals(0xaa04,fixture.sent.last().messageId)
            assertFalse(fixture.bootstrap.status().authenticated)
            assertEquals(0,fixture.transportReads)
            assertFalse(fixture.sent.any { it.messageId in listOf(0xaa05,0x4e0e,0x4e0d,0x4300,0x5702) })
            assertNull(fixture.endpoint)
        }
    }

    @Test fun transportStringProfileDoesNotRequireAccessoryComponentMetadataForPhoneTransport() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.VIVO_TRANSPORT_STRING)
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300), malformedTransport = true, componentId = null)
        fixture.authenticate()
        assertEquals(Fixture.ADDRESS,Iap2CarPlayMessages.availability(fixture.sent.last()).wireless!!.identifier)
        assertTrue(fixture.bootstrap.status().authenticated)
    }

    @Test fun observedCarCapabilitiesPublishOnlyDeclaredNotificationsWithoutRequestingPhoneMac() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = true, uuidSupported = true, received = listOf(
            0x6801,0x4c01,0x4c04,0x5001,0x4e09,0x4e0a,0x4e0b,0x4e0c,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4300),
            fixture.sent.map { it.messageId })
        assertEquals(0,fixture.transportReads)
        assertEquals(3,Iap2BodyReader.of(fixture.sent.last()).group(1).u16(1))
        assertTrue(fixture.bootstrap.status().authenticated)
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION,fixture.bootstrap.status().phase)
        assertNull(fixture.endpoint)
    }

    @Test fun minimalModernCapabilityListCannotCauseUnsupportedOptionalNotifications() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300))
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x4300),fixture.sent.takeLast(2).map { it.messageId })
        assertFalse(fixture.sent.any { it.messageId in 0x4e09..0x4e0e })
        assertEquals(0,fixture.transportReads)
    }

    @Test fun readinessCanBeAdvertisedWithoutUnsupportedTransportNotificationOrPhoneMac() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4e0d,0x4300))
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x4e0d,0x4300),fixture.sent.takeLast(3).map { it.messageId })
        assertFalse(fixture.sent.any { it.messageId == 0x4e0e })
        assertEquals(0,fixture.transportReads)
    }

    @Test fun legacyWifiRequestRequiresBothAdvertisedSidesOfItsConfigurationExchange() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = false, uuidSupported = false, received = emptyList())
        assertThrows(IllegalStateException::class.java) { fixture.authenticate() }
        assertTrue(fixture.bootstrap.status().authenticated)
        assertFalse(fixture.sent.any { it.messageId in listOf(0x4300,0x5702) })
        assertNull(fixture.endpoint)
    }

    @Test fun genericPublicationKeepsItsOriginalNotificationsAndStringAvailability() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.GENERIC)
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300))
        fixture.authenticate()
        assertEquals(listOf(0xaa05,0x4e09,0x4e0a,0x4e0c,0x4e0e,0x4300,0x5702),
            fixture.sent.takeLast(7).map { it.messageId })
        assertEquals(Fixture.ADDRESS, Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4300 }).group(1).string(1))
    }

    @Test fun malformedOptionalTransportDiagnosticsCannotBlockIdentificationOrValidProof() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300), malformedTransport = true)
        fixture.authenticate()
        assertTrue(fixture.bootstrap.status().identified)
        assertTrue(fixture.bootstrap.status().authenticated)
        assertEquals(0x4300,fixture.sent.last().messageId)
    }

    @Test fun modernPublicationUsesTheCarWirelessComponentAndNeverTheBluetoothComponent() {
        for (component in listOf(3,77,0,65535)) {
            val fixture = Fixture(address = "")
            fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300), componentId = component)
            fixture.authenticate()
            assertEquals(component,Iap2BodyReader.of(fixture.sent.last()).group(1).u16(1))
            assertEquals(0,fixture.transportReads)
        }
    }

    @Test fun missingModernWirelessComponentCannotFallBackToReferenceConstantAfterProof() {
        val fixture = Fixture(address = "")
        fixture.identify(modern = true, uuidSupported = false, received = listOf(0x4300), componentId = null)
        assertThrows(IllegalStateException::class.java) { fixture.authenticate() }
        assertTrue(fixture.bootstrap.status().authenticated)
        assertFalse(fixture.sent.any { it.messageId == 0x4300 })
        assertNull(fixture.endpoint)
    }

    @Test fun vivoAvailabilityMatchesItsReferenceBytesWithoutChangingGenericStringEncoding() {
        assertArrayEquals(hex("40 40 00 15 43 00 00 0f 00 01 00 05 00 00 01 00 06 00 01 00 02"),
            Iap2CarPlayMessages.buildVivoReferenceAvailability().encodedFrame())
        val generic = Iap2CarPlayMessages.buildAvailability(bluetoothTransportIdentifier = "generic-transport")
        assertEquals("generic-transport", Iap2BodyReader.of(generic).group(1).string(1))
    }

    @Test fun modernVivoPublishesRealBluetoothIdentityAndReadinessThenWaitsFor4301() {
        val fixture = Fixture()
        fixture.identify(modern = true, uuidSupported = false)
        assertFalse(fixture.bootstrap.status().authenticated)
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0e,0x4e0d,0x4300),
            fixture.sent.map { it.messageId })
        assertEquals(Fixture.ADDRESS, Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0e }).string(0))
        assertEquals(1, Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0d }).u8(0))
        assertEquals(3, Iap2BodyReader.of(fixture.sent.last()).group(1).u16(1))
        assertEquals(BootstrapPhase.WIRELESS_CONFIGURATION, fixture.bootstrap.status().phase)
        assertNull(fixture.endpoint)
        fixture.bootstrap.receive(Iap2CarPlayMessages.startSession(7000, publicKey = "07".repeat(32), sourceVersion = "test-reference", wireless =
            com.carplay.iap2.message.Iap2WirelessSessionParameters("test-car", "test-password", 36, listOf("192.168.3.1"), 2)))
        assertEquals("test-car", fixture.endpoint!!.ssid)
        assertEquals(7000, fixture.endpoint!!.port)
        assertEquals(BootstrapPhase.READY, fixture.bootstrap.status().phase)
    }

    @Test fun optionalUuidIsSentOnlyWhenCarAdvertisesItAndKeepsPersistentUuidSeparate() {
        val fixture = Fixture()
        fixture.identify(modern = true, uuidSupported = true)
        fixture.authenticate()
        assertEquals(Fixture.UUID, Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0c }).string(0))
        assertEquals(Fixture.ADDRESS, Iap2BodyReader.of(fixture.sent.first { it.messageId == 0x4e0e }).string(0))
        assertFalse(fixture.sent.any { it.messageId == 0x5702 })
    }

    @Test fun legacyWifiPathPublishesReadinessAndRequests5703Configuration() {
        val fixture = Fixture()
        fixture.identify(modern = false, uuidSupported = true)
        fixture.authenticate()
        assertEquals(listOf(0x4e0d, 0x5702), fixture.sent.takeLast(2).map { it.messageId })
        assertFalse(fixture.sent.any { it.messageId == 0x4300 })
        fixture.bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("test-car", "test-password", 36, 2))
        assertEquals("test-car", fixture.endpoint!!.ssid)
    }

    @Test fun invalidProofCannotPublishAnyNetworkRequest() {
        val fixture = Fixture()
        fixture.identify(modern = true, uuidSupported = true)
        fixture.requestChallenge()
        val wrongKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val invalid = Signature.getInstance("NONEwithECDSA").run {
            initSign(wrongKey.private); update(fixture.challenge()); sign()
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0, invalid) })
        }
        assertFalse(fixture.bootstrap.status().authenticated)
        assertEquals(0xaa04, fixture.sent.last().messageId)
        assertFalse(fixture.sent.any { it.messageId in listOf(0x4e0e,0x4e0d,0x4300,0x5702) })
        assertNull(fixture.endpoint)
    }

    @Test fun missingOrPlaceholderBluetoothAddressCannotBeAdvertisedAfterValidProof() {
        for (address in listOf(Fixture.UUID, "02:00:00:00:00:00", "00:00:00:00:00:00")) {
            val fixture = Fixture(address)
            fixture.identify(modern = true, uuidSupported = true)
            assertThrows(IllegalArgumentException::class.java) { fixture.authenticate() }
            assertTrue(fixture.bootstrap.status().authenticated)
            assertFalse(fixture.sent.any { it.messageId in listOf(0x4e0e,0x4e0d,0x4300,0x5702) })
            assertNull(fixture.endpoint)
        }
    }

    @Test fun bluetoothAddressValidationPreservesConfiguredValueAndRejectsInvalidInputs() {
        assertEquals(Fixture.ADDRESS, BluetoothTransportIdentity.normalizedAddress(" 12:34:56:78:9a:bc "))
        for (value in listOf(null,"",Fixture.UUID,"02:00:00:00:00:00","00:00:00:00:00:00","FF:FF:FF:FF:FF:FF","12:34:56:78:9A")) {
            assertNull(BluetoothTransportIdentity.normalizedAddress(value))
        }
    }

    @Test fun modernWirelessKeepsFullGroupAndOnlyPublishesDeclaredNotifications() {
        val fixture = Fixture(profile = PhoneBootstrapProfile.MODERN_WIRELESS)
        fixture.identify(true,true,listOf(0x4300,0x4e09,0x4e0a,0x4e0c))
        fixture.authenticate()
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0a,0x4e0c,0x4300),fixture.sent.map { it.messageId })
        val availability = Iap2CarPlayMessages.availability(fixture.sent.last())
        assertEquals(true,availability.wired!!.available)
        assertEquals(Fixture.UUID,availability.wired.identifier)
        assertEquals(true,availability.wireless!!.available)
        assertEquals(Fixture.ADDRESS,availability.wireless.identifier)
        assertEquals(87,fixture.sent.last().encodedFrame().size)
        assertFalse(fixture.bootstrap.status().endpointReceived)
    }

    @Test fun wifiControlAuthenticatesWithoutWirelessConfigurationCapabilitiesOrTransportIdentity() {
        val fixture = Fixture(address = "",profile = PhoneBootstrapProfile.MODERN_WIRELESS,
            purpose = PhoneBootstrapPurpose.CONTROL_SESSION)
        fixture.identify(false,true,listOf(0x4e09,0x4e0c))
        assertFalse(fixture.bootstrap.status().controlReady)
        fixture.authenticate()
        val status = fixture.bootstrap.status()
        assertTrue(status.identified && status.authenticated && status.controlReady)
        assertEquals(BootstrapPhase.CONTROL_READY,status.phase)
        assertFalse(status.endpointReceived || status.networkRequestQueued)
        assertNull(fixture.endpoint)
        assertEquals(0,fixture.transportReads)
        assertEquals(listOf(0x1d00,0x1d02,0xaa00,0xaa02,0xaa05,0x4e09,0x4e0c),fixture.sent.map { it.messageId })
    }

    @Test fun wifiControlRejectsChangedIdentityEvenIfTheTransportCapabilitiesAreDifferent() {
        val bluetooth = Fixture(profile = PhoneBootstrapProfile.MODERN_WIRELESS)
        bluetooth.identify(true,true,serial = "isolated-accessory-one")
        val wifi = Fixture(profile = PhoneBootstrapProfile.MODERN_WIRELESS,
            expectedIdentity = bluetooth.bootstrap.accessoryIdentityFingerprint(),purpose = PhoneBootstrapPurpose.CONTROL_SESSION)
        assertThrows(IllegalStateException::class.java) { wifi.identify(false,true,serial = "isolated-accessory-two") }
        assertFalse(wifi.bootstrap.status().identified || wifi.bootstrap.status().controlReady)
        assertFalse(wifi.sent.any { it.messageId == 0xaa00 })
    }

    @Test fun wifiControlUsesAFreshChallengeAndCannotAcceptTheBluetoothProof() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bluetooth = Fixture(profile = PhoneBootstrapProfile.MODERN_WIRELESS,key = key)
        bluetooth.identify(true,true)
        bluetooth.authenticate()
        val oldProof = Signature.getInstance("NONEwithECDSA").run { initSign(key.private); update(bluetooth.challenge()); sign() }
        val wifi = Fixture(profile = PhoneBootstrapProfile.MODERN_WIRELESS,key = key,
            expectedIdentity = bluetooth.bootstrap.accessoryIdentityFingerprint(),purpose = PhoneBootstrapPurpose.CONTROL_SESSION)
        wifi.identify(false,true,listOf(0x4e09))
        wifi.requestChallenge()
        assertFalse(wifi.challenge().contentEquals(bluetooth.challenge()))
        assertThrows(IllegalStateException::class.java) { wifi.bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0,oldProof) }) }
        assertFalse(wifi.bootstrap.status().authenticated || wifi.bootstrap.status().controlReady)
        assertEquals(0xaa04,wifi.sent.last().messageId)
    }

    private class Fixture(address: String = ADDRESS, profile: PhoneBootstrapProfile = PhoneBootstrapProfile.VIVO_REFERENCE,
        private val key: java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
        expectedIdentity: ByteArray? = null, purpose: PhoneBootstrapPurpose = PhoneBootstrapPurpose.WIRELESS_CONFIGURATION) {
        private val certificate = BootstrapCertificate(key.public)
        val sent = mutableListOf<Iap2Frame>()
        val stages = mutableListOf<String>()
        var endpoint: WirelessEndpoint? = null
        var transportReads = 0
        val bootstrap = PhoneBootstrap(UUID, {
            assertArrayEquals(byteArrayOf(42), it)
            certificate
        }, { leaf, challenge, response -> Signature.getInstance("NONEwithECDSA").run {
            initVerify(leaf.publicKey); update(challenge); verify(response)
        } }, sent::add, stages::add, { endpoint = it }, profile = profile,
            transportIdentifier = { transportReads++; address }, expectedAccessoryIdentity = expectedIdentity,purpose = purpose)

        fun identify(modern: Boolean, uuidSupported: Boolean, received: List<Int>? = null, malformedTransport: Boolean = false,
            componentId: Int? = 3, serial: String? = null) {
            bootstrap.begin()
            bootstrap.receive(Iap2Messages.buildRaw(0x1d01) {
                string(0,"Isolated test car"); string(1,"Test model"); string(2,"Test manufacturer")
                if (serial != null) string(3, serial)
                u16List(6, if (modern) listOf(0x4301) else listOf(0x5703))
                u16List(7, received ?: (listOf(0x4e09,0x4e0a,0x4e0d,0x4e0e) + (if (modern) listOf(0x4300) else listOf(0x5702)) +
                    (if (uuidSupported) listOf(0x4e0c) else emptyList()))
                )
                if (malformedTransport) bytes(17,byteArrayOf(1,2,3)) else group(17) { u16(0,2); void(2) }
                if (modern && componentId != null) group(24) { u16(0,componentId); void(2); void(4) }
            })
        }
        fun requestChallenge() { bootstrap.receive(Iap2Messages.buildRaw(0xaa01) { bytes(0,byteArrayOf(42)) }) }
        fun challenge() = Iap2BodyReader.of(sent.first { it.messageId == 0xaa02 }).bytes(0)
        fun authenticate() {
            requestChallenge()
            val proof = Signature.getInstance("NONEwithECDSA").run { initSign(key.private); update(challenge()); sign() }
            bootstrap.receive(Iap2Messages.buildRaw(0xaa03) { bytes(0,proof) })
        }
        companion object {
            const val ADDRESS = "12:34:56:78:9A:BC"
            const val UUID = "12345678-1234-4321-abcd-123456789012"
        }
    }

    // Isolated controller test double: certificate-chain validation is injected, while challenge
    // verification uses a fresh real EC key and JCA signature. Unused certificate APIs fail loudly.
    private class BootstrapCertificate(private val key: PublicKey) : X509Certificate() {
        override fun getPublicKey(): PublicKey = key
        private fun unused(): Nothing = throw UnsupportedOperationException("Not used by isolated bootstrap fixture")
        override fun checkValidity() = unused()
        override fun checkValidity(date: Date?) = unused()
        override fun getVersion(): Int = unused()
        override fun getSerialNumber(): BigInteger = unused()
        override fun getIssuerDN(): Principal = unused()
        override fun getSubjectDN(): Principal = unused()
        override fun getNotBefore(): Date = unused()
        override fun getNotAfter(): Date = unused()
        override fun getTBSCertificate(): ByteArray = unused()
        override fun getSignature(): ByteArray = unused()
        override fun getSigAlgName(): String = unused()
        override fun getSigAlgOID(): String = unused()
        override fun getSigAlgParams(): ByteArray = unused()
        override fun getIssuerUniqueID(): BooleanArray = unused()
        override fun getSubjectUniqueID(): BooleanArray = unused()
        override fun getKeyUsage(): BooleanArray = unused()
        override fun getBasicConstraints(): Int = unused()
        override fun getEncoded(): ByteArray = unused()
        override fun verify(key: PublicKey?) = unused()
        override fun verify(key: PublicKey?, provider: String?) = unused()
        override fun hasUnsupportedCriticalExtension(): Boolean = unused()
        override fun getCriticalExtensionOIDs(): Set<String> = unused()
        override fun getNonCriticalExtensionOIDs(): Set<String> = unused()
        override fun getExtensionValue(oid: String?): ByteArray = unused()
        override fun toString(): String = "Isolated bootstrap public-key fixture"
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
