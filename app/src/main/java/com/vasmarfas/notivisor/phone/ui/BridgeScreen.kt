package com.vasmarfas.notivisor.phone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.AppRole
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.RoleCard
import com.vasmarfas.notivisor.core.adb.AdbPairPrompt
import com.vasmarfas.notivisor.core.control.ScrcpySession
import com.vasmarfas.notivisor.core.protocol.MediaKey
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.settings.FilterMode
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.transport.ble.BlePermissions
import com.vasmarfas.notivisor.core.ui.AboutCard
import com.vasmarfas.notivisor.core.ui.ChecklistRow
import com.vasmarfas.notivisor.core.ui.HealthTone
import com.vasmarfas.notivisor.core.ui.LogPanel
import com.vasmarfas.notivisor.core.ui.SectionCard
import com.vasmarfas.notivisor.core.ui.SegmentedChoice
import com.vasmarfas.notivisor.core.ui.SettingRow
import com.vasmarfas.notivisor.core.ui.StatRow
import com.vasmarfas.notivisor.core.ui.StatusBanner
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.phone.core.DoNotDisturb
import com.vasmarfas.notivisor.phone.core.HeadsetStatus
import com.vasmarfas.notivisor.phone.core.PhoneBridge
import com.vasmarfas.notivisor.phone.listener.NotifyListener
import com.vasmarfas.notivisor.phone.service.BridgeService
import com.vasmarfas.notivisor.phone.service.ScreenCaptureService
import com.vasmarfas.notivisor.phone.service.ShizukuInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen() {
    val context = LocalContext.current
    PhoneBridge.init(context)
    val settings = PhoneBridge.settings

    val revision by settings.revision.collectAsStateWithLifecycle()
    val linkState by PhoneBridge.link.state.collectAsStateWithLifecycle()
    val stats by PhoneBridge.link.stats.collectAsStateWithLifecycle()
    val counters by PhoneBridge.counters.collectAsStateWithLifecycle()
    val listenerConnected by PhoneBridge.listenerConnected.collectAsStateWithLifecycle()
    val headset by PhoneBridge.headset.collectAsStateWithLifecycle()
    val log by BridgeLog.lines.collectAsStateWithLifecycle()

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var permissionTick by remember { mutableStateOf(0) }
    var showPairing by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    LaunchedEffect(Unit) { apps = AppCatalog.load(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
        PhoneBridge.restartLink("permissions changed")
    }

    val visibleApps = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    val listenerReady = remember(permissionTick, listenerConnected) {
        NotifyListener.isEnabled(context) && listenerConnected
    }
    val batteryReady = remember(permissionTick) { isIgnoringBattery(context) }
    val dndReady = remember(permissionTick) { DoNotDisturb.granted(context) }
    val missingPermissions = remember(permissionTick) { missingPermissions(context) }
    val setupDone = listenerReady && batteryReady && missingPermissions.isEmpty()

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text(stringResource(R.string.quit_title)) },
            text = { Text(stringResource(R.string.quit_message)) },
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

    if (showPairing) {
        val code = settings.pairingCode
        if (code == null) {
            showPairing = false
        } else {
            PairingSheet(
                payload = settings.pairingPayload(localAddress()).encode(),
                code = code,
                onDismiss = { showPairing = false },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusBanner(
                    tone = linkState.tone(),
                    headline = linkState.headline(context),
                    detail = linkState.detail(context, settings.transportKind),
                    facts = buildList {
                        add(stringResource(R.string.stat_forwarded) to counters.mirrored.toString())
                        stats.lastRttMs?.let { add(stringResource(R.string.stat_ping) to "$it ms") }
                        stats.connectedSince?.let {
                            add(stringResource(R.string.stat_online) to formatUptime(it))
                        }
                        if (stats.queued > 0) {
                            add(stringResource(R.string.stat_waiting) to stats.queued.toString())
                        }
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { PhoneBridge.sendTest() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_test)) }
                    OutlinedButton(
                        onClick = {
                            if (settings.pairingCode == null) settings.pairingCode =
                                Pairing.generateCode()
                            showPairing = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_pair)) }
                }
            }

            if (!setupDone) {
                item {
                    SectionCard(
                        title = stringResource(R.string.section_setup),
                        subtitle = stringResource(R.string.section_setup_hint),
                    ) {
                        ChecklistRow(
                            done = listenerReady,
                            title = stringResource(R.string.setup_listener),
                            detail = stringResource(R.string.setup_listener_hint),
                        ) {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        ChecklistRow(
                            done = batteryReady,
                            title = stringResource(R.string.setup_battery),
                            detail = stringResource(R.string.setup_battery_hint),
                        ) {
                            permissionTick++
                            requestIgnoreBattery(context)
                        }
                        ChecklistRow(
                            done = missingPermissions.isEmpty(),
                            title = stringResource(R.string.setup_permissions),
                            detail = stringResource(R.string.setup_permissions_hint),
                        ) {
                            if (missingPermissions.isNotEmpty()) {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_connection),
                    subtitle = stringResource(R.string.section_connection_hint),
                ) {
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
                            PhoneBridge.restartLink("transport = $it")
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
                            label = {
                                stringResource(if (it) R.string.who_headset else R.string.who_phone)
                            },
                            onSelect = {
                                settings.bleServerIsSource = it
                                PhoneBridge.restartLink("search direction changed")
                            },
                        )
                        Text(
                            stringResource(R.string.label_who_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        InfoLine(
                            stringResource(R.string.label_address),
                            "${localAddress() ?: "—"}:${settings.tcpPort}"
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_notifications),
                    subtitle = stringResource(R.string.section_notifications_hint),
                ) {
                    SettingRow(
                        title = stringResource(R.string.label_show_source),
                        subtitle = stringResource(R.string.label_show_source_hint),
                        checked = revision.let { settings.showSourceApp },
                    ) { settings.showSourceApp = it }
                    SettingRow(
                        title = stringResource(R.string.label_persistent),
                        subtitle = stringResource(R.string.label_persistent_hint),
                        checked = revision.let { settings.mirrorOngoing },
                    ) { settings.mirrorOngoing = it }
                    SettingRow(
                        title = stringResource(R.string.label_actions),
                        subtitle = stringResource(R.string.label_actions_hint),
                        checked = revision.let { settings.mirrorActions },
                    ) { settings.mirrorActions = it }
                    SettingRow(
                        title = stringResource(R.string.label_codes),
                        subtitle = stringResource(R.string.label_codes_hint),
                        checked = revision.let { settings.offerCodes },
                    ) { settings.offerCodes = it }
                    SettingRow(
                        title = stringResource(R.string.label_dnd),
                        subtitle = stringResource(
                            if (dndReady) R.string.label_dnd_hint else R.string.label_dnd_permission
                        ),
                        checked = revision.let { settings.autoDnd },
                    ) { value ->
                        settings.autoDnd = value

                        if (value && !DoNotDisturb.granted(context)) {
                            permissionTick++
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    SettingRow(
                        title = stringResource(R.string.label_presence),
                        subtitle = stringResource(R.string.label_presence_hint),
                        checked = revision.let { settings.presenceGated },
                    ) { settings.presenceGated = it }
                    SettingRow(
                        title = stringResource(R.string.label_enabled),
                        subtitle = stringResource(R.string.label_enabled_hint),
                        checked = revision.let { settings.enabled },
                    ) { value ->
                        settings.enabled = value
                        if (value) BridgeService.start(context) else PhoneBridge.stopLink()
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.section_headset)) {
                    InfoLine(
                        stringResource(R.string.section_headset),
                        headset.describe(context),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { PhoneBridge.sendClipboard() },
                            enabled = linkState.isConnected,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_send_clipboard)) }
                        OutlinedButton(
                            onClick = { PhoneBridge.findHeadset() },
                            enabled = linkState.isConnected,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_find_headset)) }
                    }

                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, RemoteTypeActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        enabled = linkState.isConnected,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_send_text)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { PhoneBridge.changeHeadsetVolume(-1) },
                            enabled = linkState.isConnected,
                        ) { Text("−") }
                        OutlinedButton(
                            onClick = { PhoneBridge.pressMediaKey(MediaKey.PREVIOUS) },
                            enabled = linkState.isConnected,
                            modifier = Modifier.weight(1f),
                        ) { Text("⏮") }
                        OutlinedButton(
                            onClick = { PhoneBridge.pressMediaKey(MediaKey.PLAY_PAUSE) },
                            enabled = linkState.isConnected,
                            modifier = Modifier.weight(1f),
                        ) { Text("⏯") }
                        OutlinedButton(
                            onClick = { PhoneBridge.pressMediaKey(MediaKey.NEXT) },
                            enabled = linkState.isConnected,
                            modifier = Modifier.weight(1f),
                        ) { Text("⏭") }
                        OutlinedButton(
                            onClick = { PhoneBridge.changeHeadsetVolume(1) },
                            enabled = linkState.isConnected,
                        ) { Text("+") }
                    }
                }
            }

            item {
                val streaming by ScreenCaptureService.running.collectAsStateWithLifecycle()
                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        ScreenCaptureService.start(context, result.resultCode, data)
                    }
                }
                val shizukuReady by ShizukuInput.available.collectAsStateWithLifecycle()
                val controlReady by ScrcpySession.controlReady.collectAsStateWithLifecycle()
                var starting by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { ShizukuInput.connect(context) }

                SectionCard(
                    title = stringResource(R.string.section_mirror),
                    subtitle = stringResource(R.string.section_mirror_hint_phone),
                ) {
                    Button(
                        enabled = !starting,
                        onClick = {
                            if (streaming) {
                                ScreenCaptureService.stop(context)
                                PhoneBridge.sendMirrorState(false)
                                return@Button
                            }

                            PhoneBridge.sendMirrorState(true)

                            starting = true
                            scope.launch(Dispatchers.IO) {
                                val viaScrcpy = ScrcpySession.isAvailable(context)
                                withContext(Dispatchers.Main) {
                                    if (viaScrcpy) {
                                        ScreenCaptureService.startWithScrcpy(context)
                                    } else {
                                        val manager = context.getSystemService(
                                            MediaProjectionManager::class.java
                                        )
                                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                                    }
                                    starting = false
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                when {
                                    streaming -> R.string.action_stop_mirror
                                    starting -> R.string.mirror_connecting
                                    else -> R.string.action_start_mirror
                                }
                            )
                        )
                    }
                    Text(
                        stringResource(
                            when {
                                controlReady -> R.string.setup_control_adb
                                shizukuReady -> R.string.setup_control_shizuku
                                else -> R.string.setup_control_hint
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!controlReady) {

                        TextButton(onClick = {
                            AdbPairPrompt.show(context)
                            AdbPairPrompt.openPairingScreen(context)
                        }) { Text(stringResource(R.string.action_pair_adb)) }
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_stats),
                    trailing = {
                        TextButton(onClick = { PhoneBridge.restartLink("user") }) {
                            Text(stringResource(R.string.action_restart))
                        }
                    },
                ) {
                    StatRow(
                        listOf(
                            stringResource(R.string.stat_sent) to stats.sent.toString(),
                            stringResource(R.string.stat_delivered) to stats.acked.toString(),
                            stringResource(R.string.stat_filtered) to counters.skipped.toString(),
                            stringResource(R.string.stat_lost) to stats.dropped.toString(),
                            stringResource(R.string.stat_reconnects) to stats.reconnects.toString(),
                        )
                    )
                    counters.lastEvent?.let {
                        Text(
                            stringResource(R.string.label_last_event, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row {
                        TextButton(onClick = { showLog = !showLog }) {
                            Text(
                                stringResource(if (showLog) R.string.action_hide_log else R.string.action_show_log)
                            )
                        }
                        TextButton(onClick = { BridgeLog.share(context) }) {
                            Text(stringResource(R.string.action_share_log))
                        }
                    }
                    if (showLog) LogPanel(log)
                }
            }

            item { RoleCard() }

            item { AboutCard() }

            item {
                TextButton(
                    onClick = { confirmQuit = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.action_quit),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_apps),
                    subtitle = stringResource(R.string.section_apps_hint),
                ) {
                    val mode = revision.let { settings.filterMode }
                    SegmentedChoice(
                        options = listOf(FilterMode.ALLOW_ALL, FilterMode.ALLOWLIST),
                        selected = mode,
                        label = {
                            stringResource(
                                if (it == FilterMode.ALLOW_ALL) {
                                    R.string.filter_all_except
                                } else {
                                    R.string.filter_only_checked
                                }
                            )
                        },
                        onSelect = { settings.filterMode = it },
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.hint_search)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (apps.isEmpty()) {
                        Text(
                            stringResource(R.string.apps_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            pluralStringResource(
                                R.plurals.apps_count,
                                visibleApps.size,
                                visibleApps.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(visibleApps, key = { it.pkg }) { app ->
                val mode = revision.let { settings.filterMode }
                val mirrored = revision.let {
                    if (mode == FilterMode.ALLOW_ALL) app.pkg !in settings.blockedPackages
                    else app.pkg in settings.allowedPackages
                }
                AppRow(app = app, mirrored = mirrored) { on ->
                    if (mode == FilterMode.ALLOW_ALL) {
                        settings.blockedPackages =
                            if (on) settings.blockedPackages - app.pkg else settings.blockedPackages + app.pkg
                    } else {
                        settings.allowedPackages =
                            if (on) settings.allowedPackages + app.pkg else settings.allowedPackages - app.pkg
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, mirrored: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, app.pkg) {
        value = AppCatalog.icon(context, app.pkg)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = mirrored, onCheckedChange = onToggle)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
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
        is LinkState.Waiting -> R.string.state_waiting
        is LinkState.Starting -> R.string.state_starting
        is LinkState.Failed -> if (pairing) R.string.state_mismatch else R.string.state_failed
        is LinkState.Stopped -> R.string.state_stopped
    }
)

private fun LinkState.detail(context: Context, kind: TransportKind): String {
    val transport = context.getString(
        if (kind == TransportKind.BLE) R.string.transport_bluetooth else R.string.transport_wifi
    )
    return when (this) {
        is LinkState.Connected -> context.getString(R.string.detail_connected, transport)
        is LinkState.Waiting -> context.getString(R.string.detail_waiting, transport)
        is LinkState.Failed ->
            if (pairing) context.getString(R.string.detail_mismatch)
            else context.getString(R.string.detail_failed, transport)

        else -> transport
    }
}

private fun formatUptime(since: Long): String {
    val elapsed = System.currentTimeMillis() - since
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun missingPermissions(context: Context): List<String> {
    val wanted = buildList {
        addAll(BlePermissions.forServer())
        addAll(BlePermissions.forClient())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.distinct()
    return BlePermissions.missing(context, wanted)
}

private fun HeadsetStatus.describe(context: Context): String {
    if (updatedAt == 0L) return context.getString(R.string.headset_unknown)
    return listOfNotNull(
        battery?.let { context.getString(R.string.headset_battery, it) },
        worn?.let {
            context.getString(if (it) R.string.headset_in_use else R.string.headset_charging)
        },
    ).joinToString(" · ").ifEmpty { context.getString(R.string.headset_unknown) }
}

private fun isIgnoringBattery(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData("package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(request) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun localAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()
        ?.hostAddress
}.getOrNull()
