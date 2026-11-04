package com.vasmarfas.notivisor.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.FileStream
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import javax.crypto.SecretKey

object FileSender {

    enum class Outcome { SENT, NOT_PICKED_UP, NO_SPACE, UNSUPPORTED, FAILED }

    private const val SCOPE = "files"
    private const val ACCEPT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun send(
        context: Context,
        uri: Uri,
        key: SecretKey,
        onOffer: (Envelope) -> Unit,
        onProgress: (name: String, sent: Long, total: Long?) -> Unit,
    ): Outcome = runCatching { transfer(context, uri, key, onOffer, onProgress) }
        .onFailure { BridgeLog.w(SCOPE, "file transfer failed: ${it.message}") }
        .getOrDefault(Outcome.FAILED)

    private fun transfer(
        context: Context,
        uri: Uri,
        key: SecretKey,
        onOffer: (Envelope) -> Unit,
        onProgress: (String, Long, Long?) -> Unit,
    ): Outcome {
        val offer = offer(context, uri)
        val name = offer.title.orEmpty()
        ServerSocket(TransportConfig.FILE_STREAM_PORT).use { server ->
            server.soTimeout = ACCEPT_TIMEOUT_MS
            onOffer(offer)
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                return Outcome.NOT_PICKED_UP
            }
            socket.use {
                socket.soTimeout = READ_TIMEOUT_MS
                val replies = socket.getInputStream()
                when (replies.read()) {
                    FileStream.READY -> Unit
                    FileStream.NO_SPACE -> return Outcome.NO_SPACE
                    FileStream.UNSUPPORTED -> return Outcome.UNSUPPORTED
                    else -> return Outcome.FAILED
                }
                val source = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("the shared file did not open")
                source.use {
                    FileStream(key, offer.key.orEmpty()).send(source, socket.getOutputStream()) { sent ->
                        onProgress(name, sent, offer.size)
                    }
                }
                val saved = replies.read() == FileStream.SAVED
                BridgeLog.i(SCOPE, "file transfer finished, saved=$saved")
                return if (saved) Outcome.SENT else Outcome.FAILED
            }
        }
    }

    private fun offer(context: Context, uri: Uri): Envelope {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    size = cursor.takeUnless { it.isNull(1) }?.getLong(1)
                }
            }
        return Envelope(
            action = Action.FILE_OFFER,
            key = UUID.randomUUID().toString(),
            title = FileStream.safeName(name ?: uri.lastPathSegment),
            mime = resolver.getType(uri),
            size = size,
        )
    }
}
