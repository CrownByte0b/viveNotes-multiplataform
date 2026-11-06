package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An unwritten box is only a caret: no frame, no handles, and nothing that takes clicks unseen. */
@OptIn(ExperimentalTestApi::class)
class EmptyTextBoxTest {

    @Test
    fun aPlacedBoxShowsNoFrameOrHandlesUntilItHasContent() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(emptyList()).toggleTextTool()
        setWorkspace(observed) { observed = it }

        clickPage(Offset(400f, 300f))
        val id = runOnIdle { observed.focusedTextOutlineId!! }
        assertNoChrome(id)
        editor().assertIsFocused()

        editor().performTextInput("A")
        assertChrome(id)

        // Everything gone again: it is back to being a box that will be discarded.
        editor().performTextClearance()
        assertNoChrome(id)
    }

    @Test
    fun spacesAloneBringUpNoFrame() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(emptyList()).toggleTextTool()
        setWorkspace(observed) { observed = it }
        clickPage(Offset(400f, 300f))
        val id = runOnIdle { observed.focusedTextOutlineId!! }

        editor().performTextReplacement("   ")
        assertNoChrome(id)
        editor().performTextReplacement("  x")
        assertChrome(id)
    }

    /** No frame is drawn, tagged or not: the box's edges are the same pixels focused or not. */
    @Test
    fun theEdgesOfAnEmptyBoxLookTheSameWhileItIsEdited() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val box = Outline.Text(id = BOX, x = 300f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
        var observed = page(listOf(box))
        setWorkspace(observed) { observed = it }
        val tag = onNodeWithTag(WorkspaceTestTags.textBox(BOX))
        val before = tag.edgePixels()

        tag.performMouseInput { click(center) }
        runOnIdle { assertEquals(BOX, observed.focusedTextOutlineId) }

        assertEquals(before, tag.edgePixels())
    }

    @Test
    fun theMouseOverAnEmptyBoxBringsUpNothing() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val box = Outline.Text(id = BOX, x = 300f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
        setWorkspace(page(listOf(box))) { }
        val tag = onNodeWithTag(WorkspaceTestTags.textBox(BOX))
        val before = tag.edgePixels()

        tag.performMouseInput { moveTo(center) }

        assertNoChrome(BOX)
        assertEquals(before, tag.edgePixels())
    }

    /** Regression: the box is 400 and more wide but cannot be seen, so this click looked dead. */
    @Test
    fun aClickInsideTheUnseenBoxMovesTheCaretThereAndKeepsTheKeyboard() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = page(emptyList()).toggleTextTool()
            setWorkspace(observed) { observed = it }
            clickPage(Offset(400f, 300f))
            val (id, first, undo) = runOnIdle { Triple(observed.focusedTextOutlineId!!, single(observed), observed.structuralUndo.size) }

            clickPage(Offset(600f, 330f))

            runOnIdle {
                val moved = single(observed)
                assertEquals(id, moved.id, "it is still the same box")
                assertEquals(first.x + 200f, moved.x, 1f)
                assertEquals(first.y + 30f, moved.y, 1f)
                assertEquals(id, observed.focusedTextOutlineId)
                assertEquals(undo, observed.structuralUndo.size, "nothing new to undo")
            }
            editor().assertIsFocused()
            editor().performTextInput("Here")
            runOnIdle { assertEquals("Here", single(observed).blocks.single().text) }
        }

    /** Beside the unseen box is bare page: the margin kept for a handle it does not have is gone. */
    @Test
    fun aClickJustBesideTheUnseenBoxPlacesANewOne() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page(emptyList()).toggleTextTool()
        setWorkspace(observed) { observed = it }
        clickPage(Offset(400f, 300f))
        val id = runOnIdle { observed.focusedTextOutlineId!! }
        val box = onNodeWithTag(WorkspaceTestTags.textBox(id)).getUnclippedBoundsInRoot()
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas).getUnclippedBoundsInRoot()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
            click(Offset((box.left - canvas.left).toPx() + 50f, (box.bottom - canvas.top).toPx() + 8f))
        }

        runOnIdle {
            assertNotEquals(id, observed.focusedTextOutlineId, "a new box")
            assertEquals(1, observed.selectedPage!!.document.outlines.size, "the old one was discarded")
        }
    }

    /** The unseen box was placed last and overlaps the note: it must not take the note's clicks. */
    @Test
    fun aWrittenBoxUnderTheUnseenBoxStillTakesItsClick() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val note = Outline.Text(id = "note", x = 300f, y = 340f, width = 400f, blocks = listOf(Block.of("A note to edit")))
        val empty = Outline.Text(id = BOX, x = 300f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
        var observed = page(listOf(note, empty)).toggleTextTool().focusTextBox(empty.id)
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.textBox(note.id)).performMouseInput { click(Offset(30f, 20f)) }

        runOnIdle {
            assertEquals(note.id, observed.focusedTextOutlineId)
            assertEquals(listOf(note.id), observed.selectedPage!!.document.outlines.map { it.id }, "the empty box was discarded")
        }
    }

    /** It rises above the other boxes on the first letter; the keyboard must stay with it. */
    @Test
    fun theKeyboardStaysWithABoxAsItBecomesWritten() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val note = Outline.Text(id = "note", x = 300f, y = 500f, width = 400f, blocks = listOf(Block.of("Listed after it")))
        val empty = Outline.Text(id = BOX, x = 300f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
        var observed = page(listOf(empty, note)).focusTextBox(empty.id)
        setWorkspace(observed) { observed = it }
        editor().performMouseClickToFocus()

        editor().performTextInput("a")
        editor().assertIsFocused()
        editor().performTextInput("b")
        editor().assertIsFocused()

        runOnIdle {
            val box = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().first { it.id == BOX }
            assertEquals("ab", box.blocks.single().text)
        }
    }

    /** Without the tool a click in a page's empty box edits it where it is, as always. */
    @Test
    fun withoutTheToolAClickInAnEmptyBoxLeavesItWhereItIs() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val box = Outline.Text(id = BOX, x = 300f, y = 300f, width = 400f, blocks = listOf(Block.of("")))
        var observed = page(listOf(box))
        setWorkspace(observed) { observed = it }

        onNodeWithTag(WorkspaceTestTags.textBox(BOX)).performMouseInput { click(Offset(200f, 40f)) }

        runOnIdle {
            assertEquals(BOX, observed.focusedTextOutlineId)
            assertEquals(box.x, single(observed).x)
            assertEquals(box.y, single(observed).y)
        }
    }

    @Test
    fun undoRemovesAPlacedBoxHoweverOftenItWasMoved() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val note = Outline.Text(id = "note", x = 800f, y = 600f, width = 200f, blocks = listOf(Block.of("Stays")))
        var observed = page(listOf(note)).toggleTextTool()
        setWorkspace(observed) { observed = it }
        clickPage(Offset(400f, 300f))
        clickPage(Offset(500f, 320f))
        clickPage(Offset(440f, 340f))

        runOnIdle {
            assertEquals(2, observed.selectedPage!!.document.outlines.size)
            val undone = observed.undoStructure().selectedPage!!.document.outlines
            assertEquals(listOf(note.id), undone.map { it.id })
            assertNull(observed.undoStructure().focusedTextOutline)
        }
    }

    private fun ComposeUiTest.assertNoChrome(id: String) {
        onNodeWithTag(WorkspaceTestTags.textBoxOutline(id)).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.textMoveBar(id)).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).assertDoesNotExist()
    }

    private fun ComposeUiTest.assertChrome(id: String) {
        onNodeWithTag(WorkspaceTestTags.textBoxOutline(id)).assertExists()
        onNodeWithTag(WorkspaceTestTags.textMoveBar(id)).assertExists()
        onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).assertExists()
    }

    /** The field of the page's first text box, which is the box under test wherever this is used. */
    private fun ComposeUiTest.editor(): SemanticsNodeInteraction = onNodeWithTag(WorkspaceTestTags.BodyEditor)

    /** A click where the editor's first line is, so the field holds the keyboard as a person would have it. */
    private fun SemanticsNodeInteraction.performMouseClickToFocus() {
        performMouseInput { click(center) }
    }

    private fun ComposeUiTest.clickPage(at: Offset) {
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(at) }
        waitForIdle()
    }

    private fun single(state: WorkspaceState): Outline.Text =
        state.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>().single()

    /** The box's outermost ring of pixels, where a frame would be drawn. */
    private fun SemanticsNodeInteraction.edgePixels(): List<Int> {
        val pixels = captureToImage().toPixelMap()
        val edge = ArrayList<Int>()
        for (x in 0 until pixels.width) { edge += pixels[x, 0].hashCode(); edge += pixels[x, pixels.height - 1].hashCode() }
        for (y in 0 until pixels.height) { edge += pixels[0, y].hashCode(); edge += pixels[pixels.width - 1, y].hashCode() }
        assertTrue(edge.isNotEmpty())
        return edge
    }

    private fun page(outlines: List<Outline>): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        return demo.updatePage(demo.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }.clearCanvasFocus()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }

    private companion object {
        const val BOX = "empty-box"
    }
}
