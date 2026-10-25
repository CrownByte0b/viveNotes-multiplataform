package com.vivenotes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The dark look is the Android app's: its scheme, and its surfaces under the desktop's names. */
@OptIn(ExperimentalTestApi::class)
class ThemeTest {
    @Test
    fun darkSchemeIsTheAndroidAppsDarkScheme() {
        val (scheme, _) = darkTheme()
        // Android's ui/theme/Theme.kt DarkColors, every slot it names.
        val android = mapOf(
            "primary" to Color(0xFF007FFF),
            "onPrimary" to Color.White,
            "primaryContainer" to Color(0xFF00478E),
            "onPrimaryContainer" to Color(0xFFB3D9FF),
            "secondary" to Color(0xFF0063C6),
            "tertiary" to Color(0xFFFF8000),
            "onTertiary" to Color(0xFF1F1F1F),
            "tertiaryContainer" to Color(0xFF7A3D00),
            "onTertiaryContainer" to Color(0xFFFFD9B3),
            "background" to Color(0xFF1F1F1F),
            "onBackground" to Color(0xFFE6E6E6),
            "surface" to Color(0xFF1E2024),
            "onSurface" to Color(0xFFE6E6E6),
            "surfaceVariant" to Color(0xFF333333),
            "onSurfaceVariant" to Color(0xFFB8B8B8),
            "surfaceContainer" to Color(0xFF18191C),
            "surfaceContainerHigh" to Color(0xFF1E2024),
            "surfaceContainerHighest" to Color(0xFF222930),
            "outline" to Color(0xFF4A4A4A),
            "outlineVariant" to Color(0xFF3A3A3A),
        )
        val desktop = mapOf(
            "primary" to scheme.primary,
            "onPrimary" to scheme.onPrimary,
            "primaryContainer" to scheme.primaryContainer,
            "onPrimaryContainer" to scheme.onPrimaryContainer,
            "secondary" to scheme.secondary,
            "tertiary" to scheme.tertiary,
            "onTertiary" to scheme.onTertiary,
            "tertiaryContainer" to scheme.tertiaryContainer,
            "onTertiaryContainer" to scheme.onTertiaryContainer,
            "background" to scheme.background,
            "onBackground" to scheme.onBackground,
            "surface" to scheme.surface,
            "onSurface" to scheme.onSurface,
            "surfaceVariant" to scheme.surfaceVariant,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "outline" to scheme.outline,
            "outlineVariant" to scheme.outlineVariant,
        )
        assertEquals(android, desktop)
    }

    @Test
    fun darkDesktopSurfacesTakeTheAndroidSlotsThatPaintThem() {
        val (scheme, chrome) = darkTheme()
        assertEquals(scheme.surface, chrome.headerBar)
        assertEquals(scheme.surface, chrome.toolbar)
        assertEquals(scheme.surfaceContainer, chrome.sidebar)
        assertEquals(scheme.surfaceContainerHigh, chrome.dialog)
        assertEquals(scheme.surfaceContainerHighest, chrome.selection)
        assertEquals(Color(0xFF73DD83), chrome.success)
        assertEquals(chrome.headerBar, headerBarColor(darkTheme = true))
    }

    /** Tonal buttons stay neutral, as Android repaints its own, and show on a boxed group. */
    @Test
    fun darkTonalContainerIsNeutralAndDistinctFromBoxedGroups() {
        val (scheme, _) = darkTheme()
        assertEquals(scheme.surfaceVariant, scheme.secondaryContainer)
        assertEquals(scheme.onSurface, scheme.onSecondaryContainer)
        assertNotEquals(scheme.surfaceContainerHighest, scheme.secondaryContainer)
    }

    private fun darkTheme(): Pair<ColorScheme, DesktopColors> {
        lateinit var scheme: ColorScheme
        lateinit var chrome: DesktopColors
        runDesktopComposeUiTest {
            setContent {
                ViveNotesTheme(darkTheme = true) {
                    scheme = MaterialTheme.colorScheme
                    chrome = LocalDesktopColors.current
                }
            }
            waitForIdle()
        }
        return scheme to chrome
    }
}
