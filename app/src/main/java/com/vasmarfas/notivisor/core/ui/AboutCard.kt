package com.vasmarfas.notivisor.core.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.util.BridgeLog

private const val REPO_URL = "https://github.com/vasmarfas/Notivisor"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val SUPPORT_URL = "$REPO_URL/blob/master/SUPPORT.md"
private const val PRIVACY_URL = "$REPO_URL/blob/master/privacy-policy.md"

@Composable
fun AboutCard() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    SectionCard(
        title = stringResource(R.string.section_about),
        subtitle = version?.let { stringResource(R.string.about_version, it) },
    ) {
        LinkRow(
            title = stringResource(R.string.about_github),
            detail = stringResource(R.string.about_github_hint),
        ) { open(context, REPO_URL) }
        LinkRow(
            title = stringResource(R.string.about_issues),
            detail = stringResource(R.string.about_issues_hint),
        ) { open(context, ISSUES_URL) }
        LinkRow(
            title = stringResource(R.string.about_support),
            detail = stringResource(R.string.about_support_hint),
        ) { open(context, SUPPORT_URL) }
        LinkRow(
            title = stringResource(R.string.about_privacy),
            detail = stringResource(R.string.about_privacy_hint),
        ) { open(context, PRIVACY_URL) }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "↗",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private fun open(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { BridgeLog.w("about", "no browser for $url: ${it.message}") }
}
