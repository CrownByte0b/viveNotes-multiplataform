package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page's geometry on the desktop canvas — Android's `PageViewTest` paper-size, endless-canvas
 * and ruling cases. The test density is 1, so a page dp is a pixel at 100%.
 */
@OptIn(ExperimentalTestApi::class)
class PageViewTest {

    @Test
    fun lightAppThemeReversesALegacyDarkCanvasAndViewSwitchCanOverrideItAgain() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var reported: ViewSettings? = null
            setContent {
                var state by remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.View)) }
                ViveNotesTheme(darkTheme = false) {
                    WorkspaceScreen(state, { state = it(state) },
                        interfaceSettings = InterfaceSettings(displayScale = 1f, darkTheme = false),
                        viewSettings = ViewSettings(canvasDark = true),
                        onViewSettingsChange = { reported = it })
                }
            }
            fun paper() = onNodeWithTag(WorkspaceTestTags.PageCanvas).captureToImage().toPixelMap()[300, 5]
            assertEquals(Color.White, paper())
            assertEquals(Color(0xFF1B1B1B), canvasPalette(PageStyle(), canvasDark = false).ink)
            onNodeWithTag(ViewRibbonTags.SwitchBackground).performClick()
            assertEquals(Color(0xFF1F1F1F), paper())
            runOnIdle {
                assertEquals(true, reported?.canvasDark)
                assertEquals(false, reported?.canvasThemeDark)
            }
        }

    private val a6Width = (PaperSize.A6.widthInches * PageStyle.DP_PER_INCH).roundToInt()
    private val a6Height = (PaperSize.A6.heightInches * PageStyle.DP_PER_INCH).roundToInt()

    // --- an infinite page keeps going ------------------------------------------------------------

    /** Infinite is an endless canvas, not "content plus a margin": scrolling finds more page. */
    @Test
    fun anInfinitePageExtendsAsItIsScrolled() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setPage(PageStyle())
        val before = extent().second
        repeat(6) {
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(500f, 400f))
                scroll(40f)
            }
            waitForIdle()
        }
        val after = extent().second
        assertTrue(after > before, "scrolling down an infinite page should find more of it: was $before, still $after")
    }

    /** Regression for Android's first 5% fix, which cut the page off at the paired constraint. */
    @Test
    fun fivePercentZoomKeepsFarContentReachable() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val far = Outline.Text(id = "far", x = 7_561f, y = 18_685f, width = 500f, minHeight = 600f,
            blocks = listOf(Block.of("far away")))
        setPage(PageStyle(hideTitle = true), outlines = listOf(far), view = ViewSettings(zoom = 0.05f))
        val (width, height) = extent()
        assertTrue(width >= 7_561 + 500, "the canvas was cut off before the far-right content: $width")
        assertTrue(height >= 18_685 + 600, "the canvas was cut off before the far-bottom content: $height")
        onNodeWithTag(WorkspaceTestTags.textBox("far")).assertExists()
    }

    // --- the sheet binds, or marks -----------------------------------------------------------------

    @Test
    fun paperSizeGivesThePageRealBounds() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6), outlines = listOf(text(0f, 40f)))
        val sheet = onNodeWithTag(WorkspaceTestTags.PageSheet).fetchSemanticsNode().size
        assertClose("sheet width", a6Width, sheet.width)
        assertClose("sheet height", a6Height, sheet.height)
        onNodeWithTag(WorkspaceTestTags.SheetGuide).assertDoesNotExist()
    }

    @Test
    fun contentPastTheSheetMarksItInsteadOfClippingIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6), outlines = listOf(text(0f, a6Height + 200f)))
        onNodeWithTag(WorkspaceTestTags.SheetGuide).assertExists()
        onNodeWithTag(WorkspaceTestTags.PageSheet).assertDoesNotExist()
        assertTrue(extent().second > a6Height, "the page must still reach the content past the sheet")
    }

    /** A page bound by a sheet has edges: there is nowhere outside it to put anything. */
    @Test
    fun aTapBesideABoundSheetCreatesNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed: WorkspaceState? = null
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None),
            tab = RibbonTab.Document, onChange = { observed = it })
        onNodeWithTag(com.vivenotes.ui.ribbon.document.DocumentRibbonTags.Text).performClick()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(Offset(a6Width + 80f, 200f)) }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(a6Width + 80f, 200f)) }
        runOnIdle { assertEquals(0, observed?.selectedPage?.document?.outlines?.size ?: 0) }

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(120f, 200f)) }
        runOnIdle { assertEquals(1, observed!!.selectedPage!!.document.outlines.size) }
    }

    /** With the title hidden, its band is page like any other. */
    @Test
    fun aHiddenTitleGivesItsBandToTheContent() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed: WorkspaceState? = null
        setPage(PageStyle(hideTitle = true), tab = RibbonTab.Document, onChange = { observed = it })
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertDoesNotExist()
        onNodeWithTag(com.vivenotes.ui.ribbon.document.DocumentRibbonTags.Text).performClick()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(300f, 30f)) }
        runOnIdle { assertEquals(30f, observed!!.selectedPage!!.document.outlines.single().y, 1f) }
    }

    // --- ruling ----------------------------------------------------------------------------------

    @Test
    fun anUnruledPageIsOneFlatColour() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None))
        assertTrue(largestColourShareInsideTheSheet() > 0.99f)
    }

    @Test
    fun everyRulingFromThePaperMenuActuallyPaints() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None))
        listOf(RuleLines.Standard, RuleLines.Wide, RuleLines.Dotted, RuleLines.Hexagonal,
            RuleLines.GridMedium, RuleLines.GridLarge).forEach { rule ->
            onNodeWithTag(ViewRibbonTags.Paper).performClick()
            onNodeWithTag(ViewRibbonTags.ruleLines(rule)).performClick()
            assertTrue(sheetColours().size > 1, "nothing was painted for $rule")
        }
    }

    /** The margins are only observable through their guides, drawn while the pane that sets them is open. */
    @Test
    fun marginGuidesShowOnlyWhileThePaperSizePaneIsOpen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setPage(PageStyle(hideTitle = true, paper = PaperSize.A6, ruleLines = RuleLines.None), tab = RibbonTab.View)
        val closed = largestColourShareInsideTheSheet()
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        val open = largestColourShareInsideTheSheet()
        assertTrue(closed > 0.99f && open < closed, "margin guides: closed $closed, open $open")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun text(x: Float, y: Float) = Outline.Text(id = "t", x = x, y = y, width = 300f, blocks = listOf(Block.of("t")))

    private fun ComposeUiTest.extent(): Pair<Int, Int> =
        onNodeWithTag(WorkspaceTestTags.CanvasExtent).fetchSemanticsNode().size.let { it.width to it.height }

    /** The share of the sheet held by its commonest colour — 1 for a sheet nothing is painted on. */
    private fun ComposeUiTest.largestColourShareInsideTheSheet(): Float {
        val counts = sheetColours()
        return (counts.values.maxOrNull() ?: 0).toFloat() / counts.values.sum()
    }

    /** How often each colour appears on the sheet, kept clear of the sheet's own border. */
    private fun ComposeUiTest.sheetColours(): Map<Int, Int> {
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        val sheet = onNodeWithTag(WorkspaceTestTags.PageSheet).fetchSemanticsNode().boundsInRoot
        val counts = HashMap<Int, Int>()
        val inset = 8
        for (y in sheet.top.toInt() + inset until minOf(sheet.bottom.toInt() - inset, pixels.height)) {
            for (x in sheet.left.toInt() + inset until minOf(sheet.right.toInt() - inset, pixels.width)) {
                val colour = pixels[x, y].hashCode()
                counts[colour] = (counts[colour] ?: 0) + 1
            }
        }
        return counts
    }

    private fun assertClose(what: String, expected: Int, actual: Int, tolerance: Int = 2) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected about $expected px, was $actual")
    }

    private fun ComposeUiTest.setPage(
        style: PageStyle,
        outlines: List<Outline> = emptyList(),
        view: ViewSettings = ViewSettings(),
        tab: RibbonTab = RibbonTab.View,
        onChange: (WorkspaceState) -> Unit = {},
    ) {
        val demo = WorkspaceState.demo()
        val initial = demo.copy(activeTab = tab).updatePage(demo.selectedPageId) {
            it.copy(document = PageDoc(outlines = outlines, style = style))
        }
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onChange(state) },
                    viewSettings = view)
            }
        }
    }
}
