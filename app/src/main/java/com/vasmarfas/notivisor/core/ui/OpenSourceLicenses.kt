package com.vasmarfas.notivisor.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.notivisor.R
import com.vasmarfas.notivisor.core.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class License(val title: String, val texts: List<Int>) {
    APACHE("Apache License 2.0", listOf(R.raw.license_apache_2_0)),
    MIT_SHIZUKU("MIT License", listOf(R.raw.license_mit_shizuku)),
    MIT_BOUNCY_CASTLE("MIT License", listOf(R.raw.license_mit_bouncy_castle)),
    MIT_CHECKER("MIT License", listOf(R.raw.license_mit_checker_qual)),
    GPL_CLASSPATH(
        "GNU GPL 2.0 with the Classpath Exception",
        listOf(R.raw.license_gpl_2_0, R.raw.license_classpath_exception),
    ),
    LGPL("GNU LGPL 3.0", listOf(R.raw.license_lgpl_3_0, R.raw.license_gpl_3_0)),
}

private class Component(
    val name: String,
    val holder: String,
    val license: License,
    val source: String,
    @param:StringRes val note: Int? = null,
)

private val COMPONENTS = listOf(
    Component("scrcpy server", "Romain Vimont, Genymobile", License.APACHE, "github.com/Genymobile/scrcpy"),
    Component(
        "AndroidX, Jetpack Compose, CameraX, Media3",
        "The Android Open Source Project",
        License.APACHE,
        "android.googlesource.com/platform/frameworks/support",
    ),
    Component("Kotlin, kotlinx.coroutines", "JetBrains s.r.o.", License.APACHE, "github.com/JetBrains/kotlin"),
    Component("ZXing", "ZXing authors", License.APACHE, "github.com/zxing/zxing"),
    Component("dadb", "mobile.dev", License.APACHE, "github.com/mobile-dev-inc/dadb"),
    Component("Okio", "Square, Inc.", License.APACHE, "github.com/square/okio"),
    Component("Conscrypt", "The Android Open Source Project", License.APACHE, "github.com/google/conscrypt"),
    Component(
        "Guava, Dagger, AutoValue, Error Prone, J2ObjC and JSR 305 annotations, JSpecify, javax.inject",
        "Google LLC and contributors",
        License.APACHE,
        "github.com/google/guava",
    ),
    Component(
        "libadb-android",
        "Muntashir Al-Islam",
        License.APACHE,
        "github.com/MuntashirAkon/libadb-android",
        R.string.license_dual_note,
    ),
    Component("Shizuku API", "RikkaW", License.MIT_SHIZUKU, "github.com/RikkaApps/Shizuku-API"),
    Component(
        "Bouncy Castle",
        "The Legion of the Bouncy Castle Inc.",
        License.MIT_BOUNCY_CASTLE,
        "github.com/bcgit/bc-java",
    ),
    Component(
        "Checker Framework qualifiers",
        "Checker Framework developers",
        License.MIT_CHECKER,
        "github.com/typetools/checker-framework",
    ),
    Component(
        "sun-security-android",
        "Muntashir Al-Islam, Oracle and/or its affiliates",
        License.GPL_CLASSPATH,
        "github.com/MuntashirAkon/sun-security-android",
    ),
    Component(
        "spake2-java",
        "Muntashir Al-Islam",
        License.LGPL,
        "github.com/MuntashirAkon/spake2-java",
        R.string.license_lgpl_note,
    ),
)

@Composable
fun OpenSourceLicenses(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<License?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        title = { Text(stringResource(R.string.about_licenses)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        stringResource(R.string.licenses_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(COMPONENTS) { component -> ComponentRow(component) { reading = component.license } }
            }
        },
    )

    reading?.let { license -> LicenseText(license) { reading = null } }
}

@Composable
private fun ComponentRow(component: Component, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        onClick = onOpen,
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(component.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "© ${component.holder} · ${component.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                component.note?.let { "${component.license.title}. ${stringResource(it)}" } ?: component.license.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LicenseText(license: License, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val text by produceState("", license) {
        value = withContext(Dispatchers.IO) {
            license.texts.joinToString("\n\n") { id ->
                resources.openRawResource(id).bufferedReader().use { it.readText() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        title = { Text(license.title) },
        text = {
            SelectionContainer {
                Text(text, style = MonoStyle, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
    )
}
