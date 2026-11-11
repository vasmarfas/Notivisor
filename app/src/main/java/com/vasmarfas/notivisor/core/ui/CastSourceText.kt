package com.vasmarfas.notivisor.core.ui

import androidx.annotation.StringRes
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.control.CastSource

@StringRes
fun castSourceLabel(source: CastSource): Int = when (source) {
    CastSource.MAGIC -> R.string.cast_source_magic
    CastSource.MAGIC_ADB -> R.string.cast_source_magic_adb
    CastSource.SCRCPY -> R.string.cast_source_scrcpy
    CastSource.PROJECTION -> R.string.cast_source_projection
}

@StringRes
fun castSourceHint(source: CastSource): Int = when (source) {
    CastSource.MAGIC -> R.string.cast_source_magic_hint
    CastSource.MAGIC_ADB -> R.string.cast_source_magic_adb_hint
    CastSource.SCRCPY -> R.string.cast_source_scrcpy_hint
    CastSource.PROJECTION -> R.string.cast_source_projection_hint
}
