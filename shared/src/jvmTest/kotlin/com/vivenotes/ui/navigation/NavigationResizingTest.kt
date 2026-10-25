package com.vivenotes.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.ribbon.settings.HardwareTags
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.RibbonTab
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

@OptIn(ExperimentalTestApi::class)
class NavigationResizingTest {
    @Test
    fun aResponsiveHiddenNotebookPanelUsesTheClosedIndicator() = runDesktopComposeUiTest(width = 700, height = 900) {
        setWorkspace()
        onNodeWithTag(WorkspaceTestTags.NavigationToggle).assertIsNotSelected()
        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
    }

    @Test
    fun menuOpenChangesToMenuWhenBothPanelsCloseAndReturnsOnReopen() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace()
            val toggle = onNodeWithTag(WorkspaceTestTags.NavigationToggle)
            toggle.assertIsSelected()
            val openIcon = iconPixels()
            toggle.performClick().assertIsNotSelected()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            assertTrue(openIcon != iconPixels(), "open and closed must paint different symbols")
            toggle.performClick().assertIsSelected()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
            assertEquals(openIcon, iconPixels())
        }

    @Test
    fun mouseResizesEachPanelLiveAndReopeningRetainsItsWidth() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace { observed = it }
            for ((divider, pane) in listOf(
                WorkspaceTestTags.NotebookDivider to WorkspaceTestTags.NotebookPane,
                WorkspaceTestTags.PageDivider to WorkspaceTestTags.PagePane,
            )) {
                val before = bounds(pane).width
                onNodeWithTag(divider).performMouseInput {
                    moveTo(center); press(); repeat(10) { moveBy(Offset(12f, 0f)) }
                }
                assertTrue(bounds(pane).width > before + 110f, "panel must resize while pressed")
                onNodeWithTag(divider).performMouseInput { release(); exit() }
                val panePixels = onNodeWithTag(pane).captureToImage().toPixelMap()
                val dividerPixels = onNodeWithTag(divider).captureToImage().toPixelMap()
                val paneBackground = panePixels[panePixels.width / 2, panePixels.height / 2]
                for (x in 0 until dividerPixels.width) {
                    assertEquals(paneBackground, dividerPixels[x, dividerPixels.height / 2],
                        "the resize area must blend into the pane without a contrasting edge")
                }
            }
            runOnIdle {
                assertTrue(observed.notebookPaneWidth > 342f)
                assertTrue(observed.pagePaneWidth > 370f)
            }
            val notebookWidth = bounds(WorkspaceTestTags.NotebookPane).width
            val pageWidth = bounds(WorkspaceTestTags.PagePane).width
            saveWorkspace("navigation-resized.png")
            onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick().performClick()
            assertEquals(notebookWidth, bounds(WorkspaceTestTags.NotebookPane).width)
            assertEquals(pageWidth, bounds(WorkspaceTestTags.PagePane).width)
        }

    @Test
    fun collapsingNotebookDividerClosesBothAndCanBeReopened() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace()
            val initialWidth = bounds(WorkspaceTestTags.NotebookPane).width
            onNodeWithTag(WorkspaceTestTags.NotebookDivider).performMouseInput {
                moveTo(center); press(); moveBy(Offset(-150f, 0f)); moveBy(Offset(-250f, 0f)); release()
            }
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            saveWorkspace("navigation-closed.png")
            onNodeWithTag(WorkspaceTestTags.NavigationToggle).assertIsNotSelected().performClick()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
            assertEquals(initialWidth, bounds(WorkspaceTestTags.NotebookPane).width)
            onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
        }

    @Test
    fun collapsingPagesDividerClosesOnlyPagesAndKeepsTheCanvasUsable() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace()
            val canvasBefore = bounds(WorkspaceTestTags.PageCanvas).width
            drag(WorkspaceTestTags.PageDivider, -400f)
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PageDivider).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
            assertTrue(bounds(WorkspaceTestTags.PageCanvas).width > canvasBefore + 250f)
            onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick().performClick()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
        }

    @Test
    fun resizingHonorsDisplayDensityAndStopsAtTheCanvasBudget() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(settings = InterfaceSettings(displayScale = .75f)) { observed = it }
            drag(WorkspaceTestTags.PageDivider, 90f)
            runOnIdle { assertTrue(abs(observed.pagePaneWidth - 380f) < 2f) }
            drag(WorkspaceTestTags.PageDivider, 3000f)
            assertTrue(bounds(WorkspaceTestTags.PageCanvas).width >= 179f)
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
        }

    @Test
    fun aFingerCanResizeADividerWithoutTriggeringThePaneSwipe() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace()
            val before = bounds(WorkspaceTestTags.PagePane).width
            onNodeWithTag(WorkspaceTestTags.PageDivider).performTouchInput {
                swipe(center, center - Offset(100f, 0f), durationMillis = 400)
            }
            val after = bounds(WorkspaceTestTags.PagePane).width
            assertTrue(after < before - 50f, "finger resize: width $before -> $after")
            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
        }

    @Test
    fun panelsCanBecomeOneDpWideBeforeTheyClose() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()
        for ((divider, pane) in listOf(
            WorkspaceTestTags.NotebookDivider to WorkspaceTestTags.NotebookPane,
            WorkspaceTestTags.PageDivider to WorkspaceTestTags.PagePane,
        )) {
            onNodeWithTag(divider).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            assertEquals(1f, bounds(pane).width)
        }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
    }

    @Test
    fun largePanelsFitBesideAToolPaneWithoutLosingTheirRememberedWidths() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace { observed = it }
            onNodeWithTag(WorkspaceTestTags.PageDivider).performSemanticsAction(SemanticsActions.SetProgress) { it(700f) }
            val before = bounds(WorkspaceTestTags.PagePane).width
            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Settings)).performClick()
            onNodeWithTag(HardwareTags.Open).performClick()
            onNodeWithTag(ToolPaneTags.Pane).assertIsDisplayed()
            assertTrue(bounds(WorkspaceTestTags.PagePane).width < before)
            assertTrue(bounds(WorkspaceTestTags.PageCanvas).width >= 239f)
            runOnIdle { assertEquals(700f, observed.pagePaneWidth) }
            onNodeWithTag(ToolPaneTags.Close).performClick()
            assertEquals(before, bounds(WorkspaceTestTags.PagePane).width)
        }

    @Test
    fun accessibleResizeCanClosePagesAndLoggingReportsOperationsWithoutDocumentContents() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val logs = mutableListOf<String>()
            setWorkspace(log = DebugLog(true, logs::add))
            drag(WorkspaceTestTags.NotebookDivider, 80f)
            onNodeWithTag(WorkspaceTestTags.PageDivider).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            runOnIdle {
                assertTrue(logs.any { "[navigation] notebooks resized width=" in it })
                assertTrue(logs.any { "[navigation] visibility notebooks=true pages=false" in it })
                assertTrue(logs.none { "Homework" in it || "Calculus" in it })
            }
        }

    private fun ComposeUiTest.setWorkspace(
        settings: InterfaceSettings = InterfaceSettings(displayScale = 1f),
        log: DebugLog = DebugLog(),
        onState: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state, { state = it(state); onState(state) },
                    interfaceSettings = settings, debugLog = log)
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.drag(tag: String, delta: Float) {
        onNodeWithTag(tag).performMouseInput { moveTo(center); press(); moveBy(Offset(delta, 0f)); release() }
    }

    private fun ComposeUiTest.iconPixels(): List<Boolean> {
        val pixels = onNodeWithTag(WorkspaceTestTags.NavigationIcon, useUnmergedTree = true).captureToImage().toPixelMap()
        return (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { x ->
            val color = pixels[x, y]
            // The glyph's onSurface ink, well clear of the dark header bar behind it.
            color.red > .6f && color.green > .6f && color.blue > .6f
        } }
    }

    private fun ComposeUiTest.saveWorkspace(name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
