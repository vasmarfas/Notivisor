package com.vasmarfas.notivisor.core.control

enum class CastSource {
    MAGIC,
    SCRCPY;

    companion object {
        fun parse(name: String?): CastSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MAGIC
    }
}
