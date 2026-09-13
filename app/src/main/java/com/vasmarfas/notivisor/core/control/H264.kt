package com.vasmarfas.notivisor.core.control

object H264 {

    const val NAL_SPS = 7
    private const val NAL_PPS = 8

    fun configPrefixLength(data: ByteArray): Int {
        var prefix = 0
        var index = 0
        while (index < data.size) {
            val header = startCodeLength(data, index) ?: break
            val type = data.getOrNull(index + header)?.let { it.toInt() and 0x1F } ?: break
            if (type != NAL_SPS && type != NAL_PPS) break
            index = nextStartCode(data, index + header) ?: data.size
            prefix = index
        }
        return prefix
    }

    fun dimensions(config: ByteArray): Pair<Int, Int>? {
        var index = 0
        while (index < config.size) {
            val header = startCodeLength(config, index) ?: return null
            val start = index + header
            val type = config.getOrNull(start)?.let { it.toInt() and 0x1F } ?: return null
            val end = nextStartCode(config, start) ?: config.size
            if (type == NAL_SPS) return fromSps(config.copyOfRange(start, end))
            index = end
        }
        return null
    }

    private fun startCodeLength(data: ByteArray, at: Int): Int? = when {
        at + 4 <= data.size && data[at] == ZERO && data[at + 1] == ZERO &&
                data[at + 2] == ZERO && data[at + 3] == ONE -> 4

        at + 3 <= data.size && data[at] == ZERO && data[at + 1] == ZERO && data[at + 2] == ONE -> 3

        else -> null
    }

    private fun nextStartCode(data: ByteArray, from: Int): Int? {
        var index = from
        while (index + 3 <= data.size) {
            if (startCodeLength(data, index) != null) return index
            index++
        }
        return null
    }

    private fun fromSps(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 2) return null
        val bits = BitReader(unescape(sps.copyOfRange(1, sps.size)))

        val profileIdc = bits.read(8) ?: return null
        bits.read(16) ?: return null
        bits.ue() ?: return null

        var chromaFormatIdc = 1
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = bits.ue() ?: return null
            if (chromaFormatIdc == 3) bits.read(1) ?: return null
            bits.ue() ?: return null
            bits.ue() ?: return null
            bits.read(1) ?: return null
            if ((bits.read(1) ?: return null) != 0) return null
        }

        bits.ue() ?: return null
        when (bits.ue() ?: return null) {
            0 -> bits.ue() ?: return null
            1 -> {
                bits.read(1) ?: return null
                bits.se() ?: return null
                bits.se() ?: return null
                repeat(bits.ue() ?: return null) { bits.se() ?: return null }
            }
        }

        bits.ue() ?: return null
        bits.read(1) ?: return null
        val widthInMbs = (bits.ue() ?: return null) + 1
        val heightInMapUnits = (bits.ue() ?: return null) + 1
        val frameMbsOnly = bits.read(1) ?: return null
        if (frameMbsOnly == 0) bits.read(1) ?: return null
        bits.read(1) ?: return null

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if ((bits.read(1) ?: return null) != 0) {
            cropLeft = bits.ue() ?: return null
            cropRight = bits.ue() ?: return null
            cropTop = bits.ue() ?: return null
            cropBottom = bits.ue() ?: return null
        }

        val cropUnitX = if (chromaFormatIdc == 0) 1 else 2
        val cropUnitY = (if (chromaFormatIdc == 1) 2 else 1) * (2 - frameMbsOnly)
        val width = widthInMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (2 - frameMbsOnly) * heightInMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
        return (width to height).takeIf { width > 0 && height > 0 }
    }

    private fun unescape(ebsp: ByteArray): ByteArray {
        val rbsp = ByteArray(ebsp.size)
        var length = 0
        var zeros = 0
        for (byte in ebsp) {
            if (zeros >= 2 && byte == THREE) {
                zeros = 0
                continue
            }
            rbsp[length++] = byte
            zeros = if (byte == ZERO) zeros + 1 else 0
        }
        return rbsp.copyOf(length)
    }

    private class BitReader(private val data: ByteArray) {
        private var bit = 0

        fun read(count: Int): Int? {
            var value = 0
            repeat(count) {
                val byte = data.getOrNull(bit / 8) ?: return null
                value = (value shl 1) or ((byte.toInt() shr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }

        fun ue(): Int? {
            var leadingZeros = 0
            while ((read(1) ?: return null) == 0) {
                leadingZeros++
                if (leadingZeros > 31) return null
            }
            if (leadingZeros == 0) return 0
            val suffix = read(leadingZeros) ?: return null
            return (1 shl leadingZeros) - 1 + suffix
        }

        fun se(): Int? {
            val value = ue() ?: return null
            return if (value % 2 == 0) -(value / 2) else (value + 1) / 2
        }
    }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
    private const val THREE: Byte = 3
    private val HIGH_PROFILES =
        setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
