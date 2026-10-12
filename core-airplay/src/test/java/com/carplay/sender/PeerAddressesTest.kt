package com.carplay.sender

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class PeerAddressesTest {
    @Test fun gmLinkLocalAddressUsesTheActualPhoneInterfaceInsteadOfTheCarsScope() {
        val local = Inet6Address.getByAddress(null,InetAddress.getByName("fe80::2").address,7)
        val result = PeerAddresses.resolve(listOf("fe80::1%car-interface"),listOf(local),7).single()
        assertEquals(7,(result.remote as Inet6Address).scopeId)
        assertEquals(7,(result.local as Inet6Address).scopeId)
        assertArrayEquals(InetAddress.getByName("fe80::1").address,result.remote.address)
    }
    @Test fun ipv4AvailabilityCannotPrematurelyCompleteAnIpv6OnlyNetworkRequest() {
        assertTrue(PeerAddresses.resolve(listOf("fe80::1"),listOf(InetAddress.getByName("192.168.50.2")),7).isEmpty())
        assertTrue(PeerAddresses.resolve(listOf("fe80::1"),listOf(InetAddress.getByName("fe80::2")),0).isEmpty())
    }
    @Test fun addressesCompareCanonicalBytesAndCanFallBackToAnotherAdvertisedFamily() {
        val locals = listOf(InetAddress.getByName("192.168.50.2"))
        val routes = PeerAddresses.resolve(listOf("fe80::1","192.168.50.1"),locals,7)
        assertEquals("192.168.50.1",routes.single().remote.hostAddress)
        assertTrue(PeerAddresses.matches(listOf("fe80:0:0:0:0:0:0:1%car-if"),InetAddress.getByName("fe80::1")))
        assertFalse(PeerAddresses.matches(listOf("fe80::1"),InetAddress.getByName("fe80::3")))
    }
    @Test fun globalIpv6CannotUseOnlyAnUnroutableLinkLocalSource() {
        assertTrue(PeerAddresses.resolve(listOf("2001:db8::1"),listOf(InetAddress.getByName("fe80::2")),7).isEmpty())
        assertEquals(1,PeerAddresses.resolve(listOf("2001:db8::1"),listOf(InetAddress.getByName("2001:db8::2")),7).size)
    }
    @Test fun hostnamesInvalidNumbersAndNonUnicastTargetsAreRejectedWithoutDnsFallback() {
        for (value in listOf("car.example","127.0.0.1","::1","0.0.0.0","::","ff02::fb","192.168.500.1","fe80::1%bad/scope"," 192.168.50.1")) {
            assertThrows(Exception::class.java) { PeerAddresses.parse(value) }
        }
    }
}
