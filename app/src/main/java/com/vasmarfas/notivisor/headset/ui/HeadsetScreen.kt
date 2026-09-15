package com.vasmarfas.notivisor.headset.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.AppRole
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.RoleCard
import com.vasmarfas.notivisor.core.control.MirrorState
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.control.ScreenReceiver
import com.vasmarfas.notivisor.core.protocol.Action
import com.vasmarfas.notivisor.core.protocol.Envelope
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.protocol.PairingPayload
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.OverlayMode
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.NsdHelper
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.transport.ble.BlePermissions
import com.vasmarfas.notivisor.core.ui.AboutCard
import com.vasmarfas.notivisor.core.ui.HealthTone
import com.vasmarfas.notivisor.core.ui.InfoRow
import com.vasmarfas.notivisor.core.ui.LogPanel
import com.vasmarfas.notivisor.core.ui.SectionCard
import com.vasmarfas.notivisor.core.ui.SegmentedChoice
import com.vasmarfas.notivisor.core.ui.SettingRow
import com.vasmarfas.notivisor.core.ui.StatRow
import com.vasmarfas.notivisor.core.ui.StatusBanner
import com.vasmarfas.notivisor.core.ui.VersionNotice
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge
import com.vasmarfas.notivisor.headset.core.HeadsetOverlay
import com.vasmarfas.notivisor.headset.core.HeadsetProximity
import com.vasmarfas.notivisor.headset.core.ShadeState
import com.vasmarfas.notivisor.headset.service.HeadsetCastService
import com.vasmarfas.notivisor.headset.service.RemoteKeyboardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HeadsetScreen() {
    val context = LocalContext.current
    HeadsetBridge.init(context)
    val settings = HeadsetBridge.settings

    val revision by settings.revision.collectAsStateWithLifecycle()
    val linkState by HeadsetBridge.link.state.collectAsStateWithLifecycle()
    val stats by HeadsetBridge.link.stats.collectAsStateWithLifecycle()
    val counters by HeadsetBridge.publisher.counters.collectAsStateWithLifecycle()
    val log by BridgeLog.lines.collectAsStateWithLifecycle()
    val peer by HeadsetBridge.link.peer.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(HeadsetTab.HOME) }
    var scanning by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    var scanOutcome by remember { mutableStateOf<Int?>(null) }
    var scanDetail by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var hostInput by remember { mutableStateOf(settings.tcpHost.orEmpty()) }
    var portInput by remember { mutableStateOf(settings.tcpPort.toString()) }
    var manualAddress by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activity = LocalActivity.current
    val cameraDeclared = remember { HeadsetCamera.isDeclared(context) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scanning = HeadsetCamera.hasBasicPermission(context) }

    val startupPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { HeadsetBridge.restartLink("permissions changed") }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            addAll(BlePermissions.forClient())
            addAll(BlePermissions.forServer())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()
        val missing = BlePermissions.missing(context, wanted)
        if (missing.isNotEmpty()) startupPermissions.launch(missing.toTypedArray())
    }

    if (scanning) {
        val wifiLabel = stringResource(R.string.transport_wifi)
        val bluetoothLabel = stringResource(R.string.transport_bluetooth)
        QrScannerScreen(
            onResult = { raw ->
                val payload = PairingPayload.parse(raw)
                if (payload == null) {
                    scanOutcome = R.string.pairing_rejected
                    scanDetail = null
                    BridgeLog.w(SCOPE, "scanned code is not a pairing payload")
                } else {
                    settings.apply(payload)
                    hostInput = settings.tcpHost.orEmpty()
                    portInput = settings.tcpPort.toString()
                    scanOutcome = R.string.pairing_applied
                    scanDetail = buildString {
                        val wifi = settings.transportKind == TransportKind.TCP
                        append(if (wifi) wifiLabel else bluetoothLabel)
                        if (wifi) settings.tcpHost?.let { append(" · $it") }
                    }
                    HeadsetBridge.restartLink("paired by QR")
                }
                scanning = false
            },
            onCancel = { scanning = false },
        )
        return
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text(stringResource(R.string.quit_title)) },
            text = { Text(stringResource(R.string.quit_message_headset)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmQuit = false
                    activity?.let { AppRole.quit(it) }
                }) { Text(stringResource(R.string.quit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuit = false }) {
                    Text(stringResource(R.string.quit_cancel))
                }
            },
        )
    }

    scanOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { scanOutcome = null },
            title = { Text(stringResource(outcome)) },
            text = { scanDetail?.let { Text(it) } },
            confirmButton = {
                TextButton(onClick = { scanOutcome = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                HeadsetTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = {
                            Text(
                                stringResource(entry.label),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }

            when (tab) {
                HeadsetTab.HOME -> {
                item {
                    StatusBanner(
                        tone = linkState.tone(),
                        headline = linkState.headline(context),
                        detail = if (linkState is LinkState.Failed && (linkState as LinkState.Failed).pairing) {
                            stringResource(R.string.detail_mismatch_headset)
                        } else {
                            stringResource(
                                if (revision.let { settings.transportKind } == TransportKind.BLE) {
                                    R.string.transport_bluetooth
                                } else {
                                    R.string.transport_wifi
                                }
                            )
                        },
                        facts = listOf(
                            stringResource(R.string.stat_shown) to counters.published.toString(),
                            stringResource(R.string.stat_received) to counters.received.toString(),
                            stringResource(R.string.stat_reconnects) to stats.reconnects.toString(),
                        ),
                    )
                }

                item { VersionNotice(peer) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val missing = BlePermissions.missing(context, HeadsetCamera.permissions)
                                if (missing.isEmpty()) {
                                    scanning = true
                                } else {
                                    cameraLauncher.launch(missing.toTypedArray())
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_scan)) }
                        OutlinedButton(
                            onClick = { HeadsetBridge.selfTest() },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_test_notification)) }
                    }
                }

                item {
                    SectionCard(
                        title = stringResource(R.string.section_stats),
                        trailing = {
                            TextButton(onClick = { HeadsetBridge.restartLink("user") }) {
                                Text(stringResource(R.string.action_restart))
                            }
                        },
                    ) {
                        StatRow(
                            listOf(
                                stringResource(R.string.stat_shown) to counters.published.toString(),
                                stringResource(R.string.stat_cleared) to counters.removed.toString(),
                                stringResource(R.string.stat_ping) to (stats.lastRttMs?.let { "$it ms" }
                                    ?: "—"),
                                stringResource(R.string.stat_reconnects) to stats.reconnects.toString(),
                            )
                        )
                        LogPanel(log, maxHeight = 260)
                        TextButton(onClick = { BridgeLog.share(context) }) {
                            Text(stringResource(R.string.action_share_log))
                        }
                    }
                }

                item { RoleCard() }

                item { AboutCard() }

                item {
                    TextButton(onClick = { confirmQuit = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_quit), color = MaterialTheme.colorScheme.error)
                    }
                }
                }

                HeadsetTab.LINK -> {
                item {
                    SectionCard(
                        title = stringResource(R.string.section_pairing),
                        subtitle = stringResource(
                            if (settings.pairingCode != null) R.string.pairing_done else R.string.pairing_none
                        ),
                    ) {
                        if (!cameraDeclared) {
                            Text(
                                stringResource(R.string.scan_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = Pairing.normalise(it).take(Pairing.CODE_LENGTH) },
                            label = { Text(stringResource(R.string.label_code)) },
                            placeholder = { Text(stringResource(R.string.label_code_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (Pairing.isValid(codeInput)) {
                                    settings.pairingCode = codeInput
                                    codeInput = ""
                                    HeadsetBridge.restartLink("pairing code entered")
                                }
                            },
                            enabled = Pairing.isValid(codeInput),
                        ) { Text(stringResource(R.string.action_save)) }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.section_connection)) {
                        val kind = revision.let { settings.transportKind }
                        SegmentedChoice(
                            options = listOf(TransportKind.BLE, TransportKind.TCP),
                            selected = kind,
                            label = {
                                stringResource(
                                    if (it == TransportKind.BLE) R.string.transport_bluetooth else R.string.transport_wifi
                                )
                            },
                            onSelect = {
                                settings.transportKind = it
                                HeadsetBridge.restartLink("transport = $it")
                            },
                        )
                        if (kind == TransportKind.BLE) {
                            Text(
                                stringResource(R.string.label_who_searches),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            SegmentedChoice(
                                options = listOf(true, false),
                                selected = revision.let { settings.bleServerIsSource },
                                label = { stringResource(if (it) R.string.who_headset else R.string.who_phone) },
                                onSelect = {
                                    settings.bleServerIsSource = it
                                    HeadsetBridge.restartLink("search direction changed")
                                },
                            )
                            Text(
                                stringResource(R.string.label_who_hint_headset),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            InfoRow(
                                title = stringResource(R.string.label_host),
                                detail = revision.let { settings.tcpHost }
                                    ?: stringResource(R.string.address_unknown),
                            )
                            Text(
                                stringResource(
                                    if (searchFailed) R.string.find_phone_failed
                                    else R.string.find_phone_hint
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (searchFailed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    enabled = !searching,
                                    onClick = {
                                        searching = true
                                        searchFailed = false
                                        scope.launch {
                                            val found = NsdHelper(context).discoverHost()
                                            searching = false
                                            if (found == null) {
                                                searchFailed = true
                                            } else {
                                                settings.tcpHost = found
                                                hostInput = found
                                                HeadsetBridge.restartLink("phone found at $found")
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(
                                            if (searching) R.string.action_searching
                                            else R.string.action_find_phone
                                        )
                                    )
                                }
                                TextButton(onClick = { manualAddress = !manualAddress }) {
                                    Text(stringResource(R.string.action_manual_address))
                                }
                            }
                            if (manualAddress) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = hostInput,
                                        onValueChange = { hostInput = it.trim() },
                                        label = { Text(stringResource(R.string.label_host)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Next,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = portInput,
                                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                                        label = { Text(stringResource(R.string.label_port)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.widthIn(min = 120.dp),
                                    )
                                }
                                Button(onClick = {
                                    settings.tcpHost = hostInput.takeIf { it.isNotBlank() }
                                    settings.tcpPort =
                                        portInput.toIntOrNull() ?: TransportConfig.DEFAULT_TCP_PORT
                                    HeadsetBridge.restartLink("address changed")
                                }) { Text(stringResource(R.string.action_save)) }
                            }
                        }
                    }
                }
                }

                HeadsetTab.STREAM -> {
                item { ScreenMirrorSection(settings) }

                item { HeadsetCastSection(settings) }
                }

                HeadsetTab.NOTIFICATIONS -> {
                item { ShadeSection(settings) }

                item {
                    SectionCard(
                        title = stringResource(R.string.section_notifications),
                        subtitle = stringResource(R.string.section_notifications_hint_headset),
                    ) {

                        val pasteReady by ScrcpySession.controlReady.collectAsStateWithLifecycle()
                        val keyboardOn = remember { RemoteKeyboardService.isEnabled(context) }
                        InfoRow(
                            title = stringResource(R.string.setup_paste),
                            detail = stringResource(
                                when {
                                    pasteReady -> R.string.setup_paste_ready
                                    keyboardOn -> R.string.setup_paste_keyboard
                                    else -> R.string.setup_paste_manual
                                }
                            ),
                        )
                        SettingRow(
                            title = stringResource(R.string.label_show_source),
                            subtitle = stringResource(R.string.label_show_source_hint),
                            checked = revision.let { settings.showSourceApp },
                        ) { settings.showSourceApp = it }

                        Text(
                            stringResource(R.string.label_gap),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SegmentedChoice(
                            options = listOf(0L, 1_000L, 2_500L, 5_000L),
                            selected = revision.let { settings.headsUpIntervalMs },
                            label = {
                                if (it == 0L) {
                                    stringResource(R.string.gap_none)
                                } else {
                                    stringResource(R.string.gap_seconds, it / 1000)
                                }
                            },
                            onSelect = { settings.headsUpIntervalMs = it },
                        )
                        Text(
                            stringResource(R.string.label_gap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private enum class HeadsetTab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LINK(R.string.tab_link, Icons.Filled.Share),
    STREAM(R.string.tab_stream, Icons.Filled.PlayArrow),
    NOTIFICATIONS(R.string.tab_notifications, Icons.Filled.Notifications),
}

private fun LinkState.tone(): HealthTone = when (this) {
    is LinkState.Connected -> HealthTone.Positive
    is LinkState.Connecting, is LinkState.Starting, is LinkState.Waiting -> HealthTone.Working
    is LinkState.Failed -> HealthTone.Bad
    is LinkState.Stopped -> HealthTone.Idle
}

private fun LinkState.headline(context: Context): String = context.getString(
    when (this) {
        is LinkState.Connected -> R.string.state_connected
        is LinkState.Connecting -> R.string.state_connecting
        is LinkState.Waiting -> R.string.state_waiting_headset
        is LinkState.Starting -> R.string.state_starting
        is LinkState.Failed -> if (pairing) R.string.state_mismatch else R.string.state_failed
        is LinkState.Stopped -> R.string.state_stopped_headset
    }
)

@Composable
private fun ScreenMirrorSection(settings: BridgeSettings) {
    val context = LocalContext.current
    val receiver = remember { ScreenReceiver() }
    val mirrorState by receiver.state.collectAsStateWithLifecycle()
    var surface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) { onDispose { receiver.stop() } }

    val host = settings.tcpHost
    val connected = mirrorState is MirrorState.Connected

    SectionCard(
        title = stringResource(R.string.section_mirror),
        subtitle = stringResource(R.string.section_mirror_hint),
    ) {

        val aspect = (mirrorState as? MirrorState.Connected)
            ?.let { it.width.toFloat() / it.height }
            ?: PREVIEW_ASPECT
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier
                    .height(260.dp)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(14.dp)),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surface = holder.surface
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surface = null
                            }
                        })
                    }
                },
            )
        }
        Text(
            when (val state = mirrorState) {
                is MirrorState.Idle -> stringResource(R.string.mirror_idle)
                is MirrorState.Connecting -> stringResource(R.string.mirror_connecting)
                is MirrorState.Connected ->
                    stringResource(R.string.mirror_connected, state.width, state.height, state.frames)

                is MirrorState.Failed -> stringResource(R.string.mirror_failed, state.reason)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (host == null) {
            Text(
                stringResource(R.string.mirror_needs_wifi),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val currentSurface = surface
                    if (connected) {
                        receiver.stop()
                        HeadsetBridge.send(Envelope(action = Action.MIRROR_STOP))
                    } else if (currentSurface != null && host != null) {

                        HeadsetBridge.send(Envelope(action = Action.MIRROR_START))
                        receiver.start(host, TransportConfig.SCREEN_STREAM_PORT, currentSurface)
                    }
                },
                enabled = host != null && (connected || surface != null),
            ) {
                Text(stringResource(if (connected) R.string.action_stop_mirror else R.string.action_start_mirror))
            }

            OutlinedButton(
                onClick = {
                    receiver.stop()
                    MirrorActivity.open(context)
                },
                enabled = host != null,
            ) { Text(stringResource(R.string.action_open_mirror_window)) }
        }
    }
}

@Composable
private fun ShadeSection(settings: BridgeSettings) {
    val context = LocalContext.current
    val shade by HeadsetBridge.publisher.shade.collectAsStateWithLifecycle()
    var overlayOn by remember { mutableStateOf(settings.overlayMode) }
    var canDraw by remember { mutableStateOf(HeadsetOverlay.granted(context)) }
    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { canDraw = HeadsetOverlay.granted(context) }

    SectionCard(
        title = stringResource(R.string.section_shade),
        subtitle = stringResource(
            when (shade) {
                ShadeState.UNKNOWN -> R.string.shade_unknown
                ShadeState.OK -> R.string.shade_ok
                ShadeState.APP_BLOCKED -> R.string.shade_blocked_app
                ShadeState.CHANNEL_BLOCKED -> R.string.shade_blocked_channel
                ShadeState.DROPPED -> R.string.shade_dropped
            }
        ),
    ) {
        if (shade == ShadeState.APP_BLOCKED || shade == ShadeState.CHANNEL_BLOCKED) {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { context.startActivity(intent) }
                if (opened.isFailure) {
                    BridgeLog.w(SCOPE, "no notification settings screen on this headset")
                }
            }) { Text(stringResource(R.string.action_open_notification_settings)) }
        }

        Text(
            stringResource(R.string.setting_overlay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SegmentedChoice(
            options = listOf(OverlayMode.NONE, OverlayMode.PANEL, OverlayMode.TOAST),
            selected = overlayOn,
            label = {
                stringResource(
                    when (it) {
                        OverlayMode.NONE -> R.string.overlay_off
                        OverlayMode.PANEL -> R.string.overlay_panel
                        OverlayMode.TOAST -> R.string.overlay_toast
                    }
                )
            },
            onSelect = { mode ->
                overlayOn = mode
                settings.overlayMode = mode
            },
        )

        if (overlayOn == OverlayMode.PANEL && !canDraw) {
            Text(
                stringResource(R.string.overlay_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { overlayPermission.launch(intent) }
                if (opened.isFailure) {
                    BridgeLog.w(SCOPE, "no overlay permission screen on this headset")
                }
            }) { Text(stringResource(R.string.action_allow_overlay)) }
        }
    }
}

@Composable
private fun HeadsetCastSection(settings: BridgeSettings) {
    val context = LocalContext.current
    val casting by HeadsetCastService.running.collectAsStateWithLifecycle()
    val host = settings.tcpHost

    SectionCard(
        title = stringResource(R.string.section_cast),
        subtitle = stringResource(R.string.section_cast_hint_headset),
    ) {
        Button(
            onClick = {
                if (casting) {
                    HeadsetCastService.stop(context)
                    HeadsetBridge.send(Envelope(action = Action.CAST_STOP))
                } else {
                    HeadsetBridge.send(Envelope(action = Action.CAST_START))
                    HeadsetCastService.start(context, settings.castSource)
                }
            },
            enabled = host != null,
        ) {
            Text(stringResource(if (casting) R.string.action_stop_cast else R.string.action_start_cast))
        }
        Text(
            stringResource(if (host == null) R.string.cast_needs_wifi else R.string.cast_needs_adb),
            style = MaterialTheme.typography.bodySmall,
            color = if (host == null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        var sensorOff by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) { sensorOff = withContext(Dispatchers.IO) { HeadsetProximity.state(context) } }

        OutlinedButton(onClick = {
            val wanted = sensorOff != true
            sensorOff = null
            Thread {
                val now = HeadsetProximity.set(context, wanted)
                HeadsetBridge.send(Envelope(action = Action.PROXIMITY, prox = now))
                sensorOff = now
            }.apply { isDaemon = true }.start()
        }) {
            Text(
                stringResource(
                    if (sensorOff == true) R.string.action_enable_proximity
                    else R.string.action_disable_proximity
                )
            )
        }
        Text(
            stringResource(
                when (sensorOff) {
                    true -> R.string.proximity_off
                    false -> R.string.proximity_on
                    null -> R.string.proximity_unknown
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PREVIEW_ASPECT = 0.46f
private const val SCOPE = "ui"
