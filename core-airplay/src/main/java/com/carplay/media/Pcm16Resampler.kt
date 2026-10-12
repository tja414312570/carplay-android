package com.carplay.media

import java.io.ByteArrayOutputStream

/** Streaming stereo PCM16 conversion. Integer phase preserves timing across callback boundaries. */
class Pcm16Resampler(private val inputRate: Int, private val outputRate: Int, private val channels: Int = 2) {
    init { require(inputRate in listOf(44100,48000) && outputRate in listOf(44100,48000) && channels == 2) }
    private var inputFrames = 0L
    private var nextOutput = 0L
    private var previous = IntArray(channels)

    fun convert(data: ByteArray): ByteArray {
        require(data.size % (channels * 2) == 0)
        if (inputRate == outputRate) return data.copyOf()
        val capacity = data.size.toLong() * outputRate / inputRate + channels * 2
        require(capacity <= Int.MAX_VALUE)
        val output = ByteArrayOutputStream(capacity.toInt())
        for (offset in data.indices step channels * 2) {
            val current = IntArray(channels) { channel ->
                val at = offset + channel * 2
                ((data[at].toInt() and 255) or (data[at+1].toInt() shl 8)).toShort().toInt()
            }
            val index = inputFrames++
            while (nextOutput <= index * outputRate) {
                val fraction = if (index == 0L) 0L else nextOutput - (index-1) * outputRate
                for (channel in 0 until channels) {
                    val value = if (index == 0L) current[channel] else
                        ((previous[channel].toLong() * (outputRate-fraction) + current[channel].toLong() * fraction) / outputRate).toInt()
                    output.write(value and 255); output.write((value shr 8) and 255)
                }
                nextOutput += inputRate
            }
            previous = current
        }
        return output.toByteArray()
    }
}
