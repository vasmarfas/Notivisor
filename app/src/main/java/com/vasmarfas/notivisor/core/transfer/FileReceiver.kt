package com.vasmarfas.notivisor.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.link.LinkManager
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.FileStream
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.core.util.vrTagged
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.SecretKey

object FileReceiver {

    private const val SCOPE = "files"
    private const val CHANNEL_ID = "file_received"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 30_000

    fun accept(context: Context, link: LinkManager, settings: BridgeSettings, offer: Envelope) {
        val host = (link.state.value as? LinkState.Connected)?.peer
        val key = settings.sessionKey()
        if (settings.transportKind != TransportKind.TCP || host == null || key == null) {
            BridgeLog.w(SCOPE, "refusing a file: no Wi-Fi peer or pairing key")
            return
        }
        Thread { receive(context, host, key, offer) }.apply { isDaemon = true }.start()
    }

    fun receive(context: Context, host: String, key: SecretKey, offer: Envelope) {
        val id = offer.key ?: return
        val name = FileStream.safeName(offer.title)
        val mime = FileStream.safeMime(offer.mime)
        runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(host, TransportConfig.FILE_STREAM_PORT),
                    CONNECT_TIMEOUT_MS,
                )
                socket.soTimeout = READ_TIMEOUT_MS
                val replies = socket.getOutputStream()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    replies.write(FileStream.UNSUPPORTED)
                    return
                }
                val storage = context.getSystemService(StorageManager::class.java)
                if ((offer.size ?: 0L) > storage.getAllocatableBytes(StorageManager.UUID_DEFAULT)) {
                    replies.write(FileStream.NO_SPACE)
                    return
                }
                replies.write(FileStream.READY)
                val uri = save(context, name, mime) { target ->
                    FileStream(key, id).receive(socket.getInputStream(), target)
                }
                replies.write(FileStream.SAVED)
                BridgeLog.i(SCOPE, "file saved to Downloads")
                notify(context, id, R.string.file_saved, name, uri, mime)
            }
        }.onFailure {
            BridgeLog.w(SCOPE, "file did not arrive: ${it.message}")
            notify(context, id, R.string.file_not_saved, name, null, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun save(
        context: Context,
        name: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads refused the file")
        try {
            val target = resolver.openOutputStream(uri) ?: throw IOException("Downloads did not open")
            target.use(write)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun notify(context: Context, id: String, title: Int, name: String, uri: Uri?, mime: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_files_received),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(title))
            .setContentText(name)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setAutoCancel(true)
            .vrTagged()
        uri?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id.hashCode(),
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(it, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        manager.notify(id.hashCode(), builder.build())
    }
}
