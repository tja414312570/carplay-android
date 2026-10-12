package com.carplay.sender

data class HidUsage(val page: Int, val usage: Int)
data class HidKeyChange(val usage: HidUsage, val down: Boolean)
data class HidKeyReport(val held: Set<HidUsage>, val changes: List<HidKeyChange>, val wheel: Int)

/** Descriptor-derived selector/button reports; no dependency on vendor UUIDs. */
class HidKeys(descriptor: ByteArray) {
    private data class Field(val offset: Int, val size: Int, val flags: Int, val usages: List<HidUsage>)
    private val fields = mutableListOf<Field>()
    private var bits = 0
    private var active = emptySet<HidUsage>()
    init {
        require(descriptor.size in 1..8192)
        var offset = 0; var page = 0; var size = 0; var count = 0
        val usages = mutableListOf<HidUsage>()
        while (offset < descriptor.size) {
            val prefix = descriptor[offset++].toInt() and 255; require(prefix != 0xfe)
            val bytes = if (prefix and 3 == 3) 4 else prefix and 3
            require(offset + bytes <= descriptor.size)
            var value = 0; repeat(bytes) { value = value or ((descriptor[offset++].toInt() and 255) shl (8*it)) }
            when (prefix and 0xfc) {
                0x04 -> page = value
                0x08 -> usages += HidUsage(if (value ushr 16 == 0) page else value ushr 16,value and 65535)
                0x74 -> size = value
                0x94 -> count = value
                0x84 -> error("不支持带 Report ID 的按键设备")
                0x80 -> {
                    require(size in 1..32 && count in 1..64 && bits + size*count <= 4096)
                    repeat(count) { slot ->
                        if (value and 1 == 0) fields += Field(bits,size,value,if (value and 2 == 0) usages.toList() else listOfNotNull(usages.getOrNull(slot) ?: usages.lastOrNull()))
                        bits += size
                    }
                    usages.clear()
                }
                0xa0, 0xc0 -> usages.clear()
            }
        }
        require(fields.any { field -> field.usages.any { it.page in setOf(9,0x0b,0x0c) } }) { "不是支持的按键描述符" }
    }
    @Synchronized fun report(bytes: ByteArray): HidKeyReport {
        require(bytes.size * 8 >= bits && bytes.size <= 512)
        val held = linkedSetOf<HidUsage>(); var wheel = 0
        for (field in fields) {
            var value = 0
            repeat(field.size) { bit -> val position = field.offset+bit; value = value or (((bytes[position/8].toInt() ushr (position%8)) and 1) shl bit) }
            if (field.flags and 2 == 0) {
                if (value != 0) held += requireNotNull(field.usages.getOrNull(value)) { "未知按键选择值" }
            } else {
                val usage = field.usages.firstOrNull() ?: continue
                if (usage.page == 1 && usage.usage == 0x38 && field.flags and 4 != 0) {
                    wheel = if (field.size == 8) value.toByte().toInt() else value
                    require(wheel in -127..127)
                } else if (usage.page in setOf(9,0x0b,0x0c) && value != 0) held += usage
            }
        }
        val changes = (active-held).map { HidKeyChange(it,false) } + (held-active).map { HidKeyChange(it,true) }
        active = held.toSet()
        return HidKeyReport(held,changes,wheel)
    }
}
