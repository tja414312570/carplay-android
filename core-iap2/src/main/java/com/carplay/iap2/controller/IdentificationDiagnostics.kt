package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.wire.Iap2ParameterList

/** Structural diagnostics only. Device strings and raw transport values are never emitted. */
internal object IdentificationDiagnostics {
    fun describe(body: Iap2BodyReader): String {
        val parameters = body.list()
        val shape = parameters.take(48).joinToString(",") { "${it.id}:${it.payload.size}B" } +
            if (parameters.size > 48) ",… total=${parameters.size}" else ""
        val transports = parameters.filter { it.id in TRANSPORT_PARAMETERS }
        val components = transports.take(8).joinToString(";") { parameter ->
            try {
                val group = Iap2BodyReader.of(Iap2ParameterList.parse(parameter.payload))
                val component = number(group, 0)
                val transport = if (parameter.id == 24) ",transportId=${number(group, 3)}" else ""
                val fields = group.list().take(12).joinToString(",") { "${it.id}:${it.payload.size}B" }
                "p${parameter.id}(component=$component$transport,fields=$fields)"
            } catch (_: Exception) {
                "p${parameter.id}(malformed,size=${parameter.payload.size}B)"
            }
        } + if (transports.size > 8) ";… total=${transports.size}" else ""
        return "params=$shape transports=${components.ifEmpty { "none-advertised" }}"
    }

    private fun number(body: Iap2BodyReader, id: Int): String {
        val raw = body.optionalRaw(id) ?: return "missing"
        return if (raw.size == 2) body.u16(id).toString() else "size=${raw.size}B"
    }

    private val TRANSPORT_PARAMETERS = setOf(16,17,24)
}
