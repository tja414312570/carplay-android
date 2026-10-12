package com.carplay.iap2.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneBluetoothSessionTransportTest {
    @Test fun epochCloseKeepsTheOwnedTransportButRejectsOldWritesAndNewOverlappingReaders() {
        val wire = Wire()
        val owner = PhoneBluetoothSessionTransport(wire)
        val first = owner.open()
        assertThrows(IllegalStateException::class.java) { owner.open() }
        first.send(byteArrayOf(1))
        first.close()
        assertEquals(0, wire.closes.get())
        assertThrows(IOException::class.java) { first.send(byteArrayOf(2)) }
        assertThrows(IOException::class.java) { first.recv(8, 0) }
        val second = owner.open(byteArrayOf(3, 4, 5))
        assertArrayEquals(byteArrayOf(3, 4), second.recv(2, 0))
        assertArrayEquals(byteArrayOf(5), second.recv(2, 0))
        assertArrayEquals(byteArrayOf(9), second.recv(2, 0))
        first.close() // a late close of the old lease cannot close the new one
        second.send(byteArrayOf(6))
        assertEquals(listOf(1, 6), wire.writes)
        owner.close(); owner.close(); second.close()
        assertEquals(1, wire.closes.get())
        assertThrows(IllegalStateException::class.java) { owner.open() }
    }

    @Test fun nextEpochCannotReadUntilTheOldBlockedReadHasReturned() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val owner = PhoneBluetoothSessionTransport(object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = error("No writes expected")
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
                entered.countDown()
                check(released.await(2, TimeUnit.SECONDS))
                return byteArrayOf(7)
            }
            override fun close() { released.countDown() }
        })
        val first = owner.open()
        val rejected = AtomicInteger()
        val reader = Thread {
            try { first.recv(8, 1000) } catch (_: IOException) { rejected.incrementAndGet() }
        }
        reader.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            first.close()
            assertThrows(IllegalStateException::class.java) { owner.open() }
            released.countDown(); reader.join(2000)
            assertFalse(reader.isAlive)
            assertEquals(1, rejected.get())
            owner.open().close()
        } finally { owner.close(); reader.join(2000) }
    }

    @Test fun stoppingTheOwnerCancelsAnActiveLeaseAndClosesExactlyOnce() {
        val wire = Wire()
        val owner = PhoneBluetoothSessionTransport(wire)
        val lease = owner.open()
        owner.close()
        assertThrows(IOException::class.java) { lease.send(byteArrayOf(1)) }
        assertThrows(IOException::class.java) { lease.recv(1, 0) }
        owner.close(); lease.close()
        assertEquals(1, wire.closes.get())
    }

    @Test fun ownerCancellationReleasesABlockedPhysicalReadWithoutStartingAnotherEpoch() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val closes = AtomicInteger()
        val owner = PhoneBluetoothSessionTransport(object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = error("No writes expected")
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
                entered.countDown()
                check(released.await(2, TimeUnit.SECONDS))
                return byteArrayOf()
            }
            override fun close() { closes.incrementAndGet(); released.countDown() }
        })
        val lease = owner.open()
        val reader = Thread {
            try { lease.recv(8, 1000) } catch (_: IOException) { stopped.countDown() }
        }
        reader.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.close()
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { owner.open() }
        } finally { owner.close(); reader.join(2000) }
        assertFalse(reader.isAlive)
        assertEquals(1, closes.get())
    }

    @Test fun preservedInputHasABoundAndIsCopiedInsteadOfSharingMutableFailureData() {
        val wire = Wire()
        val owner = PhoneBluetoothSessionTransport(wire)
        assertThrows(IllegalArgumentException::class.java) { owner.open(ByteArray(65_536)) }
        val prefix = byteArrayOf(1, 2)
        val lease = owner.open(prefix)
        prefix[0] = 99
        assertArrayEquals(byteArrayOf(1, 2), lease.recv(8, 0))
        val signal = Iap2PeerDetectionRestart("detection", prefix)
        prefix[0] = 3
        val copy = signal.initialInput(); copy[0] = 4
        assertEquals(99.toByte(), signal.initialInput()[0])
        owner.close()
    }

    private class Wire : BlockingDuplexByteStream {
        val closes = AtomicInteger()
        val writes = mutableListOf<Int>()
        override fun send(data: ByteArray) { writes += data[0].toInt() }
        override fun recv(maxBytes: Int, timeoutMillis: Long) = byteArrayOf(9)
        override fun close() { closes.incrementAndGet() }
    }
}
