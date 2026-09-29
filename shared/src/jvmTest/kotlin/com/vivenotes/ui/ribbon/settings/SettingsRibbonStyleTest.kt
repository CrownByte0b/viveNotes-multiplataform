package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsRibbonStyleTest {
    @Test
    fun settingsCommandsUseCompactStripAndUnderlineActiveControls() =
        runDesktopComposeUiTest(width = 900, height = 300) {
            setContent {
                var hardwareOpen by remember { mutableStateOf(false) }
                var linkPreviews by remember { mutableStateOf(false) }
                ViveNotesTheme {
                    SettingsRibbon(onInterface = {}, hardwareOpen = hardwareOpen,
                        onHardware = { hardwareOpen = !hardwareOpen }, linkPreviews = linkPreviews,
                        onLinkPreviewsChange = { linkPreviews = it }, onAbout = {})
                }
            }

            val strip = onNodeWithTag(WorkspaceTestTags.RibbonBar)
            val bounds = strip.getUnclippedBoundsInRoot()
            assertTrue(abs((bounds.bottom - bounds.top).value - 46f) < 1f)
            val pixels = strip.captureToImage().toPixelMap()
            assertColor(pixels[pixels.width - 8, 8], Color(0xFF292A2F))
            assertColor(pixels[pixels.width - 8, pixels.height - 1], Color(0xFF393B42))

            onNodeWithTag("settings-appearance").assertDoesNotExist()
            onNodeWithTag("settings-models").assertIsNotEnabled()
            for (label in listOf("interface", "link-previews", "hardware", "models", "about")) {
                onNodeWithTag("settings-icon-$label", useUnmergedTree = true).assertIsDisplayed()
            }
            assertIconContains("interface", Color(0xFFE5E7ED))
            assertIconContains("hardware", Color(0xFF5DAFFF))
            assertIconContains("models", Color(0xFFA8ABB4))
            onNodeWithTag(HardwareTags.Open).assertIsNotSelected().performClick().assertIsSelected()
            val active = onNodeWithTag(HardwareTags.Open).captureToImage().toPixelMap()
            assertColor(active[active.width / 2, active.height - 2], Color(0xFF007FFF))

            onNodeWithTag(LINK_PREVIEWS_TAG).performClick().assertIsSelected()
            onNodeWithTag(LINK_PREVIEWS_TAG).performClick().assertIsNotSelected()
            onNodeWithTag(HardwareTags.Open).performClick().assertIsNotSelected()
        }

    @Test
    fun hoveringASettingsCommandPaintsItsTabBackground() =
        runDesktopComposeUiTest(width = 900, height = 300) {
            setContent {
                ViveNotesTheme {
                    SettingsRibbon(onInterface = {}, hardwareOpen = false, onHardware = {},
                        linkPreviews = false, onLinkPreviewsChange = {}, onAbout = {})
                }
            }

            val tab = onNodeWithTag(InterfaceTags.Open)
            tab.performMouseInput { moveTo(center) }
            waitForIdle()
            val pixels = tab.captureToImage().toPixelMap()
            assertColor(pixels[4, 4], Color(0xFF32343A))
        }

    private fun assertColor(actual: Color, expected: Color) {
        assertTrue(abs(actual.red - expected.red) < 0.03f &&
            abs(actual.green - expected.green) < 0.03f &&
            abs(actual.blue - expected.blue) < 0.03f,
            "expected $expected, painted $actual")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertIconContains(label: String, expected: Color) {
        val pixels = onNodeWithTag("settings-icon-$label", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { x ->
            (0 until pixels.height).any { y ->
                val pixel = pixels[x, y]
                abs(pixel.red - expected.red) < 0.03f &&
                    abs(pixel.green - expected.green) < 0.03f &&
                    abs(pixel.blue - expected.blue) < 0.03f
            }
        }, "$label icon should paint $expected")
    }
}
