package com.vasmarfas.notivisor.core.protocol

import org.json.JSONArray
import org.json.JSONObject

const val PROTOCOL_VERSION = 2

object Action {
    const val HELLO = "hello"
    const val POST = "post"
    const val REMOVE = "remove"
    const val PING = "ping"
    const val PONG = "pong"
    const val ACK = "ack"
    const val STATS = "stats"

    const val INVOKE = "invoke"

    const val REPLY = "reply"

    const val DISMISS = "dismiss"

    const val ICON_REQ = "icon_req"

    const val ICON = "icon"

    const val CLIP = "clip"

    const val OPEN = "open"

    const val STATUS = "status"

    const val FIND = "find"

    const val MEDIA = "media"

    const val VOLUME = "volume"

    const val TEXT_REQ = "text_req"

    const val TEXT = "text"

    const val TEXT_DONE = "text_done"

    const val MIRROR_START = "mirror_start"

    const val MIRROR_STOP = "mirror_stop"

    const val CAST_START = "cast_start"

    const val CAST_STOP = "cast_stop"

    const val PROXIMITY = "proximity"
}

object MediaKey {
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
}

data class RemoteAction(
    val index: Int,
    val label: String,
    val reply: Boolean = false,
)

data class ChatMessage(
    val sender: String?,
    val text: String,
    val ts: Long,
)

data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val action: String,
    val seq: Long = 0L,
    val key: String? = null,
    val pkg: String? = null,
    val app: String? = null,
    val title: String? = null,
    val text: String? = null,
    val sub: String? = null,
    val ts: Long? = null,
    val whenMs: Long? = null,
    val prio: Int? = null,
    val group: String? = null,
    val count: Int? = null,
    val silent: Boolean? = null,
    val category: String? = null,
    val actions: List<RemoteAction> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val idx: Int? = null,
    val data: String? = null,
    val battery: Int? = null,
    val worn: Boolean? = null,
    val prox: Boolean? = null,
    val avatar: String? = null,
    val picture: String? = null,
) {

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", v)
        o.put("action", action)
        if (seq != 0L) o.put("seq", seq)
        key?.let { o.put("key", it) }
        pkg?.let { o.put("pkg", it) }
        app?.let { o.put("app", it) }
        title?.let { o.put("title", it) }
        text?.let { o.put("text", it) }
        sub?.let { o.put("sub", it) }
        ts?.let { o.put("ts", it) }
        whenMs?.let { o.put("when", it) }
        prio?.let { o.put("prio", it) }
        group?.let { o.put("group", it) }
        count?.let { o.put("count", it) }
        silent?.let { o.put("silent", it) }
        category?.let { o.put("cat", it) }
        idx?.let { o.put("idx", it) }
        data?.let { o.put("data", it) }
        battery?.let { o.put("bat", it) }
        worn?.let { o.put("worn", it) }
        prox?.let { o.put("prox", it) }
        avatar?.let { o.put("av", it) }
        picture?.let { o.put("pic", it) }
        if (actions.isNotEmpty()) {
            val array = JSONArray()
            actions.forEach { act ->
                array.put(
                    JSONObject()
                        .put("i", act.index)
                        .put("l", act.label)
                        .apply { if (act.reply) put("r", true) }
                )
            }
            o.put("acts", array)
        }
        if (messages.isNotEmpty()) {
            val array = JSONArray()
            messages.forEach { msg ->
                array.put(
                    JSONObject()
                        .put("t", msg.text)
                        .put("ts", msg.ts)
                        .apply { msg.sender?.let { put("s", it) } }
                )
            }
            o.put("msgs", array)
        }
        return o.toString()
    }

    fun contentHash(): Int =
        listOf(pkg, title, text, sub, count?.toString()).joinToString(" ").hashCode()

    companion object {
        fun parse(line: String): Envelope {
            val o = JSONObject(line)
            return Envelope(
                v = o.optInt("v", PROTOCOL_VERSION),
                action = o.optString("action", ""),
                seq = o.optLong("seq", 0L),
                key = o.optStringOrNull("key"),
                pkg = o.optStringOrNull("pkg"),
                app = o.optStringOrNull("app"),
                title = o.optStringOrNull("title"),
                text = o.optStringOrNull("text"),
                sub = o.optStringOrNull("sub"),
                ts = o.optLongOrNull("ts"),
                whenMs = o.optLongOrNull("when"),
                prio = o.optIntOrNull("prio"),
                group = o.optStringOrNull("group"),
                count = o.optIntOrNull("count"),
                silent = if (o.has("silent")) o.optBoolean("silent") else null,
                category = o.optStringOrNull("cat"),
                actions = o.optActions(),
                messages = o.optMessages(),
                idx = o.optIntOrNull("idx"),
                data = o.optStringOrNull("data"),
                battery = o.optIntOrNull("bat"),
                worn = if (o.has("worn")) o.optBoolean("worn") else null,
                prox = if (o.has("prox")) o.optBoolean("prox") else null,
                avatar = o.optStringOrNull("av"),
                picture = o.optStringOrNull("pic"),
            )
        }

        fun ping(seq: Long) =
            Envelope(action = Action.PING, seq = seq, ts = System.currentTimeMillis())

        fun pong(seq: Long) =
            Envelope(action = Action.PONG, seq = seq, ts = System.currentTimeMillis())

        fun ack(seq: Long, key: String?) = Envelope(action = Action.ACK, seq = seq, key = key)
        fun hello(role: String, device: String) =
            Envelope(
                action = Action.HELLO,
                pkg = role,
                app = device,
                ts = System.currentTimeMillis()
            )
    }
}

private fun JSONObject.optActions(): List<RemoteAction> {
    val array = optJSONArray("acts") ?: return emptyList()
    val out = ArrayList<RemoteAction>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val label = item.optString("l").takeIf { it.isNotEmpty() } ?: continue
        out += RemoteAction(
            index = item.optInt("i", i),
            label = label,
            reply = item.optBoolean("r", false),
        )
    }
    return out
}

private fun JSONObject.optMessages(): List<ChatMessage> {
    val array = optJSONArray("msgs") ?: return emptyList()
    val out = ArrayList<ChatMessage>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        out += ChatMessage(
            sender = item.optStringOrNull("s"),
            text = item.optString("t"),
            ts = item.optLong("ts"),
        )
    }
    return out
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
