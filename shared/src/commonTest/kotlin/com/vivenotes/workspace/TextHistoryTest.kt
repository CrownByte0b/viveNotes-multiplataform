package com.vivenotes.workspace

import com.vivenotes.model.Align
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Text and its formatting are undo steps, in the same history as the page's objects. */
class TextHistoryTest {
    private val yellow = Mark.Highlight(0x66FFEB3B)

    /** A new, empty text box being edited. */
    private fun newBox(): WorkspaceState = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)

    /** Types [chars] one at a time at the caret, as the text field reports each keystroke. */
    private fun WorkspaceState.type(chars: String): WorkspaceState = chars.fold(this) { state, char ->
        val buffer = state.richText!!
        val range = buffer.selection
        val text = buffer.text.substring(0, range.min) + char + buffer.text.substring(range.max)
        state.editSelectedText(text, TextSelection(range.min + 1))
    }

    private fun WorkspaceState.backspace(times: Int): WorkspaceState = (1..times).fold(this) { state, _ ->
        val buffer = state.richText!!
        val at = buffer.selection.start
        state.editSelectedText(buffer.text.removeRange(at - 1, at), TextSelection(at - 1))
    }

    /** The Delete key: the character after the caret goes, and the caret stays. */
    private fun WorkspaceState.delete(): WorkspaceState {
        val buffer = richText!!
        val at = buffer.selection.start
        return editSelectedText(buffer.text.removeRange(at, at + 1), TextSelection(at))
    }

    private val WorkspaceState.text: String get() = richText!!.text
    private val WorkspaceState.firstRunMarks: Set<Mark> get() = richText!!.blocks.first().runs.first().marks

    private fun WorkspaceState.undo(times: Int = 1) = (1..times).fold(this) { state, _ -> state.undoStructure() }
    private fun WorkspaceState.redo(times: Int = 1) = (1..times).fold(this) { state, _ -> state.redoStructure() }

    @Test
    fun boldHighlightAndCenterAreEachUndoneAndRedone() {
        val typed = newBox().type("hello world")
        // A new box's text carries the default font and size.
        val base = typed.firstRunMarks
        val formatted = typed.selectText(TextSelection(0, 5)).toggleSelectedMark(Mark.Bold)
            .setSelectedMark(yellow).alignSelectedText(Align.Center)
        assertEquals(base + Mark.Bold + yellow, formatted.firstRunMarks)
        assertEquals(Align.Center, formatted.richText!!.blocks.first().align)

        val uncentered = formatted.undo()
        assertEquals(Align.Start, uncentered.richText!!.blocks.first().align)
        assertEquals(base + Mark.Bold + yellow, uncentered.firstRunMarks)
        assertEquals(TextSelection(0, 5), uncentered.editorSelection)
        val unhighlighted = uncentered.undo()
        assertEquals(base + Mark.Bold, unhighlighted.firstRunMarks)
        val plain = unhighlighted.undo()
        assertEquals(base, plain.firstRunMarks)
        assertEquals("hello world", plain.text, "undoing formatting leaves the text")

        val redone = plain.redo(3)
        assertEquals(formatted.selectedPage!!.document, redone.selectedPage!!.document)
        assertEquals(TextSelection(0, 5), redone.editorSelection)
        assertTrue(redone.structuralRedo.isEmpty())
    }

    @Test
    fun listsIndentsLinksAndClearFormattingAreUndone() {
        val typed = newBox().type("item")
        val commands = listOf<(WorkspaceState) -> WorkspaceState>(
            { it.setSelectedBlockType(BlockType.Bullet) },
            { it.indentSelectedText(1) },
            { it.setSelectedLineSpacing(2f) },
            { it.selectText(TextSelection(0, 4)).insertLink("item", "https://example.com") },
            { it.clearSelectedFormatting() },
            { it.selectText(TextSelection(0, 4)).setSelectedMark(Mark.TextColor(0xFFE53935.toInt())) },
            { it.selectText(TextSelection(0, 4)).setSelectedMark(Mark.FontSize(24)) },
        )
        var state = typed
        for (command in commands) {
            val before = state.selectedPage!!.document
            val after = command(state)
            assertTrue(after.selectedPage!!.document != before)
            assertEquals(before, after.undoStructure().selectedPage!!.document)
            state = after
        }
    }

    @Test
    fun typingIsUndoneAWordAtATime() {
        val typed = newBox().type("hello world")
        assertEquals("hello ", typed.undo().text)
        assertEquals("", typed.undo(2).text)
        assertEquals("hello ", typed.undo(2).redo().text)
        assertEquals("hello world", typed.undo(2).redo(2).text)
        assertEquals(TextSelection(11), typed.undo(2).redo(2).editorSelection)
    }

    @Test
    fun aNewLineStartsTheNextStep() {
        val typed = newBox().type("one\ntwo")
        assertEquals("one\n", typed.undo().text)
        assertEquals("", typed.undo(2).text)
    }

    @Test
    fun deletingIsOneStepAndAMovedCaretStartsAnother() {
        val deleted = newBox().type("abcdef").backspace(3)
        assertEquals("abc", deleted.text)
        assertEquals("abcdef", deleted.undo().text)
        assertEquals(TextSelection(6), deleted.undo().editorSelection)

        val inserted = newBox().type("abc").selectText(TextSelection(1)).type("x")
        assertEquals("axbc", inserted.text)
        assertEquals("abc", inserted.undo().text)
        assertEquals(TextSelection(1), inserted.undo().editorSelection)
    }

    @Test
    fun typingOverASelectionIsOneStepWithWhatFollows() {
        val replaced = newBox().type("abc def").selectText(TextSelection(4, 7)).type("xyz")
        assertEquals("abc xyz", replaced.text)
        assertEquals("abc def", replaced.undo().text)
        assertEquals(TextSelection(4, 7), replaced.undo().editorSelection)
    }

    @Test
    fun aCommandEndsTheTypingBeforeIt() {
        val edited = newBox().type("ab").alignSelectedText(Align.Center).type("c")
        assertEquals("ab", edited.undo().text)
        assertEquals(Align.Center, edited.undo().richText!!.blocks.first().align)
        assertEquals(Align.Start, edited.undo(2).richText!!.blocks.first().align)
        assertEquals("", edited.undo(3).text)
    }

    @Test
    fun typingAfterAnUndoDoesNotJoinTheStepBelow() {
        // Delete forward from the middle: the caret stays put, where the undo puts it back.
        val deleted = newBox().type("abcd").selectText(TextSelection(2)).delete()
        assertEquals("abd", deleted.text)
        val again = deleted.undo().delete()
        assertEquals("abd", again.text)
        assertTrue(again.structuralRedo.isEmpty(), "a new edit drops the steps that were undone")
        assertEquals("abcd", again.undo().text)
        assertEquals("", again.undo(2).text)
    }

    @Test
    fun composingJoinsTheStepItBegan() {
        val typed = newBox().type("a ")
        val composed = typed.editSelectedText("a n", TextSelection(3), TextSelection(2, 3))
            .editSelectedText("a ni", TextSelection(4), TextSelection(2, 4))
            .editSelectedText("a 你", TextSelection(3))
        assertEquals("a 你", composed.text)
        assertEquals("a ", composed.undo().text)
        assertNull(composed.undo().editorComposition)
    }

    @Test
    fun undoingTextOutsideTheBoxBeingEditedEditsItAgainWithItsSelection() {
        val box = newBox()
        val id = box.focusedTextOutlineId!!
        val typed = box.type("bold me")
        val left = typed.selectText(TextSelection(0, 4)).toggleSelectedMark(Mark.Bold).clearCanvasFocus()
        assertNull(left.focusedTextOutlineId)

        val undone = left.undoStructure()
        assertEquals(id, undone.focusedTextOutlineId)
        assertEquals(TextSelection(0, 4), undone.editorSelection)
        assertEquals(typed.firstRunMarks, undone.firstRunMarks)
        val redone = undone.clearCanvasFocus().redoStructure()
        assertEquals(id, redone.focusedTextOutlineId)
        assertEquals(typed.firstRunMarks + Mark.Bold, redone.firstRunMarks)
    }

    @Test
    fun textAndObjectStepsShareOneHistory() {
        val initial = WorkspaceState.demo()
        val id = initial.bodyTextOutline!!.id
        val moved = initial.moveTextBox(id, 80f, 0f).focusBody()
        val typed = moved.selectText(TextSelection(moved.richText!!.text.length)).replaceSelectedText(" added")

        val untyped = typed.undoStructure()
        assertFalse(untyped.selectedPage!!.body.endsWith(" added"))
        assertEquals(initial.bodyTextOutline!!.x + 80f, untyped.bodyTextOutline!!.x)
        val unmoved = untyped.undoStructure()
        assertEquals(initial.bodyTextOutline!!.x, unmoved.bodyTextOutline!!.x)
        assertEquals(initial.selectedPage!!.document, unmoved.selectedPage!!.document)

        val redone = unmoved.redoStructure().redoStructure()
        assertEquals(typed.selectedPage!!.document, redone.selectedPage!!.document)
    }

    @Test
    fun undoingTheBoxAfterItsTextRemovesTheBox() {
        val box = newBox()
        val id = box.focusedTextOutlineId!!
        val written = box.type("note")
        assertEquals("", written.undo().text)
        assertTrue(written.undo(2).selectedPage!!.document.outlines.none { it.id == id })
        assertEquals("note", written.undo(2).redo(2).text)
    }

    @Test
    fun anAbandonedBoxTakesItsTextStepsWithIt() {
        val emptied = newBox().type("x").backspace(1)
        assertTrue(emptied.structuralUndo.any { it.text != null })
        val abandoned = emptied.selectPointer()
        assertTrue(abandoned.structuralUndo.isEmpty())
        assertTrue(abandoned.structuralRedo.isEmpty())
    }

    @Test
    fun tickingAToDoIsUndone() {
        val initial = WorkspaceState.demo()
        val outline = initial.bodyTextOutline!!
        val listed = initial.focusBody().selectText(TextSelection(0)).setSelectedBlockType(BlockType.Todo).clearCanvasFocus()
        fun WorkspaceState.firstBlock() = selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
            .single { it.id == outline.id }.blocks.first()
        val ticked = listed.toggleTodo(outline.id, listed.firstBlock().id)
        assertEquals(true, ticked.firstBlock().checked)

        val unticked = ticked.undoStructure()
        assertEquals(false, unticked.firstBlock().checked)
        assertEquals(outline.id, unticked.focusedTextOutlineId)
        assertEquals(TextSelection(0), unticked.editorSelection)
        assertEquals(true, unticked.redoStructure().firstBlock().checked)
    }

    @Test
    fun aTableCellsTextIsUndone() {
        val placed = WorkspaceState.demo().setInkSettings(InkSettings(table = TableToolSettings(inkOnly = false)))
            .selectDrawPlacement(DrawPlacementTool.Table).placeDrawObject(100f, 200f)
        fun WorkspaceState.table() = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        val cell = placed.table().cellAt(0, 0)!!
        val written = placed.focusTextBox(cell.id).type("Cell")
        val typed = written.selectText(TextSelection(0, 4)).toggleSelectedMark(Mark.Italic)
        fun WorkspaceState.cellMarks() = table().cellAt(0, 0)!!.blocks.first().runs.first().marks
        assertEquals(written.cellMarks() + Mark.Italic, typed.cellMarks())

        val roman = typed.undoStructure()
        assertEquals(written.cellMarks(), roman.cellMarks())
        assertEquals("", roman.undoStructure().table().cellAt(0, 0)!!.plainText)
        assertEquals(placed.table(), roman.undoStructure().table())
    }

    @Test
    fun onlyAChangeToTheTextIsAStep() {
        val typed = newBox().type("abc")
        val steps = typed.structuralUndo.size
        assertEquals(steps, typed.selectText(TextSelection(0, 2)).structuralUndo.size)
        // Bold with nothing selected arms the next characters; the page has not changed yet.
        assertEquals(steps, typed.toggleSelectedMark(Mark.Bold).structuralUndo.size)
    }

    @Test
    fun historyKeepsItsLastHundredSteps() {
        var state = newBox().type("x")
        repeat(120) { state = state.alignSelectedText(if (it % 2 == 0) Align.Center else Align.Start) }
        assertEquals(100, state.structuralUndo.size)
    }

    @Test
    fun keystrokesJoinOnlyWithinAWordOrARunOfDeletions() {
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("ab", "abc", TextSelection(2)))
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("ab", "ab ", TextSelection(2)))
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("ab ", "ab c", TextSelection(3)))
        assertEquals(Keystroke(deleting = false, joinable = true), Keystroke.of("", "a", TextSelection(0)))
        assertEquals(Keystroke(deleting = true, joinable = true), Keystroke.of("abc", "ab", TextSelection(3)))
        assertEquals(Keystroke(deleting = true, joinable = true), Keystroke.of("abc", "bc", TextSelection(0)))
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("abc", "aXc", TextSelection(1, 2)))
        assertEquals(Keystroke(deleting = true, joinable = false), Keystroke.of("abc", "a", TextSelection(1, 3)))
        // The same length, changed: an input method's replacement, never a continuation.
        assertEquals(Keystroke(deleting = false, joinable = false), Keystroke.of("abc", "abd", TextSelection(3)))
    }
}
