package com.vasmarfas.notivisor.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.notivisor.core.ui.theme.AppTheme
import com.vasmarfas.notivisor.core.ui.theme.MonoStyle

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

enum class HealthTone { Positive, Working, Bad, Idle }

@Composable
fun StatusBanner(
    tone: HealthTone,
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    facts: List<Pair<String, String>> = emptyList(),
) {
    val status = AppTheme.status
    val container = when (tone) {
        HealthTone.Positive -> status.positiveContainer
        HealthTone.Working -> MaterialTheme.colorScheme.secondaryContainer
        HealthTone.Bad -> MaterialTheme.colorScheme.errorContainer
        HealthTone.Idle -> status.neutralContainer
    }
    val onContainer = when (tone) {
        HealthTone.Positive -> status.onPositiveContainer
        HealthTone.Working -> MaterialTheme.colorScheme.onSecondaryContainer
        HealthTone.Bad -> MaterialTheme.colorScheme.onErrorContainer
        HealthTone.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val dot = when (tone) {
        HealthTone.Positive -> status.positive
        HealthTone.Working -> MaterialTheme.colorScheme.secondary
        HealthTone.Bad -> MaterialTheme.colorScheme.error
        HealthTone.Idle -> status.neutral
    }
    val animated by animateColorAsState(container, label = "banner")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = animated,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(dot)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(headline, style = MaterialTheme.typography.titleLarge, color = onContainer)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.75f),
                    )
                }
            }
            if (facts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    facts.forEach { (label, value) -> Fact(label, value, onContainer) }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, tint: Color) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = tint,
            maxLines = 1,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

@Composable
fun StatRow(stats: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        stats.forEach { (label, value) ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, softWrap = false)
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun ChecklistRow(
    done: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onAction: () -> Unit,
) {
    val status = AppTheme.status
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        onClick = onAction,
        enabled = !done,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (done) status.positiveContainer else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (done) "✓" else "!",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (done) status.onPositiveContainer else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!done) {
                Text(
                    "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    onClick = { onSelect(option) },
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogPanel(lines: List<String>, modifier: Modifier = Modifier, maxHeight: Int = 220) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = lines.takeLast(60).joinToString("\n").ifEmpty { "—" },
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = maxHeight.dp)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(14.dp)),
        )
    }
}
