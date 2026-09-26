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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3584E4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3C4654),
    onPrimaryContainer = Color(0xFFE8F2FF),
    secondary = Color(0xFFC5C5CB),
    onSecondary = Color(0xFF222226),
    secondaryContainer = Color(0xFF3B3B40),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF81D0FF),
    onTertiary = Color(0xFF1D1D20),
    background = Color(0xFF222226),
    onBackground = Color.White,
    surface = Color(0xFF1D1D20),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF343438),
    onSurfaceVariant = Color(0xFFC2C2C8),
    surfaceContainer = Color(0xFF2E2E32),
    surfaceContainerHigh = Color(0xFF38383D),
    surfaceContainerHighest = Color(0xFF45454A),
    outline = Color(0xFF77777E),
    outlineVariant = Color(0xFF47474D),
    error = Color(0xFFFF938C),
    onError = Color(0xFF222226),
    errorContainer = Color(0xFF7D2028),
    onErrorContainer = Color.White,
)

internal data class DesktopColors(
    val headerBar: Color,
    val sidebar: Color,
    val toolbar: Color,
    val dialog: Color,
    val selection: Color,
    val destructive: Color,
)

private val DarkDesktopColors = DesktopColors(
    headerBar = Color(0xFF2E2E32),
    sidebar = Color(0xFF2E2E32),
    toolbar = Color(0xFF38383D),
    dialog = Color(0xFF2E2E32),
    selection = Color(0xFF45454A),
    destructive = Color(0xFFC01C28),
)

private val LightDesktopColors = DesktopColors(
    headerBar = Color.White,
    sidebar = Color(0xFFEBEBED),
    toolbar = Color(0xFFF3F3F5),
    dialog = Color.White,
    selection = Color(0xFFDCDCE0),
    destructive = Color(0xFFE01B24),
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
    CompositionLocalProvider(LocalDesktopColors provides if (darkTheme) DarkDesktopColors else LightDesktopColors) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            motionScheme = MotionScheme.standard(),
            shapes = DesktopShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
