package com.carplay.adapter.generic

import android.content.Context
import android.view.Surface

/** A host-authorized Surface source. The existing MediaProjection source remains independent. */
interface ProjectionSurfaceSource : AutoCloseable {
    val inputAvailable: Boolean
    val microphoneAvailable: Boolean
    fun attach(context: Context, diagnostic: (String) -> Unit, failed: (Throwable) -> Unit)
    fun start(surface: Surface, width: Int, height: Int, fps: Int, pcm: (ByteArray,Int,Int,Int) -> Unit)
    fun touch(action: Int, x: Float, y: Float, pointer: Int): Boolean
    fun key(code: Int, down: Boolean): Boolean
    fun microphone(pcm: ByteArray, sampleRate: Int, channels: Int): Boolean
}
