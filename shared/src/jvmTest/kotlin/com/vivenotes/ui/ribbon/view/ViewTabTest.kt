package com.vivenotes.ui.ribbon.view

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Orientation
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.navigation.NavigationTestTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.TabsLayout
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The View tab is a wall of controls that differ mostly in which setting they write — the shape of
 * code where a copy-paste sends "Wide Ruled" to the paper size. The first group, ported from
 * Android's `ViewTabTest`, checks each control reaches the setting it names; the second checks the
 * workspace carries each setting through to the page and the canvas.
 */
@OptIn(ExperimentalTestApi::class)
class ViewTabTest {

    private var ruleLines: RuleLines? = null
    private var pageColor: Int? = null
    private var pageColorCleared = false
    private var paneToggled = false
    private var hideTitle: Boolean? = null
    private var zoom: Float? = null
    private var zoomedIn = false
    private var zoomedOut = false
    private var fittedToPageWidth = false
    private var tabsLayout: TabsLayout? = null
    private var canvasDark: Boolean? = null

    private fun ComposeUiTest.setTab(
        style: PageStyle = PageStyle(),
        settings: ViewSettings = ViewSettings(),
        pageOpen: Boolean = true,
        dark: Boolean = true,
    ) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                ViewRibbon(
                    style = style,
                    pageOpen = pageOpen,
                    settings = settings,
                    canvasDark = dark,
                    actions = ViewActions(
                        setRuleLines = { ruleLines = it },
                        setPageColor = { if (it == null) pageColorCleared = true else pageColor = it },
                        setHideTitle = { hideTitle = it },
                        setPaperSize = {},
                        setOrientation = {},
                        setCustomPaper = {},
                        setMargins = {},
                        setZoom = { zoom = it },
                        zoomIn = { zoomedIn = true },
                        zoomOut = { zoomedOut = true },
                        zoomToPageWidth = { fittedToPageWidth = true },
                        setTabsLayout = { tabsLayout = it },
                        setCanvasDark = { canvasDark = it },
                        togglePaperSizePane = { paneToggled = true },
                    ),
                )
            }
        }
    }

    @Test
    fun paperPicksTheChosenRuling() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab()
        onNodeWithTag(ViewRibbonTags.Paper).performClick()
        onNodeWithText("Hexagonal Paper").performClick()
        assertEquals(RuleLines.Hexagonal, ruleLines)
    }

    @Test
    fun removedRuleLineOptionsAreNotOffered() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab()
        onNodeWithTag(ViewRibbonTags.Paper).performClick()
        onNodeWithText("Narrow Ruled").assertDoesNotExist()
        onNodeWithText("College Ruled").assertDoesNotExist()
        onNodeWithText("Small Grid").assertDoesNotExist()
        onNodeWithTag(ViewRibbonTags.ruleLines(RuleLines.GridMedium)).assertIsSelected()
    }

    @Test
    fun pageColorPaintsAndCanBeCleared() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(style = PageStyle(backgroundArgb = 0xFF112233.toInt()))
        onNodeWithTag(ViewRibbonTags.PageColor).performClick()
        onNodeWithTag(ViewRibbonTags.pageColor(0xFF17232E.toInt())).performClick()
        assertEquals(0xFF17232E.toInt(), pageColor)

        onNodeWithTag(ViewRibbonTags.PageColor).performClick()
        onNodeWithText("No Color").performClick()
        assertTrue(pageColorCleared, "clearing the page colour must hand the page back to the canvas")
    }

    /** Paper Size is a pane, not a menu: six fields in two groups do not belong in a drop-down. */
    @Test
    fun paperSizeOpensItsPaneRatherThanAMenu() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab()
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        assertTrue(paneToggled)
        onNodeWithText("A4").assertDoesNotExist()
    }

    @Test
    fun hidePageTitleTogglesRatherThanOnlySetting() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(style = PageStyle(hideTitle = true))
        onNodeWithTag(ViewRibbonTags.HideTitle).assertIsSelected()
        onNodeWithTag(ViewRibbonTags.HideTitle).performClick()
        assertEquals(false, hideTitle, "a second press should bring the title back")
    }

    @Test
    fun theZoomGroupReachesItsOwnActions() = runDesktopComposeUiTest(width = 1600, height = 600) {
        // Not at 100%: the picker shows the current zoom, which would then read like the 100% button.
        setTab(settings = ViewSettings(zoom = 1.25f))
        onNodeWithTag(ViewRibbonTags.PageWidth).performClick()
        onNodeWithTag(ViewRibbonTags.ActualSize).performClick()
        onNodeWithTag(ViewRibbonTags.ZoomIn).performClick()
        onNodeWithTag(ViewRibbonTags.ZoomOut).performClick()
        assertTrue(fittedToPageWidth)
        assertEquals(1f, zoom)
        assertTrue(zoomedIn && zoomedOut)
    }

    @Test
    fun theZoomPickerOffersTheSameStepsTheButtonsClimb() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(settings = ViewSettings(zoom = 1.25f))
        onNodeWithText("125%").performClick()
        ViewSettings.ZOOM_STEPS.forEach { onNodeWithTag(ViewRibbonTags.zoomStep(it)).assertExists() }
        onNodeWithTag(ViewRibbonTags.zoomStep(1.5f)).performClick()
        assertEquals(1.5f, zoom)
    }

    @Test
    fun zoomButtonsStopAtTheEndsOfTheRange() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(settings = ViewSettings(zoom = ViewSettings.MAX_ZOOM))
        onNodeWithTag(ViewRibbonTags.ZoomIn).assertIsNotEnabled()
    }

    @Test
    fun switchBackgroundFlipsWhateverTheCanvasCurrentlyIs() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(dark = true)
        onNodeWithTag(ViewRibbonTags.SwitchBackground).performClick()
        assertEquals(false, canvasDark)
    }

    @Test
    fun tabsLayoutSwitchesTheNavigation() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(settings = ViewSettings(tabsLayout = TabsLayout.Vertical))
        onNodeWithTag(ViewRibbonTags.TabsLayout).performClick()
        onNodeWithText("Horizontal Tabs").performClick()
        assertEquals(TabsLayout.Horizontal, tabsLayout)
    }

    /** With no page open there is nothing whose appearance these could change. */
    @Test
    fun pageControlsAreInertUntilAPageIsOpen() = runDesktopComposeUiTest(width = 1600, height = 600) {
        setTab(pageOpen = false)
        onNodeWithTag(ViewRibbonTags.Paper).performClick()
        onNodeWithText("Standard Ruled").assertDoesNotExist()
        assertNull(ruleLines)
        listOf(ViewRibbonTags.PageColor, ViewRibbonTags.PaperSize, ViewRibbonTags.HideTitle, ViewRibbonTags.PageWidth)
            .forEach { onNodeWithTag(it).assertIsNotEnabled() }
    }

    // --- through the workspace -------------------------------------------------------------------

    @Test
    fun paperAndPageColorChangeTheOpenPage() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(ViewRibbonTags.Paper).performClick()
        onNodeWithTag(ViewRibbonTags.ruleLines(RuleLines.Wide)).performClick()
        onNodeWithTag(ViewRibbonTags.PageColor).performClick()
        onNodeWithTag(ViewRibbonTags.pageColor(0xFFFFF8E7.toInt())).performClick()
        runOnIdle {
            assertEquals(RuleLines.Wide, observed.selectedPage!!.document.style.ruleLines)
            assertEquals(0xFFFFF8E7.toInt(), observed.selectedPage!!.document.style.backgroundArgb)
        }
        val paper = onNodeWithTag(WorkspaceTestTags.PageCanvas).captureToImage().toPixelMap()
        assertEquals(Color(0xFFFFF8E7), paper[300, 5])
    }

    @Test
    fun hidePageTitleRemovesTheTitleFromThePage() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertExists()
        onNodeWithTag(ViewRibbonTags.HideTitle).performClick()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertDoesNotExist()
    }

    @Test
    fun zoomControlsChangeTheCanvasAndTheZoomOutlivesThePage() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var reported: ViewSettings? = null
        setWorkspace(onView = { reported = it })
        onNodeWithTag(ViewRibbonTags.ZoomIn).performClick()
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertContentDescriptionEquals("Canvas zoom 125 percent")
        onNodeWithTag(ViewRibbonTags.Zoom).performClick()
        onNodeWithTag(ViewRibbonTags.zoomStep(0.5f)).performClick()
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertContentDescriptionEquals("Canvas zoom 50 percent")
        runOnIdle { assertEquals(0.5f, reported?.zoom) }

        // Android's rule: zoom is how this device looks at the notes, not a property of one page.
        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performClick()
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertContentDescriptionEquals("Canvas zoom 50 percent")
        onNodeWithTag(ViewRibbonTags.ActualSize).performClick()
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertContentDescriptionEquals("Canvas zoom 100 percent")
    }

    /** Regression: the ribbon zoomed about the window's centre and scrolled the title off the top. */
    @Test
    fun zoomingAtTheTopOfThePageKeepsItsTopInView() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        val canvasTop = onNodeWithTag(WorkspaceTestTags.PageCanvas).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag(ViewRibbonTags.ZoomIn).performClick()
        onNodeWithTag(ViewRibbonTags.ZoomIn).performClick()
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertContentDescriptionEquals("Canvas zoom 150 percent")
        val title = onNodeWithTag(WorkspaceTestTags.TitleEditor).fetchSemanticsNode().boundsInRoot
        assertTrue(title.top >= canvasTop, "the title was scrolled off: ${title.top} above $canvasTop")
    }

    @Test
    fun pageWidthFitsThePageToTheWindow() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(ViewRibbonTags.PageWidth).performClick()
        val viewport = onNodeWithTag(WorkspaceTestTags.PageCanvas).fetchSemanticsNode().size.width
        val page = maxOf(720f, observed.selectedPage!!.document.outlines.maxOf { it.x + it.width })
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator)
            .assertContentDescriptionEquals("Canvas zoom ${(viewport / page * 100).roundToInt()} percent")
    }

    @Test
    fun switchBackgroundRepaintsTheCanvasAndIsRemembered() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var reported: ViewSettings? = null
        setWorkspace(onView = { reported = it })
        fun paper() = onNodeWithTag(WorkspaceTestTags.PageCanvas).captureToImage().toPixelMap()[300, 5]
        assertEquals(Color(0xFF1F1F1F), paper())
        onNodeWithTag(ViewRibbonTags.SwitchBackground).performClick()
        assertEquals(Color.White, paper())
        runOnIdle { assertEquals(false, reported?.canvasDark) }
    }

    @Test
    fun paperSizeDocksAPaneThatSizesTheSheet() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertIsDisplayed()
        onNodeWithTag(ToolPaneTags.field("Size")).assertTextContains("Infinite")

        onNodeWithTag(ToolPaneTags.field("Size")).performClick()
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).performClick()
        onNodeWithTag(ToolPaneTags.field("Orientation")).performClick()
        onNodeWithTag(ToolPaneTags.option("Orientation", "Landscape")).performClick()
        runOnIdle {
            assertEquals(PaperSize.A4, observed.selectedPage!!.document.style.paper)
            assertEquals(Orientation.Landscape, observed.selectedPage!!.document.style.orientation)
        }
        val sheet = onNodeWithTag(WorkspaceTestTags.PageSheet).fetchSemanticsNode().size
        assertEquals((PaperSize.A4.heightInches * PageStyle.DP_PER_INCH).roundToInt(), sheet.width, absoluteTolerance = 2)
        assertEquals((PaperSize.A4.widthInches * PageStyle.DP_PER_INCH).roundToInt(), sheet.height, absoluteTolerance = 2)

        // The pane stays while the tab changes, and closes from its own button.
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertIsDisplayed()
        onNodeWithTag(ToolPaneTags.Close).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    @Test
    fun horizontalTabsReplaceTheNotebookPaneWithSectionTabs() = runDesktopComposeUiTest(width = 1600, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.SectionTabs).assertDoesNotExist()

        onNodeWithTag(ViewRibbonTags.TabsLayout).performClick()
        onNodeWithTag(ViewRibbonTags.tabsLayout(TabsLayout.Horizontal)).performClick()
        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
        onNodeWithTag(NavigationTestTags.SectionTabs).assertIsDisplayed()
        onNodeWithTag(NavigationTestTags.sectionTab("chapter-1")).assertIsSelected()

        onNodeWithTag(NavigationTestTags.sectionTab("chapter-2")).performClick()
        runOnIdle { assertEquals("sequences", observed.selectedPageId) }
    }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo().copy(activeTab = RibbonTab.View),
        onView: (ViewSettings) -> Unit = {},
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    onViewSettingsChange = onView)
            }
        }
    }

    private fun ComposeUiTest.setWorkspace(onStateChange: (WorkspaceState) -> Unit) =
        setWorkspace(onView = {}, onStateChange = onStateChange)
}

private fun assertEquals(expected: Int, actual: Int, absoluteTolerance: Int) {
    assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected about $expected, was $actual")
}
