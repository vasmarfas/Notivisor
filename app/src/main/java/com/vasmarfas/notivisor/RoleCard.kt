package com.vasmarfas.notivisor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.notivisor.core.settings.BridgeSettings
import com.vasmarfas.notivisor.core.settings.DeviceRole
import com.vasmarfas.notivisor.core.settings.RoleDetector
import com.vasmarfas.notivisor.core.ui.SectionCard
import com.vasmarfas.notivisor.core.ui.SegmentedChoice

@Composable
fun RoleCard() {
    val context = LocalContext.current
    val settings = BridgeSettings.get(context)
    val detected = remember { RoleDetector.detectWithReason(context) }
    var confirming by remember { mutableStateOf<DeviceRole?>(null) }

    val override = settings.roleOverride
    val active = override ?: detected.first

    SectionCard(
        title = stringResource(R.string.section_role),
        subtitle = if (override == null) {
            stringResource(R.string.role_auto, detected.second)
        } else {
            stringResource(R.string.role_manual)
        },
    ) {
        SegmentedChoice(
            options = listOf(DeviceRole.PHONE, DeviceRole.HEADSET),
            selected = active,
            label = {
                stringResource(if (it == DeviceRole.PHONE) R.string.role_phone else R.string.role_headset)
            },
            onSelect = { picked -> if (picked != active) confirming = picked },
        )
        Text(
            stringResource(R.string.role_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirming?.let { picked ->
        RoleSwitchDialog(
            target = picked,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                AppRole.override(context, picked.takeIf { it != detected.first })
                AppRole.start(context)
            },
        )
    }
}

@Composable
private fun RoleSwitchDialog(
    target: DeviceRole,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.role_switch_title)) },
        text = {
            Text(
                stringResource(
                    R.string.role_switch_message,
                    stringResource(
                        if (target == DeviceRole.PHONE) R.string.role_phone else R.string.role_headset
                    ),
                )
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.role_switch_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.quit_cancel))
            }
        },
    )
}
