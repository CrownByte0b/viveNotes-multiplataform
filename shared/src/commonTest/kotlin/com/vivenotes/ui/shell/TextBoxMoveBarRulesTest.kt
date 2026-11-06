package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextBoxMoveBarRulesTest {
    private val written = Outline.Text(id = "written", x = 200f, y = 300f, width = 400f, blocks = listOf(Block.of("Hello")))
    private val unwritten = Outline.Text(id = "unwritten", x = 200f, y = 600f, width = 400f, blocks = listOf(Block.of("")))

    private fun page(): WorkspaceState {
        val demo = WorkspaceState.demo()
        return demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(written, unwritten))) }
            .clearCanvasFocus()
    }

    @Test
    fun aWrittenBoxShowsItsBarOnHoverAndAlwaysWhileEditedOrSelected() {
        assertEquals(TextBoxMoveBarMode.OnHover, page().textBoxMoveBarMode(written))
        assertEquals(TextBoxMoveBarMode.Always, page().focusTextBox(written.id).textBoxMoveBarMode(written))
        assertEquals(TextBoxMoveBarMode.Always,
            page().copy(selectedTextOutlineIds = setOf(written.id)).textBoxMoveBarMode(written))
    }

    @Test
    fun anUnwrittenBoxHasNoBarEvenWhileEdited() {
        assertEquals(TextBoxMoveBarMode.Hidden, page().textBoxMoveBarMode(unwritten))
        assertEquals(TextBoxMoveBarMode.Hidden, page().focusTextBox(unwritten.id).textBoxMoveBarMode(unwritten))
    }

    @Test
    fun aToolThatActsOnPressesKeepsHoverFromShowingTheBar() {
        val base = page()
        listOf(
            base.toggleTextTool(),
            base.toggleShapeTool(),
            base.toggleObjectLasso(),
            base.copy(drawPlacement = DrawPlacementTool.Table),
            base.copy(inkTool = InkTool.Pen),
        ).forEach { armed -> assertEquals(TextBoxMoveBarMode.Hidden, armed.textBoxMoveBarMode(written)) }
    }

    @Test
    fun aLassoSelectionKeepsItsBarWhileTheLassoIsStillArmed() {
        val selected = page().toggleObjectLasso().copy(selectedTextOutlineIds = setOf(written.id))
        assertEquals(TextBoxMoveBarMode.Always, selected.textBoxMoveBarMode(written))
    }

    @Test
    fun aPageThatCannotBeEditedHasNoBar() {
        val base = page()
        val locked = base.updatePage(base.selectedPageId) { it.copy(content = PageContent.Unloaded) }
        assertEquals(TextBoxMoveBarMode.Hidden, locked.textBoxMoveBarMode(written))
    }

    @Test
    fun hoverOnlyMattersWhenTheBarIsLeftToHover() {
        assertTrue(TextBoxMoveBarMode.OnHover.shows(hovered = true))
        assertFalse(TextBoxMoveBarMode.OnHover.shows(hovered = false))
        assertTrue(TextBoxMoveBarMode.Always.shows(hovered = false))
        assertFalse(TextBoxMoveBarMode.Hidden.shows(hovered = true))
    }

    @Test
    fun theBarCoversTheBoxsWidthJustAboveItLappingItByALine() {
        assertTrue(written.moveBarContains(Offset(200f, 285f)))
        assertTrue(written.moveBarContains(Offset(600f, 301f)))
        assertFalse(written.moveBarContains(Offset(199f, 290f)), "left of the box")
        assertFalse(written.moveBarContains(Offset(601f, 290f)), "right of the box")
        assertFalse(written.moveBarContains(Offset(300f, 284f)), "above the bar")
        assertFalse(written.moveBarContains(Offset(300f, 302f)), "in the box")
    }

    @Test
    fun atThePagesTopTheBarStaysOnThePageOverTheBox() {
        val top = written.copy(y = 4f)
        assertTrue(top.moveBarContains(Offset(300f, 0f)))
        assertTrue(top.moveBarContains(Offset(300f, 16f)))
        assertFalse(top.moveBarContains(Offset(300f, 17f)))
    }

    @Test
    fun theMouseIsOverABoxBody() {
        val state = page()
        assertEquals(written.id, state.textBoxUnderMouse(Offset(300f, 350f), null, HEIGHTS))
        assertNull(state.textBoxUnderMouse(Offset(100f, 350f), null, HEIGHTS), "left of it")
        assertNull(state.textBoxUnderMouse(Offset(300f, 381f), null, HEIGHTS), "below it")
    }

    @Test
    fun theStripAboveABoxIsItsOnlyOnceTheBarIsUp() {
        val state = page()
        val onBar = Offset(300f, 290f)
        assertNull(state.textBoxUnderMouse(onBar, hovered = null, HEIGHTS), "the bar is down, so that is bare page")
        assertEquals(written.id, state.textBoxUnderMouse(onBar, hovered = written.id, HEIGHTS), "the mouse went up onto it")
        assertEquals(written.id, state.focusTextBox(written.id).textBoxUnderMouse(onBar, hovered = null, HEIGHTS),
            "always up while edited")
    }

    @Test
    fun anUnwrittenBoxsStripStaysBarePage() {
        assertNull(page().textBoxUnderMouse(Offset(300f, 590f), hovered = unwritten.id, HEIGHTS))
    }

    @Test
    fun theLastOfOverlappingBoxesIsTheOneOnTop() {
        val over = written.copy(id = "over", x = 250f, y = 320f)
        val demo = WorkspaceState.demo()
        val state = demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(written, over))) }
            .clearCanvasFocus()
        assertEquals(over.id, state.textBoxUnderMouse(Offset(300f, 340f), null, mapOf(written.id to 80f, over.id to 80f)))
    }

    @Test
    fun aBarUpOverANeighbourTakesTheMouseFromIt() {
        val above = written.copy(id = "above", y = 200f)
        val demo = WorkspaceState.demo()
        val state = demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(written, above))) }
            .clearCanvasFocus()
        val heights = mapOf(written.id to 80f, above.id to 95f)
        assertEquals(above.id, state.textBoxUnderMouse(Offset(300f, 290f), null, heights))
        assertEquals(written.id, state.textBoxUnderMouse(Offset(300f, 290f), written.id, heights))
    }

    @Test
    fun anObjectLyingOverABoxKeepsTheMouseFromIt() {
        val shape = Outline.Shape(id = "shape", x = 280f, y = 320f)
        val demo = WorkspaceState.demo()
        val state = demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(written, shape))) }
            .clearCanvasFocus()
        assertNull(state.textBoxUnderMouse(Offset(300f, 340f), null, HEIGHTS))
        assertEquals(written.id, state.textBoxUnderMouse(Offset(220f, 340f), null, HEIGHTS))
    }

    private companion object {
        val HEIGHTS = mapOf("written" to 80f, "unwritten" to 80f)
    }
}
