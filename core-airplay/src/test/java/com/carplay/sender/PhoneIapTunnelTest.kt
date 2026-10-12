package com.carplay.sender

import com.carplay.airplay.ControlCipher
import com.carplay.airplay.IapTunnel
import com.carplay.airplay.NtpClock
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PhoneIapTunnelTest {
    @Test fun tcpPackagesDecryptWithOriginalAccessoryAndPreserveBodies() {
        val key = ByteArray(32) { it.toByte() }
        val received = CompletableFuture<ByteArray>()
        IapTunnel(key).use { accessory ->
            val port = accessory.listen(object : IapTunnel.Listener {
                override fun onIap(bytes: ByteArray) { received.complete(bytes) }
                override fun onClosed(cause: Throwable?) { if (cause != null) received.completeExceptionally(cause) }
            })
            PhoneIapTunnel(Socket("127.0.0.1",port),key).use { phone ->
                val data = ByteArray(32768) { (it % 251).toByte() }
                phone.send(data)
                assertArrayEquals(data,received.get(3,TimeUnit.SECONDS))
            }
        }
    }
    @Test fun goldenHeaderAndIndependentCipherCountersMatchReference() {
        val bytes = ByteArrayOutputStream()
        val socket = object : Socket() { override fun getOutputStream() = bytes }
        val key = ByteArray(32) { 11 }
        PhoneIapTunnel(socket,key).use { phone -> phone.send(byteArrayOf(1,2)); phone.send(byteArrayOf(3)) }
        val plain = ControlCipher(key,key).decrypt(bytes.toByteArray())
        assertTrue(plain.rest.isEmpty())
        val header = ByteBuffer.wrap(plain.data).order(ByteOrder.BIG_ENDIAN)
        assertEquals(34,header.getInt(0)); assertEquals(0x636f6d6d,header.getInt(16))
        assertArrayEquals(byteArrayOf(1,2),plain.data.copyOfRange(32,34))
        assertEquals(33,header.getInt(34)); assertEquals(0x636f6d6d,header.getInt(50))
        assertEquals(3,plain.data.last().toInt())
    }
    @Test fun eventInputIsCopiedSplitBoundedAndWokenOnClose() {
        val phone = PhoneIapTunnel(Socket(),ByteArray(32))
        val data = byteArrayOf(1,2,3); assertTrue(phone.offer(data)); data[0] = 99
        assertArrayEquals(byteArrayOf(1,2),phone.recv(2,0)); assertArrayEquals(byteArrayOf(3),phone.recv(2,0))
        assertNull(phone.recv(8,1))
        val waiting = CompletableFuture.supplyAsync { phone.recv(8,5000) }
        phone.close(); assertTrue(waiting.get(1,TimeUnit.SECONDS)!!.isEmpty())
        assertFalse(phone.offer(byteArrayOf(1)))
        val overflow = PhoneIapTunnel(Socket(),ByteArray(32))
        assertTrue(overflow.offer(ByteArray(1_048_576)))
        assertFalse(overflow.offer(byteArrayOf(1)))
        assertThrows(IOException::class.java) { overflow.recv(1,0) }; overflow.close()
    }
    @Test fun sendHandlerTransmitsRawBytesWithoutSocket() {
        val sent = CompletableFuture<ByteArray>()
        PhoneIapTunnel(sendHandler = { sent.complete(it) }).use { phone ->
            val payload = byteArrayOf(10, 20, 30)
            phone.send(payload)
            assertArrayEquals(payload, sent.get(1, TimeUnit.SECONDS))
        }
    }
    @Test fun failedNetworkBindingClosesTimingSocketAndAllowsRetry() {
        var captured: DatagramSocket? = null; var fail = true
        NtpClock { socket -> captured = socket; if (fail) throw IOException("binding rejected") }.use { clock ->
            assertThrows(IOException::class.java) { clock.listen() }
            assertTrue(captured!!.isClosed)
            fail = false
            assertTrue(clock.listen() > 0)
        }
        assertTrue(captured!!.isClosed)
    }
}
