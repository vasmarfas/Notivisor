package com.vasmarfas.notivisor.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.link.PeerApp
import com.vasmarfas.notivisor.core.util.AppVersion

@Composable
fun VersionNotice(peer: PeerApp?, modifier: Modifier = Modifier) {
    if (peer == null) return
    val context = LocalContext.current
    val ours = remember { AppVersion.code(context) }
    if (peer.build == ours) return

    val ourName = remember { AppVersion.name(context) ?: "—" }
    val text = if (peer.version == null) {
        stringResource(R.string.version_older)
    } else {
        stringResource(R.string.version_mismatch, ourName, peer.version)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
