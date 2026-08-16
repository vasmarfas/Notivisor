package com.vasmarfas.notivisor.phone.core

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.ChatMessage
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.RemoteAction
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.FilterMode
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import org.json.JSONObject

sealed interface MirrorDecision {
    data class Send(val envelope: Envelope) : MirrorDecision
    data class Skip(val reason: String) : MirrorDecision
}

class NotificationMirror(context: Context, private val settings: BridgeSettings) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = context.applicationContext.packageManager
    private val ownPackage = context.packageName
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val labels = HashMap<String, String>()
    private val lastContent = LinkedHashMap<String, Int>(64, 0.75f, true)

    init {
        restore()
    }

    fun inspect(sbn: StatusBarNotification): MirrorDecision {
        val notification = sbn.notification
        val pkg = sbn.packageName

        if (pkg == ownPackage) return MirrorDecision.Skip("own notification")

        val flags = notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return MirrorDecision.Skip("group summary")
        if (flags and Notification.FLAG_ONGOING_EVENT != 0 && !settings.mirrorOngoing) {
            return MirrorDecision.Skip("ongoing")
        }
        if (notification.category in MUTED_CATEGORIES) {
            return MirrorDecision.Skip("category ${notification.category}")
        }
        if (isMediaStyle(notification)) return MirrorDecision.Skip("media style")

        when (settings.filterMode) {
            FilterMode.ALLOW_ALL -> if (pkg in settings.blockedPackages) {
                return MirrorDecision.Skip("package blocked")
            }

            FilterMode.ALLOWLIST -> if (pkg !in settings.allowedPackages) {
                return MirrorDecision.Skip("package not in allowlist")
            }
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
                )?.toString()?.trim()
        val sub = (
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
                )?.toString()?.trim()

        if (title.isNullOrEmpty() && text.isNullOrEmpty()) return MirrorDecision.Skip("no text")

        val envelope = Envelope(
            action = Action.POST,
            key = sbn.key,
            pkg = pkg,
            app = appLabel(pkg),
            title = title,
            text = text,
            sub = sub,
            ts = System.currentTimeMillis(),
            whenMs = notification.`when`.takeIf { it > 0 },
            prio = @Suppress("DEPRECATION") notification.priority,
            group = notification.group,
            count = extras.getInt(Notification.EXTRA_PROGRESS, 0).takeIf { it > 0 },
            silent = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            category = notification.category,
            actions = remoteActions(notification),
            messages = conversation(notification),
            avatar = richContentAllowed { RichContent.avatar(appContext, notification.getLargeIcon()) },
            picture = richContentAllowed { RichContent.picture(bigPicture(extras)) },
        )

        val hash = envelope.contentHash()
        if (lastContent[sbn.key] == hash) return MirrorDecision.Skip("duplicate content")
        remember(sbn.key, hash)

        return MirrorDecision.Send(envelope)
    }

    fun removal(sbn: StatusBarNotification): Envelope? {
        if (sbn.packageName == ownPackage) return null
        lastContent.remove(sbn.key)
        persist()
        return Envelope(action = Action.REMOVE, key = sbn.key, pkg = sbn.packageName)
    }

    fun forget() {
        lastContent.clear()
        persist()
    }

    fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun remember(key: String, hash: Int) {
        lastContent[key] = hash
        while (lastContent.size > DEDUP_CAPACITY) {
            val oldest = lastContent.keys.firstOrNull() ?: break
            lastContent.remove(oldest)
        }
        persist()
    }

    private fun persist() {
        val json = JSONObject()
        lastContent.entries.toList().takeLast(DEDUP_CAPACITY).forEach { json.put(it.key, it.value) }
        prefs.edit { putString(KEY_SEEN, json.toString()) }
    }

    private fun restore() {
        val raw = prefs.getString(KEY_SEEN, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { key -> lastContent[key] = json.getInt(key) }
        }.onFailure { BridgeLog.w(SCOPE, "could not restore de-duplication state: ${it.message}") }
    }

    private fun remoteActions(notification: Notification): List<RemoteAction> {
        if (!settings.mirrorActions) return emptyList()
        val actions = notification.actions ?: return emptyList()
        return actions.take(MAX_ACTIONS).mapIndexedNotNull { index, action ->
            if (action.actionIntent == null) return@mapIndexedNotNull null
            val label = action.title?.toString()?.trim()
            if (label.isNullOrEmpty()) return@mapIndexedNotNull null
            RemoteAction(
                index = index,
                label = label,
                reply = action.remoteInputs?.any { it.allowFreeFormInput } == true,
            )
        }
    }

    private fun conversation(notification: Notification): List<ChatMessage> {
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification) ?: return emptyList()
        return style.messages.takeLast(MAX_MESSAGES).mapNotNull { message ->
            val text = message.text?.toString()?.trim()
            if (text.isNullOrEmpty()) return@mapNotNull null
            ChatMessage(
                sender = message.person?.name?.toString(),
                text = text,
                ts = message.timestamp,
            )
        }
    }

    private inline fun richContentAllowed(block: () -> String?): String? =
        if (settings.transportKind == TransportKind.TCP) block() else null

    private fun bigPicture(extras: Bundle): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelable(Notification.EXTRA_PICTURE)
    }

    private fun isMediaStyle(notification: Notification): Boolean {
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE) ?: return false
        return template.endsWith("MediaStyle") || template.endsWith("DecoratedMediaCustomViewStyle")
    }

    private companion object {
        const val SCOPE = "mirror"
        const val PREFS = "Notivisor_mirror"
        const val KEY_SEEN = "seen"
        const val DEDUP_CAPACITY = 500
        const val MAX_ACTIONS = 3
        const val MAX_MESSAGES = 10
        val MUTED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
        )
    }
}
