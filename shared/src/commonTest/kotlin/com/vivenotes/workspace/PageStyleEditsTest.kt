package com.vivenotes.workspace

import com.vivenotes.model.Orientation
import com.vivenotes.model.Outline
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperDimensions
import com.vivenotes.model.PaperSize
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The View tab's page commands: each reaches the setting it names, and only on an open page. */
class PageStyleEditsTest {

    private val initial = WorkspaceState.demo()
    private val WorkspaceState.style: PageStyle get() = selectedPage!!.document.style

    @Test
    fun aNewPageIsAnInfiniteCanvas() {
        assertEquals(PaperSize.Auto, initial.style.paper)
        assertEquals(null, initial.style.pageSizeDp)
    }

    @Test
    fun eachCommandWritesItsOwnSetting() {
        assertEquals(RuleLines.Hexagonal, initial.setRuleLines(RuleLines.Hexagonal).style.ruleLines)
        assertEquals(0xFF17232E.toInt(), initial.setPageColor(0xFF17232E.toInt()).style.backgroundArgb)
        assertTrue(initial.setHideTitle(true).style.hideTitle)
        assertEquals(PaperSize.A5, initial.setPaperSize(PaperSize.A5).style.paper)
        assertEquals(Orientation.Landscape, initial.setOrientation(Orientation.Landscape).style.orientation)
        assertEquals(PrintMargins(topInches = 1f), initial.setMargins(PrintMargins(topInches = 1f)).style.margins)
    }

    @Test
    fun noColourHandsThePageBackToTheCanvas() {
        val painted = initial.setPageColor(0xFFFFF8E7.toInt())
        assertEquals(null, painted.setPageColor(null).style.backgroundArgb)
    }

    @Test
    fun choosingCustomSeedsItFromTheSizeBeingLeft() {
        val custom = initial.setPaperSize(PaperSize.A5).setPaperSize(PaperSize.Custom).style
        assertEquals(PaperSize.Custom, custom.paper)
        assertEquals(PaperDimensions(PaperSize.A5.widthInches, PaperSize.A5.heightInches), custom.customPaper)

        // From an infinite page there is no sheet to copy, so it starts as A4.
        assertEquals(PaperDimensions.DEFAULT, initial.setPaperSize(PaperSize.Custom).style.customPaper)
    }

    @Test
    fun typingADimensionMakesTheSheetCustom() {
        val typed = initial.setPaperSize(PaperSize.A4).setCustomPaper(PaperDimensions(6f, 9f)).style
        assertEquals(PaperSize.Custom, typed.paper)
        assertEquals(PaperDimensions(6f, 9f), typed.paperInches)
    }

    @Test
    fun aStyleChangeIsADocumentEditThatAutosaves() {
        val before = initial.selectedPage!!.document
        val after = initial.setRuleLines(RuleLines.Dotted).selectedPage!!.document
        assertNotEquals(before, after)
        assertEquals(before.outlines, after.outlines)
    }

    @Test
    fun anUnloadedOrUnreadablePageRefusesEveryStyleCommand() {
        listOf(PageContent.Unloaded, PageContent.Unreadable).forEach { content ->
            val blocked = initial.updatePage(initial.selectedPageId) { it.copy(content = content) }
            assertSame(blocked, blocked.setRuleLines(RuleLines.None))
            assertSame(blocked, blocked.setPaperSize(PaperSize.A4))
            assertSame(blocked, blocked.setHideTitle(true))
        }
    }

    @Test
    fun anUnchangedStyleIsNotAnEdit() {
        assertSame(initial, initial.setRuleLines(initial.style.ruleLines))
    }

    /** Regression: undo restored whole snapshots, so it also reverted a ruling chosen after a move. */
    @Test
    fun undoingAMoveKeepsTheRulingChosenSince() {
        val shape = Outline.Shape(id = "shape", x = 300f, y = 300f)
        val withShape = initial.updatePage(initial.selectedPageId) {
            it.copy(document = it.document.copy(outlines = it.document.outlines + shape))
        }
        val moved = withShape.selectObject(shape.id).moveSelectedObjects(40f, 0f)
        val restyled = moved.setRuleLines(RuleLines.Wide)

        val undone = restyled.undoStructure()
        assertEquals(300f, undone.selectedPage!!.document.outlines.first { it.id == shape.id }.x)
        assertEquals(RuleLines.Wide, undone.style.ruleLines)
        assertEquals(RuleLines.Wide, undone.redoStructure().style.ruleLines)
    }

    @Test
    fun theTitleBandTakesTextOnlyWhileTheTitleIsShown() {
        val armed = initial.toggleTextTool()
        val inBand = PageStyle.TITLE_BAND_DP / 2f
        assertSame(armed, armed.createTextBox(200f, inBand))

        val hidden = armed.setHideTitle(true)
        val placed = hidden.createTextBox(200f, inBand)
        assertEquals(inBand, placed.focusedTextOutline!!.y)
        assertEquals(0f, hidden.style.titleFloor)
        assertEquals(PageStyle.TITLE_BAND_DP, initial.style.titleFloor)
    }
}
