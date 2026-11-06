package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.isUnwritten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmptyTextBoxRulesTest {
    private val empty = Outline.Text(id = "empty", x = 200f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
    private val written = Outline.Text(id = "written", x = 200f, y = 340f, width = 400f, blocks = listOf(Block.of("Hello")))

    private fun page(vararg outlines: Outline): WorkspaceState {
        val demo = WorkspaceState.demo()
        return demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = outlines.toList())) }
            .clearCanvasFocus()
    }

    @Test
    fun spacesAndBlankLinesAreNotContent() {
        assertTrue(empty.isUnwritten())
        assertTrue(empty.copy(blocks = listOf(Block.of("   "), Block.of(""))).isUnwritten())
        assertFalse(empty.copy(blocks = listOf(Block.of("  x"))).isUnwritten())
    }

    @Test
    fun aBoxOfSpacesHasNoBarLikeOneThatIsEmpty() {
        val spaces = empty.copy(blocks = listOf(Block.of("   ")))
        assertEquals(TextBoxMoveBarMode.Hidden, page(spaces).focusTextBox(spaces.id).textBoxMoveBarMode(spaces))
    }

    @Test
    fun placingAnEmptyBoxMovesTheSameBoxAndFocusesItWithNothingToUndo() {
        val state = page(empty).toggleTextTool()
        val placed = state.placeEmptyTextBox(empty.id, 500f, 450f)

        val box = placed.selectedPage!!.document.outlines.single() as Outline.Text
        assertEquals(empty.id, box.id)
        assertEquals(500f, box.x)
        assertEquals(450f, box.y)
        assertEquals(empty.id, placed.focusedTextOutlineId)
        assertEquals(state.structuralUndo, placed.structuralUndo)
    }

    @Test
    fun placingABoxAlreadyBeingEditedKeepsTheFormattingArmedForIt() {
        val armed = setOf<Mark>(Mark.Bold)
        val editing = page(empty).toggleTextTool().focusTextBox(empty.id).copy(typingMarks = armed)
        assertEquals(armed, editing.placeEmptyTextBox(empty.id, 500f, 450f).typingMarks)
    }

    @Test
    fun onlyTheTextToolPlacesAndOnlyAnUnwrittenBoxInAPlaceItMayGo() {
        val armed = page(empty, written).toggleTextTool()
        assertSame(armed, armed.placeEmptyTextBox(written.id, 500f, 450f), "a written box stays put")
        assertSame(armed, armed.placeEmptyTextBox("no such box", 500f, 450f))
        assertSame(armed, armed.placeEmptyTextBox(empty.id, 500f, 10f), "not into the title band")
        val unarmed = page(empty, written)
        assertSame(unarmed, unarmed.placeEmptyTextBox(empty.id, 500f, 450f), "no tool, no move")
    }

    @Test
    fun anEmptyBoxIsAtAPointOnlyWhenNothingElseIs() {
        val heights = mapOf(empty.id to 80f, written.id to 80f)
        // Listed either way round: written text wins whichever came first.
        listOf(page(empty, written), page(written, empty)).forEach { state ->
            assertEquals(empty.id, state.emptyTextBoxAt(Offset(300f, 320f), heights), "only the empty box")
            assertNull(state.emptyTextBoxAt(Offset(300f, 360f), heights), "both, so the written one")
            assertNull(state.emptyTextBoxAt(Offset(100f, 320f), heights), "neither")
        }
        val shape = Outline.Shape(id = "shape", x = 280f, y = 300f)
        assertNull(page(empty, shape).emptyTextBoxAt(Offset(300f, 320f), heights), "an object lies over it")
    }

    @Test
    fun theMouseIsOverTheWrittenBoxBeneathAnEmptyOneListedLater() {
        val heights = mapOf(empty.id to 80f, written.id to 80f)
        assertEquals(written.id, page(written, empty).textBoxUnderMouse(Offset(300f, 360f), null, heights))
        assertNull(page(empty).textBoxUnderMouse(Offset(300f, 320f), null, heights), "an empty box is never hovered")
    }
}
