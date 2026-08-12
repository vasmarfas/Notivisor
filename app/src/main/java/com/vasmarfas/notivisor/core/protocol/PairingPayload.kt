package com.vasmarfas.notivisor.core.protocol

import android.net.Uri
import androidx.core.net.toUri
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind

data class PairingPayload(
    val code: String,
    val transport: TransportKind,
    val bleServerIsSource: Boolean,
    val host: String?,
    val port: Int,
    val showSourceApp: Boolean,
) {

    fun encode(): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendQueryParameter("v", VERSION.toString())
        .appendQueryParameter("c", code)
        .appendQueryParameter("t", transport.name)
        .appendQueryParameter("s", if (bleServerIsSource) "1" else "0")
        .apply { host?.let { appendQueryParameter("h", it) } }
        .appendQueryParameter("p", port.toString())
        .appendQueryParameter("a", if (showSourceApp) "1" else "0")
        .build()
        .toString()

    companion object {
        const val VERSION = 1
        private const val SCHEME = "notivisor"
        private const val HOST = "pair"

        fun parse(raw: String): PairingPayload? {
            val uri = runCatching { raw.trim().toUri() }.getOrNull() ?: return null
            if (uri.scheme != SCHEME || uri.authority != HOST) return null
            if ((uri.getQueryParameter("v")?.toIntOrNull() ?: VERSION) > VERSION) return null

            val code = Pairing.normalise(uri.getQueryParameter("c").orEmpty())
            if (!Pairing.isValid(code)) return null

            val transport = runCatching {
                TransportKind.valueOf(uri.getQueryParameter("t").orEmpty().uppercase())
            }.getOrDefault(TransportKind.BLE)

            return PairingPayload(
                code = code,
                transport = transport,
                bleServerIsSource = uri.getQueryParameter("s") != "0",
                host = uri.getQueryParameter("h")?.takeIf { it.isNotBlank() },
                port = uri.getQueryParameter("p")?.toIntOrNull()
                    ?: TransportConfig.DEFAULT_TCP_PORT,
                showSourceApp = uri.getQueryParameter("a") != "0",
            )
        }
    }
}
