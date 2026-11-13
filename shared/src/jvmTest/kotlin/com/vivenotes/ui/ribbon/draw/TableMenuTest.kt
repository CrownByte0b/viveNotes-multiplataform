package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.data.LocalTextClipboard
import com.vivenotes.data.TextClipboard
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newTable
import com.vivenotes.ui.canvas.ObjectMenuTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.selectedTableCells
import com.vivenotes.workspace.withLockGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A table's right-click menus, driven through the workspace with a mouse and the keyboard: the cell
 * menu of a typed table (Select, Insert, Delete row and column) and the object menu of a table that
 * holds only ink or is locked, which offers the same row and column commands.
 */
@OptIn(ExperimentalTestApi::class)
class TableMenuTest {
    // a1 b1 c1 / a2 b2 c2 / a3 b3 c3
    private val table = newTable(3, 3, 300f, 300f, inkOnly = false).copy(id = "table").let { grid ->
        grid.copy(rows = grid.rows.mapIndexed { r, row -> row.copy(cells = row.cells.mapIndexed { c, cell ->
            cell.copy(blocks = listOf(Block.of("${"abc"[c]}${r + 1}")))
        }) })
    }
    private fun id(row: Int, column: Int) = table.cellAt(row, column)!!.id
    private val clipboard = object : TextClipboard {
        var value: AnnotatedString? = null
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    @Test
    fun theSelectSubmenuSelectsTheCellItsRowItsColumnOrTheTableAndCopyTakesTheCells() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(table)
            setWorkspace(observed) { observed = it }

            rightClickCell(1, 1)
            // The submenu's rows are not there until it is opened.
            onNodeWithTag(TableMenuTags.SelectRow).assertDoesNotExist()
            onNodeWithTag(TableMenuTags.Select).assertTextEquals("Select").performClick()
            onNodeWithTag(TableMenuTags.SelectCell).assertTextEquals("Cell", "Ctrl+A")
            onNodeWithTag(TableMenuTags.SelectColumn).assertTextEquals("Column")
            onNodeWithTag(TableMenuTags.SelectTable).assertTextEquals("Table")
            onNodeWithTag(TableMenuTags.SelectRow).assertTextEquals("Row").performClick()

            onNodeWithTag(TableMenuTags.Select).assertDoesNotExist()
            onNodeWithTag(TableMenuTags.SelectRow).assertDoesNotExist()
            runOnIdle {
                assertEquals(setOf(id(1, 0), id(1, 1), id(1, 2)), observed.selectedTableCells(table.id))
                assertEquals(id(1, 1), observed.focusedTextOutlineId)
            }

            // A right-click inside the selected cells keeps them, and Copy takes them as cells.
            rightClickCell(1, 2)
            runOnIdle { assertEquals(3, observed.selectedTableCells(table.id).size) }
            onNodeWithTag(TableMenuTags.Select).assertExists()
            onNodeWithTag(TableMenuTags.Copy).performClick()
            runOnIdle { assertEquals("a2\tb2\tc2", clipboard.value?.text) }

            rightClickCell(0, 2)
            onNodeWithTag(TableMenuTags.Select).performClick()
            onNodeWithTag(TableMenuTags.SelectColumn).performClick()
            runOnIdle { assertEquals(setOf(id(0, 2), id(1, 2), id(2, 2)), observed.selectedTableCells(table.id)) }

            rightClickCell(2, 0)
            onNodeWithTag(TableMenuTags.Select).performClick()
            onNodeWithTag(TableMenuTags.SelectTable).performClick()
            runOnIdle {
                assertEquals(table.cellIds().toSet(), observed.selectedTableCells(table.id))
                assertEquals("a1\tb1\tc1\na2\tb2\tc2\na3\tb3\tc3", observed.selectedText)
            }

            rightClickCell(2, 0)
            onNodeWithTag(TableMenuTags.Select).performClick()
            onNodeWithTag(TableMenuTags.SelectCell).performClick()
            runOnIdle {
                assertTrue(observed.selectedTableCells(table.id).isEmpty())
                assertEquals(id(2, 0), observed.focusedTextOutlineId)
                assertEquals("a3", observed.selectedText)
            }
        }

    @Test
    fun theSubmenuOpensUnderAPointerAtRestAndClosesWhenItMovesToAnotherRowOrTheMenuCloses() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(table)
            setWorkspace(observed) { observed = it }

            rightClickCell(0, 0)
            onNodeWithTag(TableMenuTags.Select).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(400)
            onNodeWithTag(TableMenuTags.SelectRow).assertExists()

            // On to Insert: its submenu replaces Select's.
            onNodeWithTag(TableMenuTags.Insert).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(600)
            onNodeWithTag(TableMenuTags.InsertRowAbove).assertExists()
            onNodeWithTag(TableMenuTags.SelectRow).assertDoesNotExist()

