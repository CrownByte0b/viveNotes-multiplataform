package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newTable
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A table's Select commands: a row, a column or the whole table, selected cell by cell. */
class TableSelectionsTest {
    // a1 b1 c1 / a2 (empty) c2 / a3 b3 c3
    private val table = newTable(3, 3, x = 100f, y = 200f, inkOnly = false).copy(id = "table").let { grid ->
        grid.copy(rows = grid.rows.mapIndexed { r, row -> row.copy(cells = row.cells.mapIndexed { c, cell ->
            if (r == 1 && c == 1) cell else cell.copy(blocks = listOf(Block.of("${"abc"[c]}${r + 1}")))
        }) })
    }
    private fun id(row: Int, column: Int) = table.cellAt(row, column)!!.id

    private fun workspace(grid: Outline.Table = table): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(grid))) }.clearCanvasFocus()
    }

    private val WorkspaceState.grid: Outline.Table
        get() = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
    private fun WorkspaceState.text(row: Int, column: Int) = grid.cellAt(row, column)!!.plainText

    @Test
    fun aRowAColumnAndTheTableSelectAllTheTextOfTheirCells() {
        val row = workspace().selectTableCells(id(0, 1), TableSelectionScope.Row)
        assertEquals(setOf(id(0, 0), id(0, 1), id(0, 2)), row.selectedTableCells(table.id))
        assertEquals(id(0, 1), row.focusedTextOutlineId, "the caret stays in the cell asked from")
        (0..2).forEach { assertEquals(listOf(TextSelection(0, 2)), row.textSelectionsFor(id(0, it))) }
        assertEquals("a1\tb1\tc1", row.selectedText)

        val column = workspace().selectTableCells(id(2, 0), TableSelectionScope.Column)
        assertEquals(setOf(id(0, 0), id(1, 0), id(2, 0)), column.selectedTableCells(table.id))
        assertEquals("a1\na2\na3", column.selectedText)

        val all = workspace().selectTableCells(id(0, 0), TableSelectionScope.Table)
        assertEquals(table.cellIds().toSet(), all.selectedTableCells(table.id))
        assertEquals("a1\tb1\tc1\na2\t\tc2\na3\tb3\tc3", all.selectedText, "an empty cell keeps its place")
    }

    @Test
    fun anEmptyCellIsSelectedAsACellAndHandsTheCaretToOneWithText() {
        val row = workspace().selectTableCells(id(1, 1), TableSelectionScope.Row)
        assertTrue(id(1, 1) in row.selectedTableCells(table.id))
        assertEquals(id(1, 2), row.focusedTextOutlineId)
        assertEquals(TextSelection(0, 2), row.editorSelection)
        assertEquals("a2\t\tc2", row.selectedText)

        // Nothing but empty cells: still a selection of cells, with nothing to copy.
        val blank = newTable(2, 2, inkOnly = false).copy(id = "blank")
        val cell = blank.cellAt(1, 0)!!.id
        val empty = workspace(blank).selectTableCells(cell, TableSelectionScope.Table)
        assertEquals(blank.cellIds().toSet(), empty.selectedTableCells(blank.id))
        assertEquals(cell, empty.focusedTextOutlineId)
        assertEquals("", empty.selectedText)
    }

    @Test
    fun whatEditsASelectionEditsEverySelectedCellAsOneUndo() {
        val row = workspace().selectTableCells(id(0, 0), TableSelectionScope.Row)
        val bold = row.toggleSelectedMark(Mark.Bold)
        (0..2).forEach { column ->
            val marks = RichTextBuffer(bold.grid.cellAt(0, column)!!.blocks).select(TextSelection(0, 2)).activeMarks
            assertTrue(Mark.Bold in marks, "column $column")
        }
        assertTrue(Mark.Bold !in RichTextBuffer(bold.grid.cellAt(1, 0)!!.blocks).select(TextSelection(0, 2)).activeMarks)
        assertEquals(row.selectedTableCells(table.id), bold.selectedTableCells(table.id), "the cells stay selected")

        val cleared = row.replaceSelectedText("")
        assertEquals(listOf("", "", ""), (0..2).map { cleared.text(0, it) })
        assertEquals("a2", cleared.text(1, 0))
        assertEquals(row.structuralUndo.size + 1, cleared.structuralUndo.size)
        assertEquals(listOf("a1", "b1", "c1"), cleared.undoStructure().let { undone -> (0..2).map { undone.text(0, it) } })
        // Delete, as the focused cell's editor reports it: its own text gone.
        val deleted = row.editSelectedText("", TextSelection(0))
        assertEquals(listOf("", "", ""), (0..2).map { deleted.text(0, it) })
    }

    @Test
    fun aPlainCaretLetsTheCellsGoAndLockedOrInkOnlyTablesHaveNothingToSelect() {
        val row = workspace().selectTableCells(id(0, 0), TableSelectionScope.Row)
        assertTrue(row.selectText(TextSelection(1)).selectedTableCells(table.id).isEmpty())
        assertTrue(row.focusTextBox(id(2, 2)).selectedTableCells(table.id).isEmpty())

        val locked = workspace(table.copy(lockGroup = "lock"))
        assertSame(locked, locked.selectTableCells(id(0, 0), TableSelectionScope.Row))
        val ink = newTable(2, 2, inkOnly = true).copy(id = "ink")
        val inked = workspace(ink)
        assertSame(inked, inked.selectTableCells(ink.cellAt(0, 0)!!.id, TableSelectionScope.Table))
    }

    @Test
    fun selectAllTakesTheCellThenTheTable() {
        val caret = workspace().focusTextBox(id(0, 0)).selectText(TextSelection(1))
        val cell = caret.selectAllFrom(id(0, 0))
        assertEquals(TextSelection(0, 2), cell.editorSelection)
        assertTrue(cell.selectedTableCells(table.id).isEmpty())
        val all = cell.selectAllFrom(id(0, 0))
        assertEquals(table.cellIds().toSet(), all.selectedTableCells(table.id))
        assertSame(all, all.selectAllFrom(all.focusedTextOutlineId!!), "a selected table stays selected")
    }

    @Test
    fun deletingTheRowOrColumnBeingTypedInMovesTheCaretToTheCellInItsPlace() {
        val typing = workspace().focusTextBox(id(1, 2))
        val rowGone = typing.editTable(TableEdit.DeleteRow(table.id, 1))
        assertEquals(2, rowGone.grid.rowCount)
        assertEquals(id(2, 2), rowGone.focusedTextOutlineId, "the row below moved up into its place")

        val last = workspace().focusTextBox(id(2, 2)).editTable(TableEdit.DeleteColumn(table.id, 2))
        assertEquals(id(2, 1), last.focusedTextOutlineId, "the last column's caret goes to the new last one")

        // The caret elsewhere is left where it is, and an insert moves nothing.
        assertEquals(id(0, 0), workspace().focusTextBox(id(0, 0)).editTable(TableEdit.DeleteRow(table.id, 2)).focusedTextOutlineId)
        val inserted = typing.editTable(TableEdit.InsertRow(table.id, 1))
        assertEquals(id(1, 2), inserted.focusedTextOutlineId)
        assertEquals(id(1, 2), inserted.grid.cellAt(2, 2)!!.id, "inserting above pushes the row down")
        assertNull(rowGone.wordSelections)
    }
}
