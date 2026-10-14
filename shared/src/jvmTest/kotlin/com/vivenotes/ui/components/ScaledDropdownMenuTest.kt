package com.vivenotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ScaledDropdownMenuTest {
    @Test
    fun theMenuOpensAtItsFinalSizeAndOpacityWithoutASecondVisualTransition() =
        assertOpeningPaintIsStable(1f)

    @Test
    fun theMenuPaintIsStableAtTheDefaultDisplayScale() = assertOpeningPaintIsStable(0.75f)

    @Test
    fun theMenuPaintIsStableAtTheMinimumDisplayScale() = assertOpeningPaintIsStable(0.5f)

    private fun assertOpeningPaintIsStable(displayScale: Float) =
        runDesktopComposeUiTest(width = 600, height = 400) {
            setContent {
                ViveNotesTheme(darkTheme = true) {
                    val hostDensity = LocalDensity.current
                    CompositionLocalProvider(
                        LocalPopupLayerDensity provides hostDensity,
                        LocalDensity provides Density(hostDensity.density * displayScale, hostDensity.fontScale),
                    ) {
                        var open by remember { mutableStateOf(false) }
                        Box(Modifier.padding(60.dp)) {
                            TextButton(onClick = { open = true }, modifier = Modifier.testTag("open")) {
                                Text("Open")
                            }
                            ScaledDropdownMenu(open, { open = false }) {
                                Box(Modifier.size(160.dp, 100.dp).background(Color.Red).testTag("menu"))
                            }
                        }
                    }
                }
            }
            mainClock.autoAdvance = false
            onNodeWithTag("open").performClick()
            mainClock.advanceTimeBy(64)
            val opening = onNodeWithTag("menu").captureToImage().toPixelMap()
            mainClock.advanceTimeBy(600)
            val settled = onNodeWithTag("menu").captureToImage().toPixelMap()
            assertEquals(Color.Red, opening[opening.width / 2, opening.height / 2])
            assertEquals(settled.width, opening.width)
            assertEquals(settled.height, opening.height)
            var changedPixels = 0
            for (y in 0 until opening.height) for (x in 0 until opening.width) {
                if (opening[x, y] != settled[x, y]) changedPixels++
            }
            assertEquals(0, changedPixels, "The menu changed size or opacity after opening")
        }
}
