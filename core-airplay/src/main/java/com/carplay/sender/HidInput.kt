package com.carplay.sender

data class TouchSample(val id: Int, val x: Float, val y: Float, val down: Boolean)
data class HidField(val page: Int, val usage: Int, val offset: Int, val size: Int, val maximum: Int)

/** Reads the advertised descriptor so report offsets and coordinate ranges are negotiated. */
class HidInput(descriptor: ByteArray) {
    private val fields = mutableListOf<HidField>()
    private var bits = 0
    val isTouch get() = fields.any { it.page == 0x0d && it.usage == 0x33 } && fields.any { it.page == 1 && it.usage == 0x30 }
    init {
        require(descriptor.size in 1..8192)
        var cursor = 0; var page = 0; var reportSize = 0; var reportCount = 0; var maximum = 0
        val usages = mutableListOf<Int>()
        while (cursor < descriptor.size) {
            val prefix = descriptor[cursor++].toInt() and 255
            require(prefix != 0xfe) { "不支持扩展 HID 项" }
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            require(cursor + size <= descriptor.size)
            var value = 0; repeat(size) { value = value or ((descriptor[cursor++].toInt() and 255) shl (8 * it)) }
            when (prefix and 0xfc) {
                0x04 -> page = value
                0x08 -> usages += value
                0x24 -> maximum = value
                0x74 -> reportSize = value
                0x94 -> reportCount = value
                0x84 -> error("当前不支持带 Report ID 的输入设备")
                0x80 -> {
                    require(reportSize in 1..32 && reportCount in 1..64 && bits + reportSize * reportCount <= 4096)
                    repeat(reportCount) { slot ->
                        if (value and 1 == 0) fields += HidField(page,usages.getOrElse(slot) { usages.lastOrNull() ?: 0 },bits,reportSize,maximum)
                        bits += reportSize
                    }
                    usages.clear()
                }
                0xa0, 0xc0 -> usages.clear()
            }
        }
    }
    fun touches(report: ByteArray): List<TouchSample> {
        require(report.size * 8 >= bits && report.size <= 512)
        val result = mutableListOf<TouchSample>()
        var id = 0; var down = false; var x: Float? = null
        for (field in fields) {
            val value = read(report,field)
            when {
                field.page == 0x0d && field.usage == 0x38 -> id = value
                field.page == 0x0d && field.usage == 0x33 -> down = value != 0
                field.page == 1 && field.usage == 0x30 -> { require(field.maximum > 0); x = value.toFloat() / field.maximum }
                field.page == 1 && field.usage == 0x31 -> { require(field.maximum > 0); result += TouchSample(id,checkNotNull(x),value.toFloat()/field.maximum,down); x = null }
            }
        }
        require(result.all { it.x in 0f..1f && it.y in 0f..1f })
        return result
    }
    private fun read(bytes: ByteArray, field: HidField): Int {
        var value = 0
        repeat(field.size) { bit -> val index = field.offset + bit; value = value or (((bytes[index/8].toInt() ushr (index%8)) and 1) shl bit) }
        return value
    }
}

data class CaptureGeometry(val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int) {
    init { require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0) }
    fun point(x: Float, y: Float): Pair<Float,Float>? {
        val scale = minOf(width.toFloat()/sourceWidth,height.toFloat()/sourceHeight)
        val left = (width-sourceWidth*scale)/2; val top = (height-sourceHeight*scale)/2
        val mappedX = (x-left)/scale; val mappedY = (y-top)/scale
        if (mappedX !in 0f..sourceWidth.toFloat() || mappedY !in 0f..sourceHeight.toFloat()) return null
        return mappedX to mappedY
    }
}
