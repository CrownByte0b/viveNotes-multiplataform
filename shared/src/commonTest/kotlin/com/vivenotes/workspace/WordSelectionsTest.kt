package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.decodePageDoc
import com.vivenotes.model.encode
import com.vivenotes.model.newTable
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordSelectionsTest {
    private val table = newTable(2, 1).let { grid -> grid.copy(rows = grid.rows.map { row ->
        row.copy(cells = row.cells.mapIndexed { index, cell ->
            cell.copy(blocks = listOf(Block.of(if (index == 0) "alpha beta" else "gamma omega")))
        })
    }) }
    private val left = table.cellAt(0, 0)!!.id
    private val right = table.cellAt(0, 1)!!.id
    private fun state(): WorkspaceState = WorkspaceState.demo().let { state ->
        state.updatePage(state.selectedPageId) { page -> page.copy(document = page.document.copy(outlines =
            listOf(state.bodyTextOutline!!.copy(blocks = listOf(Block.of("one middle three"))), table))) }
    }.clearCanvasFocus()
    private fun WorkspaceState.twoCells() = addWordSelection(right, TextSelection(6, 11)).addWordSelection(left, TextSelection(0, 5))
    private fun WorkspaceState.cell(id: String) = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        .rows.flatMap { it.cells }.single { it.id == id }
    private fun WorkspaceState.marks(id: String, start: Int, end: Int) =
        RichTextBuffer(cell(id).blocks).select(TextSelection(start, end)).activeMarks

    @Test
    fun wordsInDifferentCellsStaySelectedAndCopyInReadingOrder() {
        val selected = state().twoCells()
        assertEquals(listOf(TextSelection(0, 5)), selected.textSelectionsFor(left))
        assertEquals(listOf(TextSelection(6, 11)), selected.textSelectionsFor(right))
        assertEquals("alpha\nomega", selected.selectedText)
        val copied = selected.copySelectedText().textClipboard!!
        assertEquals("alpha\nomega", copied.text)
        assertEquals(listOf("alpha", "omega"), copied.blocks.map { it.text })
    }

    @Test
    fun ctrlDoubleClickAddsToANormalSelectionAndRepeatingAWordRemovesIt() {
        val initial = state()
        val id = initial.bodyTextOutline!!.id
        val selected = initial.focusTextBox(id).selectText(TextSelection(0, 3)).addWordSelection(id, TextSelection(11, 16))
        assertEquals("one\nthree", selected.selectedText)
        val one = selected.addWordSelection(id, TextSelection(0, 3))
        assertEquals("three", one.selectedText)
        val none = one.addWordSelection(id, TextSelection(11, 16))
        assertNull(none.wordSelections)
        assertTrue(none.editorSelection.collapsed)
    }

    @Test
    fun formattingOnlyChangesTheSelectedWordsAcrossCellsAsOneUndoStep() {
        val selected = state().twoCells()
        val bold = selected.toggleSelectedMark(Mark.Bold)
        assertTrue(Mark.Bold in bold.marks(left, 0, 5))
        assertFalse(Mark.Bold in bold.marks(left, 6, 10))
        assertFalse(Mark.Bold in bold.marks(right, 0, 5))
        assertTrue(Mark.Bold in bold.marks(right, 6, 11))
        assertEquals(1, bold.structuralUndo.size)
        val undone = bold.undoStructure()
        assertEquals(selected.selectedPage!!.document, undone.selectedPage!!.document)
        assertEquals(selected.wordSelections, undone.wordSelections)
        val redone = undone.redoStructure()
        assertEquals(bold.selectedPage!!.document, redone.selectedPage!!.document)
        assertEquals(bold.wordSelections, redone.wordSelections)
        assertEquals(bold.selectedPage!!.document, decodePageDoc(bold.selectedPage!!.document.encode()))
        val plain = bold.toggleSelectedMark(Mark.Bold)
        assertFalse(Mark.Bold in plain.marks(left, 0, 5))
        assertFalse(Mark.Bold in plain.marks(right, 6, 11))
    }

    @Test
    fun mixedMarksAreAddedConsistentlyAcrossEveryRange() {
        val partial = state().focusTextBox(left).selectText(TextSelection(0, 5)).toggleSelectedMark(Mark.Bold)
            .addWordSelection(left, TextSelection(0, 5)).addWordSelection(right, TextSelection(6, 11))
        assertFalse(Mark.Bold in partial.selectedTextMarks)
        val bold = partial.toggleSelectedMark(Mark.Bold)
        assertTrue(Mark.Bold in bold.selectedTextMarks)
        assertTrue(Mark.Bold in bold.marks(left, 0, 5))
        assertTrue(Mark.Bold in bold.marks(right, 6, 11))
    }

    @Test
    fun paragraphCommandsApplyOncePerParagraphDespiteSeveralSelectedWords() {
        val initial = state()
        val id = initial.bodyTextOutline!!.id
        val selected = initial.addWordSelection(id, TextSelection(0, 3)).addWordSelection(id, TextSelection(11, 16))
        val indented = selected.indentSelectedText(1)
        assertEquals(1, indented.bodyTextOutline!!.blocks.single().indent)
        val bullet = indented.setSelectedBlockType(BlockType.Bullet)
        assertEquals(BlockType.Bullet, bullet.bodyTextOutline!!.blocks.single().type)
        assertEquals(BlockType.Paragraph, bullet.setSelectedBlockType(BlockType.Bullet).bodyTextOutline!!.blocks.single().type)
    }

    @Test
    fun typingReplacesAllWordsAndKeepsAllCaretsForTheNextCharacter() {
        val initial = state()
        val id = initial.bodyTextOutline!!.id
        val selected = initial.addWordSelection(id, TextSelection(0, 3)).addWordSelection(id, TextSelection(11, 16))
        val typed = selected.editSelectedText("one middle x", TextSelection(12))
        assertEquals("x middle x", typed.richText!!.text)
        assertEquals(listOf(TextSelection(1), TextSelection(10)), typed.textSelectionsFor(id))
        val continued = typed.editSelectedText("x middle xy", TextSelection(11))
        assertEquals("xy middle xy", continued.richText!!.text)
        val deleted = continued.editSelectedText("xy middle x", TextSelection(11))
        assertEquals("x middle x", deleted.richText!!.text)
        assertEquals(1, typed.structuralUndo.size)
        assertEquals(selected.selectedPage!!.document, typed.undoStructure().selectedPage!!.document)
    }

    @Test
    fun cuttingAndPastingAffectOnlyTheSelectedWordsAcrossCells() {
        val selected = state().twoCells()
        val cut = selected.copySelectedText().replaceSelectedText("")
        assertEquals(" beta", cut.cell(left).plainText)
        assertEquals("gamma ", cut.cell(right).plainText)
        assertEquals(1, cut.structuralUndo.size)
        val pasted = selected.pasteText("new", false)
        assertEquals("new beta", pasted.cell(left).plainText)
        assertEquals("gamma new", pasted.cell(right).plainText)
        assertEquals(1, pasted.structuralUndo.size)
    }

    @Test
    fun formattingAtMultipleCaretsAppliesToTheNextTypedCharacters() {
        val carets = state().twoCells().replaceSelectedText("x").toggleSelectedMark(Mark.Bold)
        val typed = carets.editSelectedText("xy beta", TextSelection(2))
        assertEquals("xy beta", typed.cell(left).plainText)
        assertEquals("gamma xy", typed.cell(right).plainText)
        assertTrue(Mark.Bold in typed.marks(left, 1, 2))
        assertTrue(Mark.Bold in typed.marks(right, 7, 8))
    }

    @Test
    fun ordinarySelectionFocusPageChangesAndLockedTablesClearOrRejectExtraRanges() {
        val selected = state().twoCells()
        assertNull(selected.selectText(TextSelection(2)).wordSelections)
        assertNull(selected.focusTextBox(selected.bodyTextOutline!!.id).wordSelections)
        assertNull(selected.clearCanvasFocus().wordSelections)
        assertNull(selected.selectPointer().wordSelections)
        assertNull(selected.addPage().wordSelections)
        val locked = state().updatePage(selected.selectedPageId) { page -> page.copy(document = page.document.copy(
            outlines = page.document.outlines.map { if (it is Outline.Table) it.copy(lockGroup = "locked") else it })) }
        assertNull(locked.addWordSelection(left, TextSelection(0, 5)).wordSelections)
        val readOnly = state().updatePage(selected.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertNull(readOnly.addWordSelection(left, TextSelection(0, 5)).wordSelections)
    }
}
