package com.carplay.iap2.transport

/** Link headers only. QUEUED does not claim a completed socket write; byte counters report completion. */
data class Iap2PacketMetadata(
    val action: String,
    val flags: Int,
    val sequence: Int,
    val acknowledgement: Int,
    val sessionId: Int,
    val length: Int,
    val state: Iap2LinkEngine.State,
    val payloadChecksumValid: Boolean = true,
) {
    fun diagnostic() = "$action flags=0x${flags.toString(16)} seq=$sequence ack=$acknowledgement " +
        "session=$sessionId length=$length state=$state checksum=$payloadChecksumValid"
}
