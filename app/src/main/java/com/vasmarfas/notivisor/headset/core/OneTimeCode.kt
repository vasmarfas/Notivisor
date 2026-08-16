package com.vasmarfas.notivisor.headset.core

object OneTimeCode {

    private val CODE = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    private val HINTS = listOf(
        "code", "otp", "one-time", "one time", "password", "passcode", "verification", "verify",
        "pin", "2fa", "authenticat",
        "код", "пароль", "подтвержд", "проверк", "одноразов",
    )

    fun find(vararg parts: String?): String? {
        val haystack = parts.filterNotNull().joinToString(" ")
        if (haystack.isEmpty()) return null
        val lower = haystack.lowercase()
        if (HINTS.none { it in lower }) return null
        return CODE.find(haystack)?.groupValues?.get(1)
    }
}
