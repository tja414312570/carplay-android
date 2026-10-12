package com.carplay.iap2.transport

/** Explicit phone compatibility profiles; legacy receiver factories retain their original configuration. */
enum class PhoneBluetoothProfile(val label: String, val readyTimeoutMillis: Long) {
    ACCESSORY_FIRST("accessory-first", 25_000),
    VIVO_ACCESSORY_FIRST("vivo-accessory-first", 25_000),
    CAPTURE_IMMEDIATE("detection-then-phone-syn", 10_000),
    LEGACY_CONTROLLER("legacy-immediate-controller", 10_000);

    val accessoryInitiates: Boolean
        get() = this == ACCESSORY_FIRST || this == VIVO_ACCESSORY_FIRST

    fun attempts(): List<PhoneBluetoothProfile> = when (this) {
        ACCESSORY_FIRST, VIVO_ACCESSORY_FIRST -> listOf(this, CAPTURE_IMMEDIATE, LEGACY_CONTROLLER)
        CAPTURE_IMMEDIATE -> listOf(this, LEGACY_CONTROLLER)
        LEGACY_CONTROLLER -> listOf(this)
    }

    internal fun linkConfig(): Iap2LinkConfig {
        if (this == LEGACY_CONTROLLER) return Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2)
        val responder = accessoryInitiates
        val synchronization = Iap2LinkEngine.SynchronizationPayload(
            maxOutgoing = if (responder) 5 else 127,
            maxLength = 2048,
            retransmissionTimeoutMillis = if (responder) 1500 else 2000,
            acknowledgementTimeoutMillis = if (responder) 73 else 256,
            maxRetransmissions = 30,
            maxAcknowledgements = if (responder) 3 else 1,
            sessions = when (this) {
                VIVO_ACCESSORY_FIRST -> listOf(
                    Iap2LinkEngine.SessionDescriptor(1, 0, 2),
                    Iap2LinkEngine.SessionDescriptor(2, 1, 2),
                )
                else -> listOf(Iap2LinkEngine.SessionDescriptor(if (responder) 10 else 1, 0, 2))
            },
        )
        return Iap2LinkConfig(
            maxOutgoing = synchronization.maxOutgoing,
            acknowledgementTimeoutMillis = synchronization.acknowledgementTimeoutMillis,
            controlSessionVersion = 2,
            startup = if (responder) Iap2LinkStartup.ACCESSORY_FIRST else Iap2LinkStartup.DETECTION_THEN_SYN,
            synchronizationOverride = synchronization,
            negotiateControlSession = true,
            maximumInFlightPackets = 1,
        )
    }
}

enum class Iap2LinkStartup { LEGACY, ACCESSORY_FIRST, DETECTION_THEN_SYN }

/** Counts are taken at the protocol stream boundary; peer fields are absent until a valid SYN is received. */
data class Iap2HandshakeStatus(
    val state: Iap2LinkEngine.State = Iap2LinkEngine.State.IDLE,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val peerSynchronization: Iap2LinkEngine.SynchronizationPayload? = null,
    val controlSessionId: Int? = null,
)
