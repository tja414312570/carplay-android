package com.carplay.sender

import com.carplay.airplay.ControlCipher
import com.carplay.iap2.transport.BlockingDuplexByteStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** Phone side of Wi-Fi iAP2: encrypted APTransportPackage TCP output (type 130) or RTSP iAPSendMessage command. */
class PhoneIapTunnel(
    private val socket: Socket? = null,
    writeKey: ByteArray? = null,
    private val sendHandler: ((ByteArray) -> Unit)? = null,
) : BlockingDuplexByteStream {
    val isCommandTunnel: Boolean get() = socket == null
    private val cipher = if (writeKey != null) ControlCipher(writeKey, writeKey) else null
    private val lock = Object()
    private val writeLock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var failure: IOException? = null

    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var overflow = false
        synchronized(lock) {
            if (closed || failure != null) return false
            if (bytes.size > MAX_PENDING_BYTES - pendingBytes) {
                failure = IOException("Wi-Fi iAP2 接收队列超出限制")
                overflow = true
            } else {
                pending.addLast(bytes.copyOf()); pendingBytes += bytes.size
            }
            lock.notifyAll()
        }
        if (overflow) socket?.close()
        return !overflow
    }

    override fun send(data: ByteArray) {
        require(data.size in 1..65535)
        synchronized(writeLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Wi-Fi iAP2 通道已关闭")
            }
            try {
                if (socket != null && cipher != null) {
                    val header = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                        .putInt(0, data.size + 32).putInt(16, 0x636f6d6d).array()
                    socket.getOutputStream().apply { write(cipher.encrypt(header + data)); flush() }
                } else if (sendHandler != null) {
                    sendHandler.invoke(data)
                } else {
                    throw IOException("Wi-Fi iAP2 通道无有效发送实现")
                }
            } catch (error: IOException) {
                synchronized(lock) { failure = error; lock.notifyAll() }
                socket?.close()
                throw error
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis in 0..86_400_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                if (closed) return ByteArray(0)
                pending.pollFirst()?.let { chunk ->
                    pendingBytes -= chunk.size
                    if (chunk.size <= maxBytes) return chunk
                    val tail = chunk.copyOfRange(maxBytes, chunk.size)
                    pending.addFirst(tail); pendingBytes += tail.size
                    return chunk.copyOf(maxBytes)
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try { lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt()) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; pending.clear(); pendingBytes = 0; lock.notifyAll()
        }
        socket?.close()
    }
    companion object { private const val MAX_PENDING_BYTES = 1_048_576 }
}
