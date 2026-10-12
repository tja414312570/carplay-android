package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader

/** Reported accessory metadata, not a guessed CarPlay SDK version or a trust decision. */
data class AccessoryVersionInfo(val model: String?, val manufacturer: String?, val firmware: String?, val hardware: String?) {
    fun diagnostic(): String = "model=${safe(model)} manufacturer=${safe(manufacturer)} " +
        "firmware=${safe(firmware)} hardware=${safe(hardware)}"

    companion object {
        fun fromIdentification(body: Iap2BodyReader) = AccessoryVersionInfo(
            optionalVersionString(body, 1), optionalVersionString(body, 2),
            optionalVersionString(body, 4), optionalVersionString(body, 5))

        internal fun optionalVersionString(body: Iap2BodyReader, id: Int): String? = try {
            body.optionalString(id)?.takeIf { it.length in 1..256 }
        } catch (_: Exception) { null }

        internal fun safe(value: String?): String {
            if (value == null) return "未提供"
            return "\"" + value.replace(Regex("[\\p{Cntrl}\\\"]"), "?")
                .replace(Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"), "[address]")
                .replace(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "[identifier]")
                .take(128) + "\""
        }
    }
}
