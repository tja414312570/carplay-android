package com.carplay.sender

import java.net.ProtocolException

enum class SenderControlKind { WIFI_DATASTREAM, WIFI_COMMAND, AUTHENTICATED_BLUETOOTH }
data class SenderControlTransport(val kind: SenderControlKind, val port: Int? = null)
data class SenderAudioFormat(val streamType: Int, val sampleRate: Int, val channels: Int, val formatBits: Long) {
    fun rtpTimestamp(ptsUs: Long): Int {
        require(ptsUs >= 0)
        val seconds = (ptsUs / 1_000_000) % 0x1_0000_0000L
        val fractional = ((ptsUs % 1_000_000) * sampleRate + 500_000) / 1_000_000
        return (seconds * sampleRate + fractional).toInt()
    }
}

/** Select only what the peer actually accepted; Bluetooth availability is checked by the owner. */
object SenderNegotiation {
    fun control(reply: Map<*, *>): SenderControlTransport {
        val raw = reply["streams"] ?: return SenderControlTransport(SenderControlKind.AUTHENTICATED_BLUETOOTH)
        val streams = raw as? List<*> ?: throw ProtocolException("type 130 回复的 streams 类型无效")
        val accepted = streams.map { it as? Map<*, *> ?: throw ProtocolException("type 130 回复的流结构无效") }
            .filter { (it["type"] as? Number)?.toLong() == 130L }
        if (accepted.isEmpty()) return SenderControlTransport(SenderControlKind.AUTHENTICATED_BLUETOOTH)
        if (accepted.size != 1) throw ProtocolException("车机返回多个 iAP 控制流")
        val stream = accepted.single()
        val rawPort = stream["dataPort"] ?: return SenderControlTransport(SenderControlKind.WIFI_COMMAND)
        val port = rawPort as? Number ?: throw ProtocolException("iAP dataPort 类型无效")
        val value = port.toLong()
        if (value == 0L) return SenderControlTransport(SenderControlKind.WIFI_COMMAND)
        if (value !in 1..65535 || port.toDouble() != value.toDouble()) throw ProtocolException("iAP dataPort 超出范围")
        return SenderControlTransport(SenderControlKind.WIFI_DATASTREAM,value.toInt())
    }

    fun audio(info: Map<*, *>): SenderAudioFormat {
        val entries = (info["audioFormats"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val choices = entries.filter {
            (it["type"] as? Number)?.toInt() in listOf(100,102) &&
                (it["audioType"] == null || it["audioType"] in listOf("media","default"))
        }.flatMap { entry ->
            val bits = (entry["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            listOf(48000 to 0x800000L,44100 to 0x400000L).mapNotNull { (rate,flag) ->
                if (bits and flag != 0L) SenderAudioFormat((entry["type"] as Number).toInt(),rate,2,flag) else null
            }
        }
        return choices.sortedWith(compareByDescending<SenderAudioFormat> { it.sampleRate }.thenBy { it.streamType })
            .firstOrNull() ?: throw ProtocolException("车机未声明可发送的 AAC 媒体格式（type 100/102、44.1/48 kHz）；请关闭音频重试")
    }
}

/** Binary data from a verified iAP command or that command's response; never infer proof from ACKs. */
object IapCommandData {
    fun payload(value: Any?, response: Boolean = false): ByteArray? {
        val raw = when (value) {
            is ByteArray -> if (response) value else null
            is Map<*, *> -> {
                if (value["type"] != null && value["type"] != "iAPSendMessage") return null
                val params = value["params"]
                if (params != null && params !is Map<*, *>) throw ProtocolException("iAP params 类型无效")
                (params as? Map<*, *>)?.get("data") ?: if (response) value["data"] else null
            }
            else -> null
        } ?: return null
        val data = raw as? ByteArray ?: throw ProtocolException("iAP 控制数据不是二进制字段")
        if (data.isEmpty()) return null
        if (data.size > 1_048_576) throw ProtocolException("iAP 控制数据超出接收上限")
        return data
    }
}
