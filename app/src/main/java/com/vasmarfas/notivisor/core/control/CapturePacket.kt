package com.vasmarfas.notivisor.core.control

sealed interface CapturePacket {
    data class Size(val width: Int, val height: Int) : CapturePacket
    data class Frame(val data: ByteArray, val isConfig: Boolean) : CapturePacket
}
