package com.vivenotes.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        toolbar = Color(0xFF292A2F),
        sidebar = Color(0xFF2E2E32),
    )

    @Test
    fun lightDesktopChromeUsesAWhiteHeaderAndGreySidebar() = assertChrome(
        dark = false,
        header = Color.White,
        toolbar = Color(0xFF292A2F),
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
            assertTrue(abs(ribbonHeight - 46f) < 1f, "toolbar height: $ribbonHeight")

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

    @Test
    fun everyRibbonTabUsesTheSameCompactDarkStrip() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent {
                var workspace by remember { mutableStateOf(WorkspaceState.demo()) }
                ViveNotesTheme(darkTheme = false) {
                    WorkspaceScreen(workspace, onStateChange = { workspace = it(workspace) },
                        interfaceSettings = InterfaceSettings(displayScale = 1f))
                }
            }
            RibbonTab.entries.forEach { tab ->
                onNodeWithTag(WorkspaceTestTags.ribbonTab(tab)).performClick()
                val ribbon = onNodeWithTag(WorkspaceTestTags.RibbonBar)
                val bounds = ribbon.getUnclippedBoundsInRoot()
                assertTrue(abs((bounds.bottom - bounds.top).value - 46f) < 1f, "$tab strip height")
                val pixels = ribbon.captureToImage().toPixelMap()
                assertPaintedColor(pixels[pixels.width - 8, 8], Color(0xFF292A2F))
                assertPaintedColor(pixels[pixels.width - 8, pixels.height - 1], Color(0xFF393B42))
            }
            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).performClick()
            val pointer = onNodeWithTag(DrawRibbonTags.PointerTool).assertIsSelected()
                .captureToImage().toPixelMap()
            assertPaintedColor(pointer[pointer.width / 2, pointer.height - 2], Color(0xFF007FFF))

            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).performClick()
            val textTool = onNodeWithTag(DocumentRibbonTags.Text).performClick().assertIsSelected()
                .captureToImage().toPixelMap()
            assertPaintedColor(textTool[textTool.width / 2, textTool.height - 2], Color(0xFF007FFF))

            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.View)).performClick()
            val switch = onNodeWithTag(ViewRibbonTags.SwitchBackground)
            switch.performMouseInput { moveTo(center) }
            waitForIdle()
            val hovered = switch.captureToImage().toPixelMap()
            assertPaintedColor(hovered[4, 4], Color(0xFF32343A))
        }

    private fun assertPaintedColor(actual: Color, expected: Color) {
        assertTrue(abs(actual.red - expected.red) < 0.03f &&
            abs(actual.green - expected.green) < 0.03f &&
            abs(actual.blue - expected.blue) < 0.03f,
            "expected $expected, painted $actual")
    }
}
