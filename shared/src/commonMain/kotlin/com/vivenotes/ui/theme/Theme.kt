package com.vivenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.ui.ribbon.DarkRibbonColors
import com.vivenotes.ui.ribbon.LightRibbonColors
import com.vivenotes.ui.ribbon.LocalRibbonColors

// For a normal hex color (#RRGGBB), replace # with 0xFF and wrap it in Color(...)

/** The Android app's dark scheme, slot for slot; what it leaves to Material is left here too. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF007FFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00478E),
    onPrimaryContainer = Color(0xFFB3D9FF),
    secondary = Color(0xFF0063C6),
    // Android leaves these two to Material's violet and repaints each tonal button neutral. Its
    // surfaceVariant grey does that here, and stays visible on a boxed group (surfaceContainerHighest).
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFFFF8000),
    onTertiary = Color(0xFF1F1F1F),
    tertiaryContainer = Color(0xFF7A3D00),
    onTertiaryContainer = Color(0xFFFFD9B3),
    background = Color(0xFF1F1F1F),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1E2024),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainer = Color(0xFF18191C),
    surfaceContainerHigh = Color(0xFF1E2024),
    surfaceContainerHighest = Color(0xFF222930),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF3A3A3A),
)

internal data class DesktopColors(
    val headerBar: Color,
    val sidebar: Color,
    val toolbar: Color,
    val dialog: Color,
    val selection: Color,
    val destructive: Color,
    /** Libadwaita's success colour: "working", where the Android app uses its green icon accent. */
    val success: Color,
)

/** Each desktop surface takes the Android scheme slot that paints the same surface there. */
private val DarkDesktopColors = DesktopColors(
    headerBar = DarkColors.surface, // the ribbon's tab strip
    sidebar = DarkColors.surfaceContainer, // the notebook rail
    toolbar = DarkColors.surface, // the ribbon
    dialog = DarkColors.surfaceContainerHigh, // AlertDialog's default container
    selection = DarkColors.surfaceContainerHighest, // the open notebook, section or page row
    destructive = Color(0xFFC01C28),
    success = Color(0xFF73DD83), // IconAccents.green, the account's connected dot
)

private val LightDesktopColors = DesktopColors(
    headerBar = Color.White,
    sidebar = Color(0xFFEBEBED),
    toolbar = Color(0xFFF3F3F5),
    dialog = Color.White,
    selection = Color(0xFFDCDCE0),
    destructive = Color(0xFFE01B24),
    success = Color(0xFF1B8553),
)

internal val LocalDesktopColors = staticCompositionLocalOf { DarkDesktopColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3584E4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EDF8),
    onPrimaryContainer = Color(0xFF1F3855),
    secondary = Color(0xFF55555D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E7),
    onSecondaryContainer = Color(0xFF222226),
    tertiary = Color(0xFF0461BE),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF242428),
    surface = Color.White,
    onSurface = Color(0xFF242428),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF55555D),
    surfaceContainer = Color(0xFFEBEBED),
    surfaceContainerHigh = Color(0xFFF3F3F5),
    surfaceContainerHighest = Color(0xFFE1E1E4),
    outline = Color(0xFF8B8B91),
    outlineVariant = Color(0xFFD4D4D8),
    error = Color(0xFFC30000),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF4A0000),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(15.dp),
)

/** Compose theme with GNOME Adwaita colors and compact desktop shapes. */
@Composable
fun ViveNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDesktopColors provides if (darkTheme) DarkDesktopColors else LightDesktopColors,
        LocalRibbonColors provides if (darkTheme) DarkRibbonColors else LightRibbonColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            motionScheme = MotionScheme.standard(),
            shapes = DesktopShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

/** The header bar's colour in the dark or light look, for a window frame drawn to match it. */
fun headerBarColor(darkTheme: Boolean): Color =
    (if (darkTheme) DarkDesktopColors else LightDesktopColors).headerBar

/** Tells [onChange] the look the app shows, at first and whenever it changes. */
@Composable
internal fun ReportDarkTheme(darkTheme: Boolean, onChange: (Boolean) -> Unit) {
    val report by rememberUpdatedState(onChange)
    LaunchedEffect(darkTheme) { report(darkTheme) }
}
