package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.*
import com.carplay.iap2.wire.Iap2Frame
import java.security.cert.CertificateException
import org.junit.Assert.*
import org.junit.Test

class PhoneBootstrapTest {
    @Test fun beginningIdentificationCannotReportAuthenticatedOrWirelessReady() {
        val sent = mutableListOf<Iap2Frame>()
        val states = mutableListOf<BootstrapStatus>()
        val bootstrap = PhoneBootstrap("phone",{ error("No certificate requested") },{ _,_,_ -> false },
            sent::add,{}, { fail("No network before authentication") }, onStatus = states::add)
        bootstrap.begin()
        assertEquals(listOf(0x1d00),sent.map { it.messageId })
        assertEquals(BootstrapPhase.IDENTIFY,states.single().phase)
        assertFalse(bootstrap.status().identified)
        assertFalse(bootstrap.status().authenticated)
        assertTrue(bootstrap.status().timeoutDetail().contains("0x1d01"))
    }

    @Test fun actualIdentificationRequestsCertificateAndReportsItAsPending() {
        val sent = mutableListOf<Iap2Frame>()
        val bootstrap = PhoneBootstrap("phone",{ error("Certificate has not arrived") },{ _,_,_ -> false },
            sent::add,{}, { fail("No network before authentication") })
        bootstrap.begin()
        bootstrap.receive(identity())
        assertEquals(listOf(0x1d00,0x1d02,0xaa00),sent.map { it.messageId })
        assertTrue(bootstrap.status().identified)
        assertFalse(bootstrap.status().authenticated)
        assertEquals(BootstrapPhase.CERTIFICATE,bootstrap.status().phase)
        assertTrue(bootstrap.status().timeoutDetail().contains("0xaa01"))
    }

    @Test fun rejectedCertificateCannotAdvanceAuthenticationOrReleaseNetwork() {
        val bootstrap = PhoneBootstrap("phone",{ throw CertificateException("untrusted test certificate") },
            { _,_,_ -> fail("No signature verification without an accepted certificate"); false },{}, {},
            { fail("No network after rejected certificate") })
        bootstrap.begin()
        bootstrap.receive(identity())
        assertThrows(CertificateException::class.java) {
            bootstrap.receive(Iap2Messages.buildRaw(0xaa01) { bytes(0,byteArrayOf(1,2,3)) })
        }
        assertFalse(bootstrap.status().authenticated)
        assertFalse(bootstrap.status().certificateReceived)
        assertEquals(BootstrapPhase.CERTIFICATE,bootstrap.status().phase)
    }

    @Test fun throwingStatusObserverCannotPreventCertificateRequest() {
        val sent = mutableListOf<Iap2Frame>()
        val bootstrap = PhoneBootstrap("phone",{ error("Not requested") },{ _,_,_ -> false },sent::add,{}, {},
            onStatus = { throw IllegalStateException("UI observer failed") })
        bootstrap.begin()
        bootstrap.receive(identity())
        assertEquals(listOf(0x1d00,0x1d02,0xaa00),sent.map { it.messageId })
        assertFalse(bootstrap.status().authenticated)
    }

    @Test fun unknownControlDoesNotInventIdentityProgressOrRepeatStatus() {
        var changes = 0
        val bootstrap = PhoneBootstrap("phone",{ error("No certificate") },{ _,_,_ -> false },{}, {}, {},
            onStatus = { changes++ })
        bootstrap.begin()
        bootstrap.receive(Iap2Messages.buildRaw(0x5000) {})
        assertEquals(1,changes)
        assertEquals(BootstrapPhase.IDENTIFY,bootstrap.status().phase)
    }

    private fun identity() = Iap2Messages.buildRaw(0x1d01) {
        string(0,"Isolated test car"); string(1,"Test model"); string(2,"Test manufacturer")
        u16List(6,listOf(0x4301)); u16List(7,listOf(0x4300))
    }

    @Test fun availabilityRoundTripsActualTransportIdentifier() {
        val frame = Iap2CarPlayMessages.buildAvailability(bluetoothTransportIdentifier="phone-transport-42")
        assertEquals("phone-transport-42",Iap2BodyReader.of(frame).group(1).string(1))
        assertEquals("phone-transport-42",Iap2CarPlayMessages.availability(frame).wireless!!.identifier)
    }
    @Test fun wifiBeforeIdentityAndAuthenticationCannotTriggerJoin() {
        val bootstrap = PhoneBootstrap("phone",{ error("No certificate") },{ _,_,_ -> false },{}, {}, { fail("Unauthenticated network must never be joined") })
        assertThrows(IllegalStateException::class.java) {
            bootstrap.receive(Iap2WirelessMessages.accessoryWiFiConfiguration("car","password123",36,2))
        }
    }
    @Test fun configurationMergeRetainsEndpointAndRejectsChangedCredentials() {
        val first = WirelessEndpoint("car","password123",2,36,listOf("192.168.1.1"),7000,"id","key")
        assertEquals(first,first.merge(first.copy(addresses=emptyList(),port=null,receiverId=null,publicKey=null)))
        assertThrows(IllegalArgumentException::class.java) { first.merge(first.copy(password="other-password")) }
        assertThrows(IllegalArgumentException::class.java) { WirelessEndpoint("car","password123",1,36) }
    }
}
