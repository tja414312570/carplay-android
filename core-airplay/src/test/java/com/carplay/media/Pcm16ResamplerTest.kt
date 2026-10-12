package com.carplay.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16ResamplerTest {
    private fun samples(frames: Int): ByteArray = ByteBuffer.allocate(frames*4).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(frames) { putShort(((it*37)%60000-30000).toShort()); putShort((30000-(it*61)%60000).toShort()) }
    }.array()

    @Test fun sameRatePreservesEverySignedStereoSampleAndCopiesOwnership() {
        val original = samples(100)
        val output = Pcm16Resampler(48000,48000).convert(original)
        assertArrayEquals(original,output)
        assertNotSame(original,output)
    }
    @Test fun arbitraryCallbackBoundariesProduceExactlyTheSameConvertedStream() {
        val data = samples(4800)
        for ((input,output) in listOf(48000 to 44100,44100 to 48000)) {
            val expected = Pcm16Resampler(input,output).convert(data)
            val resampler = Pcm16Resampler(input,output)
            val parts = java.io.ByteArrayOutputStream()
            var offset = 0
            while (offset < data.size) {
                val size = minOf(data.size-offset,((offset/4)%37+1)*4)
                parts.write(resampler.convert(data.copyOfRange(offset,offset+size))); offset += size
            }
            assertArrayEquals(expected,parts.toByteArray())
        }
    }
    @Test fun outputCountFollowsTheRealSampleRateWithoutClockResetAtEachBuffer() {
        assertEquals(44100*4,Pcm16Resampler(48000,44100).convert(samples(48000)).size)
        assertEquals(47999*4,Pcm16Resampler(44100,48000).convert(samples(44100)).size)
    }
    @Test fun constantSignedChannelsRemainIndependentAndUnchanged() {
        val input = ByteBuffer.allocate(400).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(100) { putShort(-32768); putShort(32767) } }.array()
        val output = ByteBuffer.wrap(Pcm16Resampler(48000,44100).convert(input)).order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) { assertEquals(-32768,output.short.toInt()); assertEquals(32767,output.short.toInt()) }
    }
    @Test fun invalidRatesOrPartialStereoFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Pcm16Resampler(16000,44100) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Resampler(48000,44100).convert(ByteArray(3)) }
    }
}
