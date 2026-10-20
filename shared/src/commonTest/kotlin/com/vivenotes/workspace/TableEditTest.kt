package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.ui.canvas.selectedCanvasBounds
import com.vivenotes.ui.ribbon.draw.TableAxis
import com.vivenotes.ui.ribbon.draw.heldIn
import kotlin.test.*

class TableEditTest {
    private val table = newTable(3, 3, x = 100f, y = 200f, inkOnly = false).copy(id = "table")

    private fun workspace(vararg outlines: Outline): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines.toList())) }
    }

    private val WorkspaceState.grid: Outline.Table
        get() = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()

    @Test
    fun rowsAndColumnsInsertAfterAndDeleteAtTheGivenIndexEachAsOneUndo() {
        val typed = workspace(table).selectObject(table.id).editTable(TableEdit.InsertRow(table.id, 0))
            .let { state ->
                val cell = state.grid.cellAt(0, 0)!!
                state.updateTableCell(table.id, 0, 0, "kept").also { assertEquals(cell.id, it.grid.cellAt(0, 0)!!.id) }
            }
        val below = typed.editTable(TableEdit.InsertRow(table.id, 1))
        assertEquals(5, below.grid.rowCount)
        assertEquals("kept", below.grid.cellAt(0, 0)!!.plainText)
        assertTrue(below.grid.rows[1].cells.all { it.plainText.isEmpty() && it.blocks.size == 1 })
        val right = below.editTable(TableEdit.InsertColumn(table.id, 1))
        assertEquals(4, right.grid.columnCount)
        assertEquals("kept", right.grid.cellAt(0, 0)!!.plainText)
        val deleted = right.editTable(TableEdit.DeleteRow(table.id, 0)).editTable(TableEdit.DeleteColumn(table.id, 0))
        assertEquals(4, deleted.grid.rowCount)
        assertEquals(3, deleted.grid.columnCount)
        assertTrue(deleted.grid.cellIds().none { it == right.grid.cellAt(0, 0)!!.id })
        assertEquals(right.structuralUndo.size + 2, deleted.structuralUndo.size)
        assertEquals(right.grid, deleted.undoStructure().undoStructure().grid)
    }

    @Test
    fun theLastRowAndColumnCannotBeDeletedAndARefusedEditLeavesNoHistory() {
        val single = newTable(1, 1, x = 100f, y = 200f).copy(id = "single")
        val state = workspace(single).selectObject(single.id)
        assertSame(state, state.editTable(TableEdit.DeleteRow(single.id, 0)))
        assertSame(state, state.editTable(TableEdit.DeleteColumn(single.id, 0)))
        val wide = newTable(Outline.Table.MAX_COLUMNS, 1, x = 100f, y = 200f).copy(id = "wide")
        val full = workspace(wide).selectObject(wide.id)
        assertSame(full, full.editTable(TableEdit.InsertColumn(wide.id, wide.columnCount)))
    }

    @Test
    fun aHandleDragIsOneUndoAndItsWidthAndFloorAreClampedToTheTableLimits() {
        val state = workspace(table).selectObject(table.id)
        val start = state.editTable(TableEdit.ColumnWidth(table.id, 1, 200f, recordHistory = true))
        val dragged = start.editTable(TableEdit.ColumnWidth(table.id, 1, 260f, recordHistory = false))
            .editTable(TableEdit.ColumnWidth(table.id, 1, 5f, recordHistory = false))
        assertEquals(Outline.Table.MIN_COLUMN_WIDTH, dragged.grid.columns[1])
        assertEquals(dragged.grid.columns.sum(), dragged.grid.width)
        assertEquals(state.structuralUndo.size + 1, dragged.structuralUndo.size)
        assertEquals(table, dragged.undoStructure().grid)

        val taller = state.editTable(TableEdit.RowMinHeight(table.id, 2, 90f))
            .editTable(TableEdit.RowMinHeight(table.id, 2, 5000f, recordHistory = false))
        assertEquals(Outline.Table.MAX_ROW_HEIGHT, taller.grid.rows[2].minHeight)
        assertEquals(table.rows[1], taller.grid.rows[1])
        assertEquals(table, taller.undoStructure().grid)
    }

    @Test
    fun aLockedTableKeepsItsSizeButStillTakesRowsColumnsBorderAndFill() {
        val locked = table.copy(lockGroup = "lock")
        val state = workspace(locked).selectObject(locked.id)
        assertSame(state, state.editTable(TableEdit.ColumnWidth(locked.id, 0, 300f)))
        assertSame(state, state.editTable(TableEdit.RowMinHeight(locked.id, 0, 300f)))
        val edited = state.editTable(TableEdit.InsertRow(locked.id, 3)).editTable(TableEdit.InsertColumn(locked.id, 3))
            .editTable(TableEdit.BorderWidth(locked.id, 4)).editTable(TableEdit.Fill(locked.id, 0xff336699.toInt()))
        assertEquals(4, edited.grid.rowCount)
        assertEquals(4, edited.grid.columnCount)
        assertEquals(4f, edited.grid.borderWidth)
        assertEquals(0xff336699.toInt(), edited.grid.fillArgb)
        assertEquals("lock", edited.grid.lockGroup)
        assertEquals(locked.x, edited.grid.x)
    }

    @Test
    fun borderWidthIsClampedFillClearsToNoneAndHeadersToggle() {
        val state = workspace(table).selectObject(table.id)
        assertEquals(8f, state.editTable(TableEdit.BorderWidth(table.id, 40)).grid.borderWidth)
        assertEquals(1f, state.editTable(TableEdit.BorderWidth(table.id, 0)).grid.borderWidth)
        val filled = state.editTable(TableEdit.Fill(table.id, 0xffff0000.toInt()))
        assertNull(filled.editTable(TableEdit.Fill(table.id, null)).grid.fillArgb)
        val headers = state.editTable(TableEdit.HeaderRow(table.id, true)).editTable(TableEdit.HeaderColumn(table.id, true))
        assertTrue(headers.grid.headerRow && headers.grid.headerColumn)
        assertFalse(headers.editTable(TableEdit.HeaderRow(table.id, false)).grid.headerRow)
    }

    @Test
    fun aReadOnlyPageRefusesEveryTableEdit() {
        val state = workspace(table).selectObject(table.id)
        val readOnly = state.updatePage(state.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        listOf(TableEdit.InsertRow(table.id, 0), TableEdit.DeleteColumn(table.id, 0), TableEdit.ColumnWidth(table.id, 0, 300f),
            TableEdit.Fill(table.id, null), TableEdit.BorderWidth(table.id, 3)).forEach { edit ->
            assertSame(readOnly, readOnly.editTable(edit), "$edit")
        }
    }

    @Test
    fun debugLinesNameTheOperationOncePerDragAndNeverTheCellText() {
        assertEquals("insert row at=2", TableEdit.InsertRow("t", 2).logLine)
        assertEquals("delete column at=0", TableEdit.DeleteColumn("t", 0).logLine)
        assertEquals("resize column=1", TableEdit.ColumnWidth("t", 1, 90f, recordHistory = true).logLine)
        assertNull(TableEdit.ColumnWidth("t", 1, 95f, recordHistory = false).logLine)
        assertNull(TableEdit.RowMinHeight("t", 0, 95f, recordHistory = false).logLine)
        assertEquals("fill none", TableEdit.Fill("t", null).logLine)
        assertEquals("fill set", TableEdit.Fill("t", 0xff123456.toInt()).logLine)
    }

    @Test
    fun theTablesBoxIncludesItsHandleGutterAsOnAndroid() {
        assertEquals(TABLE_GUTTER + table.width, table.primeWidth())
        assertEquals(TABLE_GUTTER + table.height, table.primeHeight())
        // A marquee over the gutter alone, left of and above the grid, still catches the table.
        val state = workspace(table)
        assertEquals(setOf(table.id), state.selectObjectsInRect(95f, 195f, 112f, 212f).selectedObjectIds)
        val equation = Outline.Equation("equation", 600f, 200f, 40f, 30f, "x")
        val bounds = selectedCanvasBounds(listOf(table, equation), setOf(table.id, equation.id))!!
        assertEquals(table.x, bounds.left)
        assertEquals(table.y, bounds.top)
        assertEquals(table.y + TABLE_GUTTER + table.height, bounds.bottom)
    }

    @Test
    fun aHoldLastsOnlyWhileItsTableIsTheWholeSelectionAndStillHasThatRowOrColumn() {
        val other = Outline.Equation("equation", 600f, 200f, 40f, 30f, "x")
        val state = workspace(table, other).selectObject(table.id)
        val row = TableAxis.Row(table.id, 2)
        val column = TableAxis.Column(table.id, 1)
        assertEquals(row, row.heldIn(state))
        assertEquals(column, column.heldIn(state))
        assertNull(null.heldIn(state))
        assertNull(TableAxis.Row(table.id, 3).heldIn(state))
        assertNull(TableAxis.Row(table.id, 2).heldIn(state.editTable(TableEdit.DeleteRow(table.id, 0))))
        assertNull(column.heldIn(state.selectObject(other.id)))
        assertNull(column.heldIn(state.selectObjectsInRect(0f, 0f, 2000f, 2000f)))
        assertNull(column.heldIn(state.clearCanvasFocus()))
        assertNull(column.heldIn(state.focusTextBox(table.cellAt(0, 0)!!.id)))
        assertNull(TableAxis.Row("missing", 0).heldIn(state))
    }
}
