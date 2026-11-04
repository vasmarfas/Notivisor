package com.vasmarfas.notivisor.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import com.vasmarfas.notivisor.AppRole
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.transport.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FileSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var shownAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = intent?.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }.orEmpty()
        scope.launch {
            sendAll(uris)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sendAll(uris: List<Uri>) {
        val key = BridgeSettings.get(this).sessionKey() ?: return
        val link = AppRole.link(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_files),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        uris.forEachIndexed { index, uri ->
            val outcome = FileSender.send(this, uri, key, link::sendDirect) { name, sent, total ->
                showProgress(name, sent, total)
            }
            if (outcome != FileSender.Outcome.SENT) {
                showResult(getString(R.string.file_sent_count, index, uris.size), failure(outcome))
                return
            }
        }
        showResult(getString(R.string.file_sent_count, uris.size, uris.size), null)
    }

    private fun failure(outcome: FileSender.Outcome): String = getString(
        when (outcome) {
            FileSender.Outcome.NOT_PICKED_UP -> R.string.file_not_picked_up
            FileSender.Outcome.NO_SPACE -> R.string.file_no_space
            FileSender.Outcome.UNSUPPORTED -> R.string.file_unsupported
            else -> R.string.file_interrupted
        }
    )

    private fun showProgress(name: String, sent: Long, total: Long?) {
        val now = SystemClock.elapsedRealtime()
        if (now - shownAt < PROGRESS_INTERVAL_MS) return
        shownAt = now
        val percent = total?.takeIf { it > 0 }?.let { (sent * 100 / it).toInt().coerceIn(0, 100) } ?: 0
        notify(
            builder()
                .setContentTitle(getString(R.string.file_sending))
                .setContentText(name)
                .setProgress(100, percent, total == null)
                .setOngoing(true)
        )
    }

    private fun showResult(title: String, detail: String?) {
        notify(builder().setContentTitle(title).setContentText(detail).setAutoCancel(true))
    }

    private fun builder() = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_bridge)
        .setOnlyAlertOnce(true)

    private fun notify(builder: Notification.Builder) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "file_transfer"
        private const val NOTIFICATION_ID = 9
        private const val PROGRESS_INTERVAL_MS = 1_000L

        fun send(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val settings = BridgeSettings.get(context)
            val refusal = when {
                settings.transportKind != TransportKind.TCP -> R.string.share_needs_wifi
                settings.sessionKey() == null -> R.string.share_needs_pairing
                !AppRole.link(context).state.value.isConnected -> R.string.share_needs_link
                else -> null
            }
            if (refusal != null) {
                Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
                return
            }
            val clip = ClipData.newRawUri(null, uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            context.startService(
                Intent(context, FileSendService::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = clip }
            )
            Toast.makeText(context, R.string.share_sending, Toast.LENGTH_SHORT).show()
        }
    }
}
