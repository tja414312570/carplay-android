package com.carplay.iap2.transport

import java.io.IOException

/** The connection owns the byte stream; each protocol epoch gets one non-overlapping lease. */
class PhoneBluetoothSessionTransport(private val underlying: BlockingDuplexByteStream) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var current: Lease? = null

    fun open(initialInput: ByteArray = byteArrayOf()): BlockingDuplexByteStream = synchronized(lock) {
        check(!closed) { "蓝牙连接已关闭" }
        check(current?.closed != false) { "旧 iAP2 协议实例尚未关闭" }
        check(current?.operations?.let { it == 0 } != false) { "旧 iAP2 协议实例仍在读写" }
        require(initialInput.size <= Iap2LinkEngine.MAX_WIRE_FRAME_BYTES) { "重新检测的待处理数据过多" }
        Lease(initialInput.copyOf()).also { current = it }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current?.closed = true
        }
        // Never hold the owner lock while closing a stream that unblocks a worker.
        underlying.close()
    }

    private inner class Lease(private var initialInput: ByteArray) : BlockingDuplexByteStream {
        var closed = false // guarded by the owner's lock
        var operations = 0 // the owner cannot open the next lease until old I/O returns

        private fun checkOpen() {
            if (closed || this@PhoneBluetoothSessionTransport.closed || current !== this) {
                throw IOException("旧 iAP2 协议实例已关闭")
            }
        }

        override fun send(data: ByteArray) {
            synchronized(lock) { checkOpen(); operations++ }
            try { underlying.send(data) } finally { synchronized(lock) { operations-- } }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            require(maxBytes > 0 && timeoutMillis >= 0)
            synchronized(lock) {
                checkOpen()
                if (initialInput.isNotEmpty()) {
                    val size = minOf(maxBytes, initialInput.size)
                    val next = initialInput.copyOfRange(0, size)
                    initialInput = initialInput.copyOfRange(size, initialInput.size)
                    return next
                }
                operations++
            }
            try {
                val next = underlying.recv(maxBytes, timeoutMillis)
                synchronized(lock) { checkOpen() }
                return next
            } finally { synchronized(lock) { operations-- } }
        }

        override fun close() = synchronized(lock) {
            closed = true
            initialInput = byteArrayOf()
        }
    }
}

/** Only a complete detection marker at a link-frame boundary can produce this signal. */
class Iap2PeerDetectionRestart internal constructor(reason: String, initialInput: ByteArray) : IOException(reason) {
    private val pending = initialInput.copyOf()
    fun initialInput(): ByteArray = pending.copyOf()
}
