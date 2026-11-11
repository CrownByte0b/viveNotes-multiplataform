package com.vivenotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.theme.ViveNotesTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WavyLoadingIndicatorTest {
    @Test
    fun circularWavesStayHollowAndVisibleAtCompactSizesAcrossThemeChanges() = runDesktopComposeUiTest(800, 300) {
        var dark by mutableStateOf(false)
        var surface = Color.Unspecified
        var ink = Color.Unspecified
        val sizes = listOf(18, 24, 32, 48)
        mainClock.autoAdvance = false
        setContent {
            ViveNotesTheme(darkTheme = dark) {
                surface = MaterialTheme.colorScheme.surface
                ink = MaterialTheme.colorScheme.primary
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Surface {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (size in sizes) WavyLoadingIndicator(size = size.dp, modifier = Modifier.testTag("wave-$size"))
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(800)
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            mainClock.advanceTimeByFrame()
            for (size in sizes) {
                val indicator = onNodeWithTag("wave-$size").assertIsIndeterminateWavyRing(size.dp)
                val pixels = indicator.captureToImage().toPixelMap()
                assertTrue(matches(pixels[pixels.width / 2, pixels.height / 2], surface), "The $size dp wave has an open centre")
                assertTrue(pixels.contains(ink), "The $size dp wave paints the theme's accent")
            }
            save(onRoot(), "wavy-indicators-${if (theme) "dark" else "light"}.png")
        }
    }

    @Test
    fun buttonWavesUseTheContentColourAndOverlaysCarryTheirOwnBackground() = runDesktopComposeUiTest(500, 400) {
        var dark by mutableStateOf(false)
        var overlaySurface = Color.Unspecified
        var overlayInk = Color.Unspecified
        mainClock.autoAdvance = false
        setContent {
            ViveNotesTheme(darkTheme = dark) {
                overlaySurface = MaterialTheme.colorScheme.primaryContainer
                overlayInk = MaterialTheme.colorScheme.onPrimaryContainer
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                            WavyLoadingIndicator(
                                size = 18.dp,
                                color = LocalContentColor.current,
                                modifier = Modifier.padding(12.dp).testTag("button-wave"),
                            )
                        }
                        ContainedWavyLoadingIndicator(Modifier.testTag("overlay-wave"))
                    }
                }
            }
        }
        mainClock.advanceTimeBy(800)
        for (theme in listOf(false, true, false)) {
            runOnIdle { dark = theme }
            mainClock.advanceTimeByFrame()
            assertTrue(onNodeWithTag("button-wave").captureToImage().toPixelMap().contains(Color.White))
            val overlay = onNodeWithTag("overlay-wave").assertIsIndeterminateWavyRing(48.dp).captureToImage().toPixelMap()
            assertTrue(matches(overlay[overlay.width / 2, overlay.height / 2], overlaySurface))
            assertTrue(overlay.contains(overlayInk))
        }
    }

    private fun matches(actual: Color, expected: Color) = abs(actual.red - expected.red) < .02f &&
        abs(actual.green - expected.green) < .02f && abs(actual.blue - expected.blue) < .02f

    private fun PixelMap.contains(expected: Color) = (0 until height).any { y ->
        (0 until width).any { x -> matches(this[x, y], expected) }
    }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
