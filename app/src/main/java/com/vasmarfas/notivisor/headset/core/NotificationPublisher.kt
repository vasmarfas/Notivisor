package com.vasmarfas.notivisor.headset.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.RemoteAction
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.service.RelayReceiver
import com.vasmarfas.notivisor.headset.ui.CopyActivity
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
import kotlin.time.Duration.Companion.milliseconds

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
    private val icons = IconCache(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = Channel<Envelope>(Channel.BUFFERED)
    private val channels = HashSet<String>()

    private val ids = LinkedHashMap<String, Int>(64, 0.75f, true)
    private val iconsAsked = HashSet<String>()

    private val _counters = MutableStateFlow(PublishCounters())
    val counters: StateFlow<PublishCounters> = _counters.asStateFlow()

    var onPublished: ((Envelope) -> Unit)? = null

    var onIconNeeded: ((String) -> Unit)? = null

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

    fun storeIcon(pkg: String, base64: String) {
        icons.store(pkg, base64)
    }

    private suspend fun publishLoop() {
        while (scope.isActive) {
            val envelope = pending.receive()
            when (envelope.action) {
                Action.POST -> {
                    publish(envelope)
                    delay(settings.headsUpIntervalMs.milliseconds)
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
        val call = envelope.category == Notification.CATEGORY_CALL
        val channelId = ensureChannel(pkg, envelope.app ?: pkg, call)
        val key = envelope.key ?: "$pkg|${envelope.seq}"
        val id = ids.getOrPut(key) { nextId() }
        while (ids.size > ID_MAP_CAPACITY) {
            val oldest = ids.keys.firstOrNull() ?: break
            ids.remove(oldest)
        }
        persistIds()

        requestIconIfMissing(pkg)

        val app = envelope.app ?: pkg
        val body = listOfNotNull(envelope.text, envelope.sub.takeIf { settings.showSourceApp })
            .joinToString(" · ")

        val builder = Notification.Builder(appContext, channelId)
            .setContentTitle(envelope.title ?: app)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setCategory(if (call) Notification.CATEGORY_CALL else Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(envelope.whenMs ?: envelope.ts ?: System.currentTimeMillis())
            .setDeleteIntent(relay(RelayReceiver.ACTION_DISMISS, key, id, 0))

        @Suppress("DEPRECATION")
        builder.setPriority(Notification.PRIORITY_HIGH)

        val avatar = envelope.avatar?.let { decodeIcon(it) }
        (avatar ?: icons.icon(pkg))?.let(builder::setLargeIcon)

        if (call) builder.setOngoing(true)

        if (settings.showSourceApp) builder.setSubText(app) else envelope.sub?.let(builder::setSubText)

        val picture = envelope.picture?.let { decodeBitmap(it) }
        when {
            envelope.messages.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                builder.setStyle(messagingStyle(envelope))
            }

            picture != null -> {
                builder.setStyle(
                    Notification.BigPictureStyle().bigPicture(picture).setSummaryText(body)
                )
            }

            body.length > 40 -> builder.setStyle(Notification.BigTextStyle().bigText(body))
        }

        envelope.actions.forEach { action -> builder.addAction(build(action, key, id)) }

        if (settings.offerCodes) {
            OneTimeCode.find(envelope.title, envelope.text)?.let { code ->
                builder.addAction(copyAction(code, id))
            }
        }

        manager.notify(id, builder.build())
        _counters.value = _counters.value.copy(
            published = _counters.value.published + 1,
            lastTitle = envelope.title ?: envelope.text,
        )
        BridgeLog.i(
            SCOPE,
            "PUBLISH seq=${envelope.seq} id=$id pkg=$pkg title=${BridgeLog.redact(envelope.title)}" +
                    " actions=${envelope.actions.size}"
        )
        onPublished?.invoke(envelope)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun messagingStyle(envelope: Envelope): Notification.MessagingStyle {
        val self = Person.Builder()
            .setName(appContext.getString(R.string.messaging_you))
            .build()
        val style = Notification.MessagingStyle(self)
            .setConversationTitle(envelope.title)
        val senders = envelope.messages.mapNotNull { it.sender }.toSet()
        style.isGroupConversation = senders.size > 1
        envelope.messages.forEach { message ->
            val person = message.sender?.let { Person.Builder().setName(it).build() }
            style.addMessage(message.text, message.ts, person)
        }
        return style
    }

    private fun decodeIcon(base64: String): Icon? = decodeBitmap(base64)?.let(Icon::createWithBitmap)

    private fun decodeBitmap(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun requestIconIfMissing(pkg: String) {
        if (pkg == "unknown" || icons.has(pkg)) return
        if (!iconsAsked.add(pkg)) return
        onIconNeeded?.invoke(pkg)
    }

    private fun build(action: RemoteAction, key: String, id: Int): Notification.Action {
        val icon = Icon.createWithResource(appContext, R.drawable.ic_stat_bridge)
        if (!action.reply) {
            return Notification.Action.Builder(
                icon,
                action.label,
                relay(RelayReceiver.ACTION_INVOKE, key, id, action.index),
            ).build()
        }
        val input = RemoteInput.Builder(RelayReceiver.REPLY_RESULT_KEY)
            .setLabel(action.label)
            .build()
        return Notification.Action.Builder(
            icon,
            action.label,
            relay(RelayReceiver.ACTION_REPLY, key, id, action.index, mutable = true),
        ).addRemoteInput(input).build()
    }

    private fun copyAction(code: String, id: Int): Notification.Action {
        val intent = CopyActivity.intent(appContext, code, sensitive = true)
            .putExtra(CopyActivity.EXTRA_NOTIFICATION_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            appContext,
            requestCode(id, CODE_REQUEST_SLOT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(appContext, R.drawable.ic_stat_bridge),
            appContext.getString(R.string.action_copy_code, code),
            pending,
        ).build()
    }

    private fun relay(
        action: String,
        key: String,
        id: Int,
        index: Int,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(appContext, RelayReceiver::class.java)
            .setAction(action)
            .putExtra(RelayReceiver.EXTRA_KEY, key)
            .putExtra(RelayReceiver.EXTRA_INDEX, index)
            .putExtra(RelayReceiver.EXTRA_NOTIFICATION_ID, id)
        val mutability = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(id, index),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    private fun requestCode(id: Int, slot: Int) = id * REQUEST_SLOTS + slot

    private fun ensureChannel(pkg: String, label: String, call: Boolean): String {
        if (call) return ensureCallChannel()
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

    private fun ensureCallChannel(): String {
        if (!channels.add(CALL_CHANNEL)) return CALL_CHANNEL
        val channel = NotificationChannel(
            CALL_CHANNEL,
            appContext.getString(R.string.channel_calls),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.channel_calls_desc)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        return CALL_CHANNEL
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
        const val CALL_CHANNEL = "calls"
        const val BASE_ID = 1000
        const val ID_MAP_CAPACITY = 300

        const val REQUEST_SLOTS = 8
        const val CODE_REQUEST_SLOT = 7
    }
}
