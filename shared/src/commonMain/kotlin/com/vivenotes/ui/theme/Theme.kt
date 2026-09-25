package com.vivenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5AA7FF),
    onPrimary = Color(0xFF002E55),
    primaryContainer = Color(0xFF004A84),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFF9ECAFF),
    tertiary = Color(0xFFFFB86A),
    onTertiary = Color(0xFF472A00),
    background = Color(0xFF17191C),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF202226),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF2A2D32),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainer = Color(0xFF1D1F23),
    surfaceContainerHigh = Color(0xFF282B30),
    surfaceContainerHighest = Color(0xFF34373D),
    outline = Color(0xFF51555D),
    outlineVariant = Color(0xFF383C43),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0063C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF355F8E),
    tertiary = Color(0xFF8C4F00),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = Color(0xFF454950),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFE9ECF1),
    surfaceContainerHighest = Color(0xFFE1E4EA),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC5C8CF),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

/** Material 3 Expressive root, carrying the Android app's azure identity onto desktop. */
@Composable
fun ViveNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        content = content,
    )
}
