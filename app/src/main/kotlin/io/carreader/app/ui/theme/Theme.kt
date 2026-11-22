package io.carreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// High-contrast palette: readable in sunlight and at night in a car.
private val Light = lightColorScheme(
    primary = Color(0xFF00468C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF3A4A5C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3F7),
    onSecondaryContainer = Color(0xFF0F1D2B),
    error = Color(0xFFB00020),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFBFF),
    onBackground = Color(0xFF0D1117),
    surface = Color(0xFFFAFBFF),
    onSurface = Color(0xFF0D1117),
    surfaceVariant = Color(0xFFE0E4EC),
    onSurfaceVariant = Color(0xFF2B323C),
    outline = Color(0xFF5A6270),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FC8FF),
    onPrimary = Color(0xFF00284F),
    primaryContainer = Color(0xFF003A73),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFBCC8DA),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF2E3B4B),
    onSecondaryContainer = Color(0xFFDCE6F5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F3F7),
    surface = Color(0xFF0B0E12),
    onSurface = Color(0xFFF1F3F7),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFD0D6DF),
    outline = Color(0xFF8C95A3),
)

/** Status colors Material 3 does not define. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val bad: Color)

private val LightStatus = StatusColors(ok = Color(0xFF1B6E20), warn = Color(0xFF8A5300), bad = Color(0xFFB00020))
private val DarkStatus = StatusColors(ok = Color(0xFF7EE787), warn = Color(0xFFFFC266), bad = Color(0xFFFF8A80))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val base = Typography()

/** Typography one step larger than the Material defaults. */
private val CarTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontSize = 17.sp, lineHeight = 22.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
    bodySmall = base.bodySmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontSize = 15.sp),
)

val ValueTextStyle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

@Composable
fun CarReaderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = CarTypography, content = content)
    }
}
