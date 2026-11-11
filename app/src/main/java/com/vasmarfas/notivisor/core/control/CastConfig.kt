package com.vasmarfas.notivisor.core.control

enum class CastQuality(val width: Int, val height: Int) {
    EYE(1344, 1440),
    HD(1280, 720),
    FULL_HD(1920, 1080),
    QHD(2560, 1440);

    val longSide: Int get() = maxOf(width, height)
}

data class CastConfig(
    val quality: CastQuality = CastQuality.EYE,
    val fps: Int = 30,
    val bitRate: Int = 0,
    val rightEye: Boolean = true,
    val sound: Boolean = true,
    val stabilized: Boolean = false,
)
