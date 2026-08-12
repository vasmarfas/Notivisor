package com.vasmarfas.notivisor.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A47C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF0C0A5C),
    secondary = Color(0xFF00707F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFAFECFA),
    onSecondaryContainer = Color(0xFF00343C),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFFF1EDF6),
    surfaceContainerHigh = Color(0xFFEBE7F1),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC8C5D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF1B1794),
    primaryContainer = Color(0xFF322EAC),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = Color(0xFF55D6EC),
    onSecondary = Color(0xFF003842),
    secondaryContainer = Color(0xFF005965),
    onSecondaryContainer = Color(0xFFAFECFA),
    tertiary = Color(0xFFF5BE48),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    surfaceContainer = Color(0xFF1E1E25),
    surfaceContainerHigh = Color(0xFF292930),
    outline = Color(0xFF928F99),
    outlineVariant = Color(0xFF47464F),
)

data class StatusColors(
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
)

private val LightStatus = StatusColors(
    positive = Color(0xFF1D7A45),
    onPositive = Color.White,
    positiveContainer = Color(0xFFB6F2CC),
    onPositiveContainer = Color(0xFF00210F),
    neutral = Color(0xFF6B6975),
    neutralContainer = Color(0xFFE7E4EE),
)

private val DarkStatus = StatusColors(
    positive = Color(0xFF7EDCA4),
    onPositive = Color(0xFF00391D),
    positiveContainer = Color(0xFF0B5730),
    onPositiveContainer = Color(0xFFB6F2CC),
    neutral = Color(0xFF9B98A5),
    neutralContainer = Color(0xFF2B2B33),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun NotivisorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

object AppTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
