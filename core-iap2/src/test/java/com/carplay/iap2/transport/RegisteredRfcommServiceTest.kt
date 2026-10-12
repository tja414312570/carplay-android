package com.carplay.iap2.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RegisteredRfcommServiceTest {
    @Test fun repeatedAcquisitionReusesOneRegistrationUntilSessionTeardown() {
        var registrations = 0
        val closes = AtomicInteger()
        val socket = AutoCloseable { closes.incrementAndGet() }
        val service = RegisteredRfcommService { registrations++; socket }
        assertSame(socket,service.acquire())
        assertSame(socket,service.acquire())
        assertEquals(1,registrations)
        assertEquals(0,closes.get())
        service.close(); service.close()
        assertEquals(1,closes.get())
        assertThrows(IllegalStateException::class.java) { service.acquire() }
        assertEquals(1,registrations)
    }

    @Test fun closingBeforeRegistrationNeverOpensAService() {
        var registrations = 0
        val service = RegisteredRfcommService { registrations++; AutoCloseable {} }
        service.close()
        assertThrows(IllegalStateException::class.java) { service.acquire() }
        assertEquals(0,registrations)
    }

    @Test fun failedRegistrationDoesNotCacheAnInvalidHandle() {
        var attempts = 0
        val closes = AtomicInteger()
        val socket = AutoCloseable { closes.incrementAndGet() }
        val service = RegisteredRfcommService {
            attempts++
            if (attempts == 1) throw IOException("registration failed")
            socket
        }
        assertThrows(IOException::class.java) { service.acquire() }
        assertSame(socket,service.acquire())
        assertEquals(2,attempts)
        service.close()
        assertEquals(1,closes.get())
    }

    @Test fun teardownCanInterruptAnAcceptThatWaitsOutsideTheRegistrationLock() {
        val acquired = CountDownLatch(1)
        val released = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val service = RegisteredRfcommService { AutoCloseable { released.countDown() } }
        val worker = Thread {
            service.acquire()
            acquired.countDown()
            released.await(2,TimeUnit.SECONDS)
            completed.countDown()
        }
        worker.start()
        try {
            assertTrue(acquired.await(2,TimeUnit.SECONDS))
            service.close()
            assertTrue(completed.await(2,TimeUnit.SECONDS))
            assertEquals(0L,released.count)
        } finally { service.close(); worker.interrupt(); worker.join(2000) }
        assertFalse(worker.isAlive)
    }

    @Test fun closeFailureCannotReopenOrDoubleCloseTheSameService() {
        var closes = 0
        val service = RegisteredRfcommService { AutoCloseable { closes++; throw IOException("close failed") } }
        service.acquire()
        assertThrows(IOException::class.java) { service.close() }
        service.close()
        assertEquals(1,closes)
        assertThrows(IllegalStateException::class.java) { service.acquire() }
    }
}
