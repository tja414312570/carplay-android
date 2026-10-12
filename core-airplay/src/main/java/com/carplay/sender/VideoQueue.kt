package com.carplay.sender

import java.util.ArrayDeque

data class EncodedVideo(val bytes: ByteArray, val ptsUs: Long, val keyFrame: Boolean, val queuedAtNanos: Long = System.nanoTime())

/** Once dependent frames are dropped, resume only on a new independently decodable frame. */
class VideoQueue(private val capacity: Int = 8, private val byteLimit: Int = 8 * 1024 * 1024) {
    private val lock = Object()
    private val frames = ArrayDeque<EncodedVideo>()
    private var bytes = 0
    private var closed = false
    private var waitingForKeyFrame = true
    var dropped: Long = 0; private set
    init { require(capacity > 0 && byteLimit > 0) }
    fun offer(frame: EncodedVideo): Boolean = synchronized(lock) {
        if (closed) return false
        require(frame.bytes.size in 1..byteLimit)
        if (frames.size >= capacity || bytes + frame.bytes.size > byteLimit) {
            dropped += frames.size; frames.clear(); bytes = 0; waitingForKeyFrame = true
        }
        if (waitingForKeyFrame && !frame.keyFrame) { dropped++; return false }
        waitingForKeyFrame = false
        frames.addLast(frame.copy(bytes = frame.bytes.copyOf())); bytes += frame.bytes.size; lock.notifyAll(); true
    }
    fun take(): EncodedVideo? = synchronized(lock) {
        while (frames.isEmpty() && !closed) lock.wait()
        if (frames.isEmpty()) null else frames.removeFirst().also { bytes -= it.bytes.size }
    }
    fun close() = synchronized(lock) { closed = true; frames.clear(); bytes = 0; lock.notifyAll() }
}
