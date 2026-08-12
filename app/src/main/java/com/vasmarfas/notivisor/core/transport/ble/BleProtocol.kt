package com.vasmarfas.notivisor.core.transport.ble

import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.ByteArrayOutputStream
import java.util.UUID

object BleProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("7a9e0100-4b1e-4b8f-9c2a-5f6d7e8a9b01")

    val TX_UUID: UUID = UUID.fromString("7a9e0101-4b1e-4b8f-9c2a-5f6d7e8a9b01")

    val RX_UUID: UUID = UUID.fromString("7a9e0102-4b1e-4b8f-9c2a-5f6d7e8a9b01")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TARGET_MTU = 517
    const val DEFAULT_MTU = 23
    private const val ATT_OVERHEAD = 3
    private const val HEADER = 1

    private const val MAX_ATTRIBUTE = 512

    private const val FINAL_BIT = 0x80
    private const val INDEX_MASK = 0x7F

    fun payloadSize(mtu: Int): Int =
        (minOf(mtu - ATT_OVERHEAD, MAX_ATTRIBUTE) - HEADER).coerceAtLeast(16)

    fun chunk(line: String, mtu: Int): List<ByteArray> {
        val bytes = line.toByteArray(Charsets.UTF_8)
        val size = payloadSize(mtu)
        val chunks = ArrayList<ByteArray>((bytes.size / size) + 1)
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val length = minOf(size, bytes.size - offset)
            val last = offset + length >= bytes.size
            val out = ByteArray(length + HEADER)
            out[0] = ((index and INDEX_MASK) or (if (last) FINAL_BIT else 0)).toByte()
            System.arraycopy(bytes, offset, out, HEADER, length)
            chunks.add(out)
            offset += length
            index++
        }
        return chunks
    }

    class Reassembler(private val scope: String) {

        private val buffer = ByteArrayOutputStream(1024)
        private var expected = 0

        fun accept(frame: ByteArray): String? {
            if (frame.isEmpty()) return null
            val header = frame[0].toInt() and 0xFF
            val index = header and INDEX_MASK
            val last = header and FINAL_BIT != 0

            if (index != expected) {
                BridgeLog.w(
                    scope,
                    "chunk out of order (got $index, expected $expected), dropping partial message"
                )
                reset()
                if (index != 0) return null
            }

            buffer.write(frame, 1, frame.size - 1)
            expected = (expected + 1) and INDEX_MASK

            if (!last) return null
            val line = buffer.toString(Charsets.UTF_8.name())
            reset()
            return line
        }

        fun reset() {
            buffer.reset()
            expected = 0
        }
    }
}
