package com.vasmarfas.notivisor.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.protocol.PairingPayload
import com.vasmarfas.notivisor.core.transport.LinkRole
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.util.BridgeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

enum class FilterMode {
    ALLOW_ALL,

    ALLOWLIST,
}

class BridgeSettings private constructor(private val prefs: SharedPreferences) {

    private val _revision = MutableStateFlow(0)

    val revision: StateFlow<Int> = _revision.asStateFlow()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = put { putBoolean(KEY_ENABLED, value) }

    var stoppedByUser: Boolean
        get() = prefs.getBoolean(KEY_STOPPED_BY_USER, false)
        set(value) = put { putBoolean(KEY_STOPPED_BY_USER, value) }

    var roleOverride: DeviceRole?
        get() = prefs.getString(KEY_ROLE_OVERRIDE, null)
            ?.let { name -> runCatching { DeviceRole.valueOf(name) }.getOrNull() }
        set(value) = put {
            if (value == null) remove(KEY_ROLE_OVERRIDE) else putString(
                KEY_ROLE_OVERRIDE,
                value.name
            )
        }

    var transportKind: TransportKind
        get() = runCatching { TransportKind.valueOf(prefs.getString(KEY_TRANSPORT, null) ?: "") }
            .getOrDefault(TransportKind.BLE)
        set(value) = put { putString(KEY_TRANSPORT, value.name) }

    var bleServerIsSource: Boolean
        get() = prefs.getBoolean(KEY_BLE_SERVER_IS_SOURCE, true)
        set(value) = put { putBoolean(KEY_BLE_SERVER_IS_SOURCE, value) }

    var tcpHost: String?
        get() = prefs.getString(KEY_TCP_HOST, null)?.takeIf { it.isNotBlank() }
        set(value) = put { putString(KEY_TCP_HOST, value) }

    var tcpPort: Int
        get() = prefs.getInt(KEY_TCP_PORT, TransportConfig.DEFAULT_TCP_PORT)
        set(value) = put { putInt(KEY_TCP_PORT, value) }

    var filterMode: FilterMode
        get() = runCatching { FilterMode.valueOf(prefs.getString(KEY_FILTER_MODE, null) ?: "") }
            .getOrDefault(FilterMode.ALLOW_ALL)
        set(value) = put { putString(KEY_FILTER_MODE, value.name) }

    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, emptySet())!!.toSet()
        set(value) = put { putStringSet(KEY_BLOCKED, value) }

    var allowedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED, emptySet())!!.toSet()
        set(value) = put { putStringSet(KEY_ALLOWED, value) }

    var headsUpIntervalMs: Long
        get() = prefs.getLong(KEY_HEADS_UP_INTERVAL, 2_500L)
        set(value) = put { putLong(KEY_HEADS_UP_INTERVAL, value) }

    var mirrorOngoing: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ONGOING, false)
        set(value) = put { putBoolean(KEY_MIRROR_ONGOING, value) }

    var showSourceApp: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SOURCE_APP, true)
        set(value) = put { putBoolean(KEY_SHOW_SOURCE_APP, value) }

    var mirrorActions: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ACTIONS, true)
        set(value) = put { putBoolean(KEY_MIRROR_ACTIONS, value) }

    var offerCodes: Boolean
        get() = prefs.getBoolean(KEY_OFFER_CODES, true)
        set(value) = put { putBoolean(KEY_OFFER_CODES, value) }

    var autoDnd: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DND, false)
        set(value) = put { putBoolean(KEY_AUTO_DND, value) }

    var presenceGated: Boolean
        get() = prefs.getBoolean(KEY_PRESENCE_GATED, false)
        set(value) = put { putBoolean(KEY_PRESENCE_GATED, value) }

    var pairingCode: String?
        get() = prefs.getString(KEY_PAIRING_CODE, null)?.takeIf { Pairing.isValid(it) }
        set(value) {
            put {
                if (value == null) {
                    remove(KEY_PAIRING_CODE)
                    remove(KEY_DERIVED_KEY)
                } else {
                    putString(KEY_PAIRING_CODE, value)
                    remove(KEY_DERIVED_KEY)
                }
            }
            cachedKey = null
        }

    var encryptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENCRYPTION, true)
        set(value) = put { putBoolean(KEY_ENCRYPTION, value) }

    private var cachedKey: SecretKey? = null

    fun sessionKey(): SecretKey? {
        if (!encryptionEnabled) return null
        cachedKey?.let { return it }
        val code = pairingCode ?: return null

        prefs.getString(KEY_DERIVED_KEY, null)?.let { stored ->
            return runCatching { Pairing.decodeKey(stored) }.getOrNull()?.also { cachedKey = it }
        }

        val started = System.currentTimeMillis()
        val key = Pairing.deriveKey(code)
        BridgeLog.i("settings", "derived pairing key in ${System.currentTimeMillis() - started} ms")
        prefs.edit { putString(KEY_DERIVED_KEY, Pairing.encodeKey(key)) }
        cachedKey = key
        return key
    }

    fun apply(payload: PairingPayload) {
        pairingCode = payload.code
        encryptionEnabled = true
        transportKind = payload.transport
        bleServerIsSource = payload.bleServerIsSource
        tcpPort = payload.port
        showSourceApp = payload.showSourceApp
        payload.host?.let { tcpHost = it }
    }

    fun pairingPayload(localHost: String?) = PairingPayload(
        code = pairingCode ?: "",
        transport = transportKind,
        bleServerIsSource = bleServerIsSource,
        host = localHost,
        port = tcpPort,
        showSourceApp = showSourceApp,
    )

    fun transportConfig(role: LinkRole) = TransportConfig(
        kind = transportKind,
        role = role,
        bleServerIsSource = bleServerIsSource,
        tcpPort = tcpPort,
        tcpHost = tcpHost,
    )

    private inline fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _revision.value = _revision.value + 1
    }

    companion object {
        private const val FILE = "Notivisor"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STOPPED_BY_USER = "stopped_by_user"
        private const val KEY_ROLE_OVERRIDE = "role_override"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_BLE_SERVER_IS_SOURCE = "ble_server_is_source"
        private const val KEY_TCP_HOST = "tcp_host"
        private const val KEY_TCP_PORT = "tcp_port"
        private const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_ALLOWED = "allowed_packages"
        private const val KEY_HEADS_UP_INTERVAL = "heads_up_interval"
        private const val KEY_MIRROR_ONGOING = "mirror_ongoing"
        private const val KEY_SHOW_SOURCE_APP = "show_source_app"
        private const val KEY_MIRROR_ACTIONS = "mirror_actions"
        private const val KEY_OFFER_CODES = "offer_codes"
        private const val KEY_AUTO_DND = "auto_dnd"
        private const val KEY_PRESENCE_GATED = "presence_gated"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_DERIVED_KEY = "derived_key"
        private const val KEY_ENCRYPTION = "encryption"

        @Volatile
        private var instance: BridgeSettings? = null

        fun get(context: Context): BridgeSettings = instance ?: synchronized(this) {
            instance ?: BridgeSettings(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
