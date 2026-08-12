package com.vasmarfas.notivisor.headset.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.protocol.Pairing
import com.vasmarfas.notivisor.core.protocol.PairingPayload
import com.vasmarfas.notivisor.core.transport.LinkState
import com.vasmarfas.notivisor.core.transport.TransportConfig
import com.vasmarfas.notivisor.core.transport.TransportKind
import com.vasmarfas.notivisor.core.transport.ble.BlePermissions
import com.vasmarfas.notivisor.core.ui.HealthTone
import com.vasmarfas.notivisor.core.ui.LogPanel
import com.vasmarfas.notivisor.core.ui.SectionCard
import com.vasmarfas.notivisor.core.ui.SegmentedChoice
import com.vasmarfas.notivisor.core.ui.SettingRow
import com.vasmarfas.notivisor.core.ui.StatRow
import com.vasmarfas.notivisor.core.ui.StatusBanner
import com.vasmarfas.notivisor.core.util.BridgeLog
import com.vasmarfas.notivisor.headset.core.HeadsetBridge

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

    var scanning by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    var scanOutcome by remember { mutableStateOf<Int?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var hostInput by remember { mutableStateOf(settings.tcpHost.orEmpty()) }
    var portInput by remember { mutableStateOf(settings.tcpPort.toString()) }

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
        QrScannerScreen(
            onResult = { raw ->
                val payload = PairingPayload.parse(raw)
                if (payload == null) {
                    scanOutcome = R.string.pairing_rejected
                    BridgeLog.w(SCOPE, "scanned code is not a pairing payload")
                } else {
                    settings.apply(payload)
                    hostInput = settings.tcpHost.orEmpty()
                    portInput = settings.tcpPort.toString()
                    scanOutcome = R.string.pairing_applied
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
                    activity?.let { com.vasmarfas.notivisor.AppRole.quit(it) }
                }) { Text(stringResource(R.string.quit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuit = false }) {
                    Text(stringResource(R.string.quit_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(20.dp)) }

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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val missing = BlePermissions.missing(context, HeadsetCamera.permissions)
                        if (HeadsetCamera.hasBasicPermission(context)) {
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
                title = stringResource(R.string.section_pairing),
                subtitle = stringResource(
                    if (settings.pairingCode != null) R.string.pairing_done else R.string.pairing_none
                ),
            ) {
                scanOutcome?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it == R.string.pairing_applied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
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

        item {
            SectionCard(
                title = stringResource(R.string.section_notifications),
                subtitle = stringResource(R.string.section_notifications_hint_headset),
            ) {
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
            }
        }

        item { com.vasmarfas.notivisor.RoleCard() }

        item {
            TextButton(onClick = { confirmQuit = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_quit), color = MaterialTheme.colorScheme.error)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
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
        is LinkState.Waiting -> R.string.state_waiting_headset
        is LinkState.Starting -> R.string.state_starting
        is LinkState.Failed -> if (pairing) R.string.state_mismatch else R.string.state_failed
        is LinkState.Stopped -> R.string.state_stopped_headset
    }
)

private const val SCOPE = "ui"
