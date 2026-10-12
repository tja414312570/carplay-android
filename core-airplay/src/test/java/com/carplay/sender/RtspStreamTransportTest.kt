package com.carplay.sender

import com.carplay.airplay.ControlCipher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress

class RtspStreamTransportTest {
    @Test fun ownedStreamInvitationPreservesRequestFramingAndHttpResponseBytes() {
        val input = ByteArrayInputStream("GET /ctrl-int/1/connect HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val output = ByteArrayOutputStream()
        var closed = false
        RtspWire({input},{output},{closed = true}).use { wire ->
            val request = wire.next()
            assertEquals("GET /ctrl-int/1/connect HTTP/1.1",request.firstLine)
            wire.respond(request,null)
        }
        assertTrue(closed)
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",output.toString("US-ASCII"))
    }
    @Test fun encryptedSocketAndStreamSuppliersRetainCipherCountersSequenceAndResponseRouting() {
        val key = ByteArray(32) { it.toByte() }
        ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(),server.localPort).use { client ->
                server.accept().use { peer ->
                    val phone = RtspWire(client).apply { cipher = ControlCipher(key,key) }
                    val car = RtspWire(peer::getInputStream,peer::getOutputStream,peer::close).apply { cipher = ControlCipher(key,key) }
                    val response = java.util.concurrent.CompletableFuture.supplyAsync { phone.request("POST","/feedback") }
                    val request = car.next()
                    assertEquals("1",request.headers["cseq"])
                    car.respond(request,byteArrayOf(1,2,3))
                    assertArrayEquals(byteArrayOf(1,2,3),response.get(3,java.util.concurrent.TimeUnit.SECONDS).body)
                }
            }
        }
    }
}
