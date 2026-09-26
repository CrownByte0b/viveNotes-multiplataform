package com.vivenotes.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The desktop chrome's dimensions and Adwaita surfaces, in both supported color schemes. */
@OptIn(ExperimentalTestApi::class)
class DesktopChromeTest {
    @Test
    fun darkDesktopChromeHasCompactBarsAndNeutralSidebar() = assertChrome(
        dark = true,
        header = Color(0xFF2E2E32),
        toolbar = Color(0xFF38383D),
        sidebar = Color(0xFF2E2E32),
    )

    @Test
    fun lightDesktopChromeUsesAWhiteHeaderAndGreySidebar() = assertChrome(
        dark = false,
        header = Color.White,
        toolbar = Color(0xFFF3F3F5),
        sidebar = Color(0xFFEBEBED),
    )

    private fun assertChrome(dark: Boolean, header: Color, toolbar: Color, sidebar: Color) =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent {
                ViveNotesTheme(darkTheme = dark) {
                    WorkspaceScreen(WorkspaceState.demo(), onStateChange = {},
                        interfaceSettings = InterfaceSettings(displayScale = 1f))
                }
            }

            val headerNode = onNodeWithTag(WorkspaceTestTags.HeaderBar)
            val ribbonNode = onNodeWithTag(WorkspaceTestTags.RibbonBar)
            val headerBounds = headerNode.getUnclippedBoundsInRoot()
            val ribbonBounds = ribbonNode.getUnclippedBoundsInRoot()
            val headerHeight = (headerBounds.bottom - headerBounds.top).value
            val ribbonHeight = (ribbonBounds.bottom - ribbonBounds.top).value
            assertTrue(abs(headerHeight - 50f) < 1f, "header height: $headerHeight")
            assertTrue(abs(ribbonHeight - 56f) < 1f, "toolbar height: $ribbonHeight")

            headerNode.captureToImage().toPixelMap().let { pixels ->
                assertPaintedColor(pixels[pixels.width - 8, 8], header)
            }
            ribbonNode.captureToImage().toPixelMap().let { pixels ->
                assertPaintedColor(pixels[pixels.width - 8, 8], toolbar)
            }
            onNodeWithTag(WorkspaceTestTags.NotebookPane).captureToImage().toPixelMap().let { pixels ->
                assertPaintedColor(pixels[pixels.width - 8, pixels.height / 2], sidebar)
            }
        }

    private fun assertPaintedColor(actual: Color, expected: Color) {
        assertTrue(abs(actual.red - expected.red) < 0.03f &&
            abs(actual.green - expected.green) < 0.03f &&
            abs(actual.blue - expected.blue) < 0.03f,
            "expected $expected, painted $actual")
    }
}
