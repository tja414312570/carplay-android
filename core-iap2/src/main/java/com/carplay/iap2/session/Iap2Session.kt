package com.carplay.iap2.session

import com.carplay.iap2.body.Iap2BodyBuilder
import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.catalog.Iap2Endpoint
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.trace.Iap2FrameFormatter
import com.carplay.iap2.trace.Iap2TraceDirection
import com.carplay.iap2.wire.Iap2Frame
import com.carplay.iap2.transport.BlockingDuplexByteStream
import com.carplay.iap2.transport.Iap2CsmChannel
import com.carplay.iap2.transport.Iap2ArtworkTransfer
import com.carplay.iap2.transport.Iap2HandshakeStatus
import com.carplay.iap2.transport.PhoneBluetoothProfile
import com.carplay.iap2.transport.PhoneBluetoothHandshakeDeadline

/**
 * One immediately readable and writable iAP2 CSM session.
 *
 * [Iap2CsmChannel] remains the lower-level frame transport. This class is the protocol-facing
 * facade used by services: it accepts endpoint builders, sends complete frames, receives complete
 * frames, and exposes the typed body reader.
 */
class Iap2Session private constructor(
    private val channel: Iap2CsmChannel,
    private val traceContext: String,
    private val onTrace: (String) -> Unit,
) : AutoCloseable {
    val isClosed: Boolean get() = channel.isClosed

    fun handshakeStatus(): Iap2HandshakeStatus = channel.handshakeStatus()

    fun awaitPhoneBluetoothReady(profile: PhoneBluetoothProfile, maximumWaitMillis: Long? = null,
        cancelled: () -> Boolean = { false }): Boolean {
        require(maximumWaitMillis == null || maximumWaitMillis >= 0)
        val extraDeadline = maximumWaitMillis?.let { System.nanoTime() + minOf(it, profile.readyTimeoutMillis) * 1_000_000 }
        val deadline = PhoneBluetoothHandshakeDeadline(profile)
        var ready = false
        while (!isClosed && !cancelled()) {
            val remaining = minOf(deadline.remainingMillis(handshakeStatus()), extraDeadline?.let {
                ((it - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            } ?: Long.MAX_VALUE)
            if (remaining == 0L) break
            if (channel.awaitReady(minOf(remaining, 250))) { ready = true; break }
        }
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    fun awaitReady(timeoutMillis: Long): Boolean {
        val ready = channel.awaitReady(timeoutMillis)
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    fun awaitOutboundAcknowledged(timeoutMillis: Long): Boolean {
        val acknowledged = channel.awaitOutboundAcknowledged(timeoutMillis)
        emitTrace("IAP2 DRAIN [$traceContext] acknowledged=$acknowledged")
        return acknowledged
    }

    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        try {
            channel.send(frame, timeoutMillis)
            emitFrameTrace(Iap2TraceDirection.TX, frame)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.TX,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
            throw failure
        }
    }

    fun send(
        endpoint: Iap2Endpoint,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.build(endpoint, block), timeoutMillis)
    }

    fun sendRaw(
        messageId: Int,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.buildRaw(messageId, block), timeoutMillis)
    }

    fun recv(timeoutMillis: Long): Iap2Frame? {
        return try {
            channel.recv(timeoutMillis)?.also { emitFrameTrace(Iap2TraceDirection.RX, it) }
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.RX,
                    traceContext,
                    messageId = null,
                    failure = failure,
                ),
            )
            throw failure
        }
    }

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2Messages.reader(frame)

    override fun close() {
        try {
            channel.close()
        } finally {
            emitTrace("IAP2 CLOSE [$traceContext]")
        }
    }

    private fun emitFrameTrace(direction: Iap2TraceDirection, frame: Iap2Frame) {
        try {
            emitTrace(Iap2FrameFormatter.format(direction, traceContext, frame))
        } catch (failure: Exception) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    direction,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
        }
    }

    private fun emitTrace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change protocol behavior.
        }
    }

    companion object {
        fun phoneBluetooth(underlying: BlockingDuplexByteStream, profile: PhoneBluetoothProfile,
            onTrace: (String) -> Unit = {}): Iap2Session = Iap2Session(
                Iap2CsmChannel.openPhoneBluetooth(underlying, profile) { onTrace("IAP2 LINK [phone/${profile.label}] $it") },
                "phone/${profile.label}", onTrace)

        fun controller(
            underlying: BlockingDuplexByteStream,
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(Iap2CsmChannel.openController(underlying), "phone", onTrace)

        fun controllerTunnel(
            underlying: BlockingDuplexByteStream,
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(Iap2CsmChannel.openControllerTunnel(underlying), "phone-wifi", onTrace)

        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        fun open(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wired",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.open(underlying, onArtwork), traceContext, onTrace)

        fun openWireless(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openWireless(underlying, onArtwork), traceContext, onTrace)

        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless-tunnel",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openTunnel(underlying, onArtwork), traceContext, onTrace)

        fun wrap(
            channel: Iap2CsmChannel,
            traceContext: String = "iap2",
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(channel, traceContext, onTrace)
    }
}


