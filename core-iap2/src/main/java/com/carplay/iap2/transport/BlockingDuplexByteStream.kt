package com.carplay.iap2.transport

import java.io.Closeable

/**
 * A blocking, ordered, full-duplex byte stream (RFCOMM, TCP, USB).
 */
interface BlockingDuplexByteStream : Closeable {
    fun send(data: ByteArray)
    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray?
    override fun close()
}

