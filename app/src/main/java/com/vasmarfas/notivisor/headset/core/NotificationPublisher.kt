package com.vasmarfas.notivisor.headset.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PublishCounters(
    val received: Long = 0,
    val published: Long = 0,
    val throttled: Long = 0,
    val removed: Long = 0,
    val lastTitle: String? = null,
)

class NotificationPublisher(context: Context, private val settings: BridgeSettings) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = Channel<Envelope>(Channel.BUFFERED)
    private val channels = HashSet<String>()
    private val ids = HashMap<String, Int>()

    private val _counters = MutableStateFlow(PublishCounters())
    val counters: StateFlow<PublishCounters> = _counters.asStateFlow()

    var onPublished: ((Envelope) -> Unit)? = null

    init {
        restoreIds()
        scope.launch { publishLoop() }
    }

    fun submit(envelope: Envelope) {
        if (envelope.action == Action.POST) {
            _counters.value = _counters.value.copy(received = _counters.value.received + 1)
        }
        if (!pending.trySend(envelope).isSuccess) {
            _counters.value = _counters.value.copy(throttled = _counters.value.throttled + 1)
            BridgeLog.w(SCOPE, "publish queue full, dropped ${envelope.action} ${envelope.key}")
        }
    }

    private suspend fun publishLoop() {
        while (scope.isActive) {
            val envelope = pending.receive()
            when (envelope.action) {
                Action.POST -> {
                    publish(envelope)
                    delay(settings.headsUpIntervalMs)
                }

                Action.REMOVE -> remove(envelope)
            }
        }
    }

    private fun remove(envelope: Envelope) {
        val key = envelope.key ?: return
        val id = ids.remove(key)
        if (id == null) {
            BridgeLog.d(SCOPE, "REMOVE key=$key had nothing to cancel")
            return
        }
        persistIds()
        manager.cancel(id)
        _counters.value = _counters.value.copy(removed = _counters.value.removed + 1)
        BridgeLog.i(SCOPE, "REMOVE key=$key id=$id")
    }

    private fun publish(envelope: Envelope) {
        val pkg = envelope.pkg ?: "unknown"
        val channelId = ensureChannel(pkg, envelope.app ?: pkg)
        val key = envelope.key ?: "$pkg|${envelope.seq}"
        val id = ids.getOrPut(key) { nextId() }
        persistIds()

        val app = envelope.app ?: pkg
        val body = listOfNotNull(envelope.text, envelope.sub.takeIf { settings.showSourceApp })
            .joinToString(" · ")

        val builder = Notification.Builder(appContext, channelId)
            .setContentTitle(envelope.title ?: app)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(envelope.whenMs ?: envelope.ts ?: System.currentTimeMillis())

        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_HIGH)

        if (settings.showSourceApp) builder.setSubText(app) else envelope.sub?.let(builder::setSubText)

        body.takeIf { it.length > 40 }?.let {
            builder.setStyle(Notification.BigTextStyle().bigText(it))
        }

        manager.notify(id, builder.build())
        _counters.value = _counters.value.copy(
            published = _counters.value.published + 1,
            lastTitle = envelope.title ?: envelope.text,
        )
        BridgeLog.i(SCOPE, "PUBLISH seq=${envelope.seq} id=$id pkg=$pkg title='${envelope.title}'")
        onPublished?.invoke(envelope)
    }

    private fun ensureChannel(pkg: String, label: String): String {
        val id = CHANNEL_PREFIX + pkg.replace(Regex("[^A-Za-z0-9_.]"), "_")
        if (!channels.add(id)) return id
        val channel = NotificationChannel(id, label, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Mirrored from $pkg"
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        return id
    }

    private fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, BASE_ID) + 1
        prefs.edit { putInt(KEY_NEXT_ID, next) }
        return next
    }

    private fun persistIds() {
        val json = JSONObject()
        ids.forEach { (key, id) -> json.put(key, id) }
        prefs.edit { putString(KEY_IDS, json.toString()) }
    }

    private fun restoreIds() {
        val raw = prefs.getString(KEY_IDS, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { key -> ids[key] = json.getInt(key) }
        }.onFailure { BridgeLog.w(SCOPE, "could not restore id map: ${it.message}") }
    }

    private companion object {
        const val SCOPE = "publish"
        const val PREFS = "Notivisor_headset"
        const val KEY_IDS = "id_map"
        const val KEY_NEXT_ID = "next_id"
        const val CHANNEL_PREFIX = "src_"
        const val BASE_ID = 1000
    }
}