            // A plain command opens nothing.
            onNodeWithTag(TableMenuTags.DeleteRow).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(600)
            onNodeWithTag(TableMenuTags.InsertRowAbove).assertDoesNotExist()

            onNodeWithTag(TableMenuTags.Insert).performClick()
            onNodeWithTag(TableMenuTags.InsertRowAbove).assertExists()
            onNodeWithTag(TableMenuTags.Insert).performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(TableMenuTags.Insert).assertDoesNotExist()
            onNodeWithTag(TableMenuTags.InsertRowAbove).assertDoesNotExist()
            runOnIdle { assertEquals(table, observed.grid) }
        }

    @Test
    fun insertAndDeleteActOnTheRowAndColumnOfTheCellRightClicked() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(table)
            setWorkspace(observed) { observed = it }

            rightClickCell(1, 1)
            onNodeWithTag(TableMenuTags.Insert).assertTextEquals("Insert").performClick()
            onNodeWithTag(TableMenuTags.InsertRowBelow).assertTextEquals("Row below")
            onNodeWithTag(TableMenuTags.InsertColumnLeft).assertTextEquals("Column left")
            onNodeWithTag(TableMenuTags.InsertColumnRight).assertTextEquals("Column right")
            onNodeWithTag(TableMenuTags.InsertRowAbove).assertTextEquals("Row above").performClick()
            runOnIdle {
                assertEquals(4, observed.grid.rowCount)
                assertEquals(id(1, 1), observed.grid.cellAt(2, 1)!!.id, "the new row is above the cell's")
                assertEquals(id(1, 1), observed.focusedTextOutlineId)
            }

            rightClickCell(1, 1)
            onNodeWithTag(TableMenuTags.Insert).performClick()
            onNodeWithTag(TableMenuTags.InsertColumnRight).performClick()
            runOnIdle {
                assertEquals(4, observed.grid.columnCount)
                assertEquals(id(1, 2), observed.grid.cellAt(2, 3)!!.id, "the new column is right of the cell's")
            }

            rightClickCell(1, 1)
            onNodeWithTag(TableMenuTags.DeleteColumn).assertTextEquals("Delete column").performClick()
            runOnIdle {
                assertEquals(3, observed.grid.columnCount)
                // The cell's own column went; the one inserted beside it stays between its neighbours.
                assertEquals(listOf(id(0, 0), id(0, 2)), observed.grid.rows.first().cells.map { it.id }.filter { it in table.cellIds() })
                assertTrue(table.cellIds().none { it == id(1, 1) && it in observed.grid.cellIds() })
                assertEquals(observed.grid.cellAt(2, 1)!!.id, observed.focusedTextOutlineId, "the caret takes the cell in its place")
            }

            onNodeWithTag(TableTags.cell(id(2, 0)), useUnmergedTree = true).performMouseInput { rightClick(center) }
            onNodeWithTag(TableMenuTags.DeleteRow).assertTextEquals("Delete row").performClick()
            runOnIdle {
                assertEquals(3, observed.grid.rowCount)
                assertTrue(observed.grid.cellIds().none { it == id(2, 0) })
            }
            // Each command was one undo step.
            repeat(4) { onNodeWithTag(WorkspaceTestTags.CanvasExtent).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } } }
            runOnIdle { assertEquals(table, observed.grid) }
        }

    @Test
    fun theLastRowAndColumnAreNotDeletedAndAFullTableTakesNoMore() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val single = newTable(Outline.Table.MAX_COLUMNS, 1, 100f, 300f, columnWidth = 80f, inkOnly = false).copy(id = "single")
            var observed = workspace(single)
            setWorkspace(observed) { observed = it }

            onNodeWithTag(TableTags.cell(single.cellAt(0, 0)!!.id), useUnmergedTree = true).performMouseInput { rightClick(center) }
            onNodeWithTag(TableMenuTags.DeleteRow).assertIsNotEnabled()
            onNodeWithTag(TableMenuTags.DeleteColumn).assertIsEnabled()
            onNodeWithTag(TableMenuTags.Insert).performClick()
            onNodeWithTag(TableMenuTags.InsertColumnLeft).assertIsNotEnabled()
            onNodeWithTag(TableMenuTags.InsertColumnRight).assertIsNotEnabled()
            onNodeWithTag(TableMenuTags.InsertRowBelow).assertIsEnabled()
        }

    /** The Draw tool's own table holds only ink: its object menu carries the row and column commands. */
    @Test
    fun anInkOnlyOrLockedTablesObjectMenuActsOnTheCellUnderThePointer() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val ink = newTable(3, 3, 300f, 300f, inkOnly = true).copy(id = "ink")
            var observed = workspace(ink)
            setWorkspace(observed) { observed = it }
            val middleRow = ink.rows[1].id

            rightClickGrid(ink, row = 1, column = 2)
            runOnIdle { assertEquals(setOf(ink.id), observed.selectedObjectIds) }
            onNodeWithTag(ObjectMenuTags.Cut).assertExists()
            onNodeWithTag(TableMenuTags.Select).assertDoesNotExist()
            onNodeWithTag(TableMenuTags.Insert).performClick()
            onNodeWithTag(TableMenuTags.InsertRowBelow).performClick()
            onNodeWithTag(ObjectMenuTags.Cut).assertDoesNotExist()
            runOnIdle {
                assertEquals(4, observed.grid.rowCount)
                assertEquals(middleRow, observed.grid.rows[1].id)
                assertTrue(observed.grid.rows[2].id !in ink.rows.map { it.id }, "the new row is below the one clicked")
            }

            rightClickGrid(ink, row = 0, column = 2)
            onNodeWithTag(TableMenuTags.DeleteColumn).performClick()
            runOnIdle {
                assertEquals(2, observed.grid.columnCount)
                assertEquals(ink.rows.first().cells.take(2).map { it.id }, observed.grid.rows.first().cells.map { it.id })
            }

            rightClickGrid(ink, row = 0, column = 0)
            onNodeWithTag(TableMenuTags.DeleteRow).performClick()
            runOnIdle { assertEquals(listOf(middleRow), observed.grid.rows.take(1).map { it.id }) }
        }

    @Test
    fun aLockedTypedTableOffersItsRowsAndColumnsFromTheObjectMenuToo() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val locked = table.withLockGroup("pinned") as Outline.Table
            var observed = workspace(locked)
            setWorkspace(observed) { observed = it }

            rightClickGrid(locked, row = 2, column = 0)
            onNodeWithTag(ObjectMenuTags.Cut).assertExists()
            onNodeWithTag(TableMenuTags.Insert).performClick()
            onNodeWithTag(TableMenuTags.InsertColumnLeft).performClick()
            runOnIdle {
                assertEquals(4, observed.grid.columnCount)
                assertEquals(id(2, 0), observed.grid.cellAt(2, 1)!!.id)
                assertEquals("pinned", observed.grid.lockGroup)
            }
        }

    @Test
    fun selectAllFromTheKeyboardTakesTheCellAndThenTheTable() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = workspace(table)
            setWorkspace(observed) { observed = it }

            // The first click holds the table, the second types in the cell.
            onNodeWithTag(TableTags.cell(id(1, 1)), useUnmergedTree = true).performMouseInput { click(center) }
            onNodeWithTag(TableTags.cell(id(1, 1)), useUnmergedTree = true).performMouseInput { click(center) }
            runOnIdle { assertEquals(id(1, 1), observed.focusedTextOutlineId) }
            onNodeWithTag(TableTags.cell(id(1, 1)), useUnmergedTree = true).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
            runOnIdle {
                assertEquals("b2", observed.selectedText)
                assertTrue(observed.selectedTableCells(table.id).isEmpty())
            }
            onNodeWithTag(TableTags.cell(id(1, 1)), useUnmergedTree = true).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
            runOnIdle { assertEquals(table.cellIds().toSet(), observed.selectedTableCells(table.id)) }

            // Delete clears every selected cell, and a click lets the cells go.
            onNodeWithTag(TableTags.cell(id(1, 1)), useUnmergedTree = true).performKeyInput { pressKey(Key.Delete) }
            runOnIdle { assertTrue(observed.grid.rows.all { row -> row.cells.all { it.plainText.isEmpty() } }) }
            onNodeWithTag(TableTags.cell(id(0, 0)), useUnmergedTree = true).performMouseInput { click(center) }
            runOnIdle {
                assertTrue(observed.selectedTableCells(table.id).isEmpty())
                assertNull(observed.wordSelections)
            }
        }

    private val WorkspaceState.grid: Outline.Table
        get() = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()

    private fun ComposeUiTest.rightClickCell(row: Int, column: Int) =
        onNodeWithTag(TableTags.cell(id(row, column)), useUnmergedTree = true).performMouseInput { rightClick(center) }

    /** A right-click in the middle of a cell of [grid] as it was placed: rows and columns of one size. */
    private fun ComposeUiTest.rightClickGrid(grid: Outline.Table, row: Int, column: Int) {
        val node = onNodeWithTag(WorkspaceTestTags.primeObject(grid.id))
        val box = node.fetchSemanticsNode().size
        node.performMouseInput {
            rightClick(Offset(box.width * (column + .5f) / grid.columnCount, box.height * (row + .5f) / grid.rowCount))
        }
    }

    private fun workspace(grid: Outline.Table): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(grid))) }.clearCanvasFocus()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme {
                CompositionLocalProvider(LocalTextClipboard provides clipboard) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                }
            }
        }
    }
}
