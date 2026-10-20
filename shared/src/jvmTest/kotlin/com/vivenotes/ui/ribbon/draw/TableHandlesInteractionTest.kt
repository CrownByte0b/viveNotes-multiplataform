package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.*
import com.vivenotes.ui.canvas.SelectionColorTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TableHandlesInteractionTest {
    private val table = newTable(3, 3, x = 120f, y = 260f, inkOnly = false, headerRow = true).copy(id = "table")

    private fun workspace(vararg outlines: Outline): WorkspaceState {
        val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = outlines.toList())) }
    }

    private val WorkspaceState.grid: Outline.Table
        get() = selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun close(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1.5f, "$what: expected $expected, was $actual")

    @Test
    fun aSelectedTableHasAHandleForEveryColumnAndRowInItsGutterAndOneScaleCorner() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val grid = bounds(WorkspaceTestTags.primeObject(table.id))
            val scale = grid.width / table.width
            val gutter = TABLE_GUTTER * scale
            var left = grid.left
            table.columns.forEachIndexed { index, width ->
                val handle = bounds(TableTags.columnHandle(table.id, index))
                close(left, handle.left, "column $index left")
                close(width * scale, handle.width, "column $index width")
                close(grid.top, handle.bottom, "column $index sits on the grid")
                close(gutter, handle.height, "column $index fills the gutter")
                left += width * scale
            }
            var top = grid.top
            state.value.grid.rows.indices.forEach { index ->
                val handle = bounds(TableTags.rowHandle(table.id, index))
                close(top, handle.top, "row $index top")
                close(grid.left, handle.right, "row $index sits beside the grid")
                close(gutter, handle.width, "row $index fills the gutter")
                top += handle.height
            }
            close(grid.bottom, top, "rows span the grid")
            val grip = bounds(TableTags.Move)
            close(grid.left, grip.right, "grip right"); close(grid.top, grip.bottom, "grip bottom")
            close(TableGripSize.value * scale, grip.width, "grip width")
            close(TableGripSize.value * scale, grip.height, "grip height")
            assertTrue(grip.width > gutter, "grip larger than the gutter")
            assertTrue(grip.right <= bounds(TableTags.columnHandle(table.id, 0)).left + 0.5f, "grip clear of the first column bar")
            assertTrue(grip.bottom <= bounds(TableTags.rowHandle(table.id, 0)).top + 0.5f, "grip clear of the first row bar")
            val corner = bounds(WorkspaceTestTags.objectCorner(table.id, 3))
            close(grid.right, corner.center.x, "scale corner x"); close(grid.bottom, corner.center.y, "scale corner y")
            (0..2).forEach { onNodeWithTag(WorkspaceTestTags.objectCorner(table.id, it)).assertDoesNotExist() }
            onNodeWithTag(TableTags.SelectionOutline).assertIsDisplayed()

            // The reference toolkit: colour | copy, lock, border width, fill, rows, columns, delete.
            val order = listOf(WorkspaceTestTags.ObjectColor, WorkspaceTestTags.ObjectColorDivider, WorkspaceTestTags.ObjectCopy,
                WorkspaceTestTags.ObjectLock, TableTags.BorderWidth, TableTags.ObjectFill, TableTags.RowMenu,
                TableTags.ColumnMenu, WorkspaceTestTags.ObjectDelete).map { bounds(it).left }
            assertEquals(order.sorted(), order, "toolkit order $order")
            val toolkit = bounds(WorkspaceTestTags.ObjectToolkit)
            close((grid.left - gutter + grid.right) / 2f, toolkit.center.x, "toolkit centred over the table")
            assertTrue(toolkit.bottom < grip.top, "toolkit above the gutter")
            save(onRoot(), "table-selected.png")

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(bottomRight - Offset(120f, 120f)) }
            runOnIdle { assertTrue(state.value.selectedObjectIds.isEmpty()) }
            onNodeWithTag(TableTags.columnHandle(table.id, 0)).assertDoesNotExist()
            onNodeWithTag(TableTags.rowHandle(table.id, 0)).assertDoesNotExist()
            onNodeWithTag(TableTags.Move).assertDoesNotExist()
            // Unselected, the grid stays inside its gutter rather than shifting into it.
            close(grid.left, bounds(WorkspaceTestTags.primeObject(table.id)).left, "grid does not move on deselection")
        }

    @Test
    fun clickingARowOrColumnHandleHoldsItAndTheToolkitActsOnThatRowOrColumn() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(TableTags.RowMenu).assertIsDisplayed()
            onNodeWithTag(TableTags.RowBelow).assertDoesNotExist()

            onNodeWithTag(TableTags.rowHandle(table.id, 1)).performMouseInput { click(center) }
            onNodeWithTag(TableTags.rowHandle(table.id, 1)).assertIsSelected()
            onNodeWithTag(TableTags.RowMenu).assertDoesNotExist()
            onNodeWithTag(TableTags.ColumnMenu).assertDoesNotExist()
            onNodeWithTag(TableTags.RowBelow).assertContentDescriptionEquals("Insert row below")
            save(onRoot(), "table-held-row.png")
            val before = state.value.grid
            onNodeWithTag(TableTags.RowBelow).performClick()
            runOnIdle {
                val grown = state.value.grid
                assertEquals(4, grown.rowCount)
                assertEquals(before.rows[1].id, grown.rows[1].id)
                assertEquals(before.rows[2].id, grown.rows[3].id)
                assertTrue(grown.rows[2].id !in before.rows.map { it.id })
                assertEquals(setOf(table.id), state.value.selectedObjectIds)
            }
            // Still holding row 1: Delete removes that row and lets go.
            onNodeWithTag(TableTags.RowDelete).performClick()
            runOnIdle {
                val shrunk = state.value.grid
                assertEquals(3, shrunk.rowCount)
                assertTrue(before.rows[1].id !in shrunk.rows.map { it.id })
            }
            onNodeWithTag(TableTags.RowMenu).assertIsDisplayed()
            onNodeWithTag(TableTags.rowHandle(table.id, 1)).assertIsNotSelected()

            val column = TableTags.columnHandle(table.id, 0)
            onNodeWithTag(column).performMouseInput { click(center) }
            onNodeWithTag(TableTags.ColumnRight).assertContentDescriptionEquals("Insert column right")
            onNodeWithTag(TableTags.ColumnDelete).assertExists()
            onNodeWithTag(column).performMouseInput { click(center) }
            onNodeWithTag(TableTags.ColumnRight).assertDoesNotExist()
            onNodeWithTag(TableTags.ColumnMenu).assertIsDisplayed()
            onNodeWithTag(column).performMouseInput { click(center) }
            val columns = state.value.grid.columns.size
            onNodeWithTag(TableTags.ColumnRight).performClick()
            runOnIdle { assertEquals(columns + 1, state.value.grid.columns.size) }

            // Holding another table, or nothing, ends the hold.
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(bottomRight - Offset(120f, 120f)) }
            runOnIdle { state.value = state.value.selectObject(table.id) }
            onNodeWithTag(TableTags.ColumnMenu).assertIsDisplayed()
            onNodeWithTag(column).assertIsNotSelected()
        }

    @Test
    fun withNothingHeldTheRowsAndColumnsMenusActAtTheFarEdgeAndToggleHeaders() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val last = table.rows.last().id
            onNodeWithTag(TableTags.RowMenu).assertContentDescriptionEquals("Rows").performClick()
            onNodeWithTag(TableTags.RowBelow).assertTextEquals("Insert below")
            onNodeWithTag(TableTags.RowDelete).assertTextEquals("Delete row")
            save(onAllNodes(isRoot())[0], "table-row-menu.png")
            save(onAllNodes(isRoot())[1], "table-row-menu-popup.png")
            onNodeWithTag(TableTags.RowBelow).performClick()
            runOnIdle { assertEquals(last, state.value.grid.rows[2].id); assertEquals(4, state.value.grid.rowCount) }
            onNodeWithTag(TableTags.RowMenu).performClick()
            onNodeWithTag(TableTags.RowDelete).performClick()
            runOnIdle { assertEquals(table.rows.map { it.id }, state.value.grid.rows.map { it.id }) }
            onNodeWithTag(TableTags.ColumnMenu).assertContentDescriptionEquals("Columns").performClick()
            onNodeWithTag(TableTags.ColumnRight).assertTextEquals("Insert right").performClick()
            runOnIdle { assertEquals(4, state.value.grid.columnCount) }
            onNodeWithTag(TableTags.RowMenu).performClick()
            onNodeWithTag(TableTags.HeaderRow).performClick()
            runOnIdle { assertFalse(state.value.grid.headerRow) }
            onNodeWithTag(TableTags.ColumnMenu).performClick()
            onNodeWithTag(TableTags.HeaderColumn).performClick()
            runOnIdle { assertTrue(state.value.grid.headerColumn) }

            // A one-row, one-column table has no Delete to offer.
            val single = newTable(1, 1, x = 120f, y = 260f).copy(id = "single")
            runOnIdle { state.value = workspace(single).selectObject(single.id) }
            onNodeWithTag(TableTags.RowMenu).performClick()
            onNodeWithTag(TableTags.RowDelete).assertDoesNotExist()
            onNodeWithTag(TableTags.RowBelow).performClick()
            onNodeWithTag(TableTags.columnHandle(single.id, 0)).performMouseInput { click(center) }
            onNodeWithTag(TableTags.ColumnRight).assertExists()
            onNodeWithTag(TableTags.ColumnDelete).assertDoesNotExist()
        }

    @Test
    fun draggingAHandleResizesItsColumnOrRowWithoutHoldingItAndUndoesInOneStep() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            val history = state.value.structuralUndo.size
            onNodeWithTag(TableTags.columnHandle(table.id, 0)).performMouseInput {
                moveTo(center); press(); repeat(10) { moveBy(Offset(8f, 0f)) }; release()
            }
            runOnIdle {
                val wider = state.value.grid
                assertTrue(wider.columns[0] > table.columns[0] + 40f, "column width ${wider.columns[0]}")
                assertEquals(table.columns.drop(1), wider.columns.drop(1))
                assertEquals(wider.columns.sum(), wider.width)
                assertEquals(history + 1, state.value.structuralUndo.size)
            }
            onNodeWithTag(TableTags.ColumnMenu).assertIsDisplayed()
            onNodeWithTag(TableTags.rowHandle(table.id, 1)).performMouseInput {
                moveTo(center); press(); repeat(8) { moveBy(Offset(0f, 8f)) }; release()
            }
            runOnIdle {
                assertTrue(state.value.grid.rows[1].minHeight > table.rows[1].minHeight + 30f)
                assertEquals(table.rows[0].minHeight, state.value.grid.rows[0].minHeight)
                assertEquals(history + 2, state.value.structuralUndo.size)
            }
            onNodeWithTag(TableTags.RowMenu).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
            runOnIdle { assertEquals(table.rows, state.value.grid.rows) }
            onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
            runOnIdle { assertEquals(table, state.value.grid) }
        }

    @Test
    fun tableEditsReachTheOptInDebugLogOncePerDrag() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val lines = mutableListOf<String>()
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent {
                ViveNotesTheme(darkTheme = true) {
                    WorkspaceScreen(state.value, { state.value = it(state.value) }, debugLog = DebugLog(true) { lines += it })
                }
            }
            onNodeWithTag(TableTags.columnHandle(table.id, 1)).performMouseInput {
                moveTo(center); press(); repeat(10) { moveBy(Offset(8f, 0f)) }; release()
            }
            onNodeWithTag(TableTags.RowMenu).performClick()
            onNodeWithTag(TableTags.RowBelow).performClick()
            runOnIdle {
                val table = lines.filter { "[table]" in it }
                assertEquals(listOf("ViveNotes DEBUG [table] resize column=1", "ViveNotes DEBUG [table] insert row at=3"), table)
            }
        }

    @Test
    fun theGripMovesTheTableAndTheCornerScalesItFromItsTopLeft() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(TableTags.Move).performMouseInput { moveTo(center); press(); repeat(5) { moveBy(Offset(10f, 6f)) }; release() }
            runOnIdle {
                assertTrue(state.value.grid.x > table.x + 30f && state.value.grid.y > table.y + 15f)
                assertEquals(table.columns, state.value.grid.columns)
            }
            val moved = state.value.grid
            onNodeWithTag(WorkspaceTestTags.objectCorner(table.id, 3)).performMouseInput {
                moveTo(center); press(); repeat(5) { moveBy(Offset(12f, 8f)) }; release()
            }
            runOnIdle {
                val scaled = state.value.grid
                assertTrue(scaled.width > moved.width, "width ${scaled.width}")
                assertTrue(scaled.height > moved.height, "height ${scaled.height}")
                assertEquals(moved.x, scaled.x); assertEquals(moved.y, scaled.y)
            }
        }

    @Test
    fun aLockedTableCanStillHoldRowsButItsHandlesNoLongerResizeOrMoveIt() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val locked = table.copy(lockGroup = "lock")
            val state = mutableStateOf(workspace(locked).selectObject(locked.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(TableTags.Move).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.objectCorner(locked.id, 3)).assertDoesNotExist()
            onNodeWithTag(TableTags.columnHandle(locked.id, 0)).performMouseInput {
                moveTo(center); press(); repeat(10) { moveBy(Offset(8f, 0f)) }; release()
            }
            runOnIdle { assertEquals(locked, state.value.grid) }
            onNodeWithTag(TableTags.rowHandle(locked.id, 2)).performMouseInput { click(center) }
            onNodeWithTag(TableTags.RowBelow).performClick()
            runOnIdle { assertEquals(4, state.value.grid.rowCount); assertEquals("lock", state.value.grid.lockGroup) }
        }

    @Test
    fun aHandleClickedWhileTypingInACellSelectsTheTableAndHoldsThatRow() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val cell = table.cellAt(0, 0)!!
            val state = mutableStateOf(workspace(table).focusTextBox(cell.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(WorkspaceTestTags.ObjectToolkit).assertDoesNotExist()
            onNodeWithTag(TableTags.rowHandle(table.id, 2)).performMouseInput { click(center) }
            runOnIdle {
                assertEquals(setOf(table.id), state.value.selectedObjectIds)
                assertNull(state.value.focusedTextOutlineId)
            }
            onNodeWithTag(TableTags.rowHandle(table.id, 2)).assertIsSelected()
            onNodeWithTag(TableTags.RowBelow).assertIsDisplayed()
        }

    @Test
    fun theBorderWidthAndFillActionsEditTheSelectedTable() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val state = mutableStateOf(workspace(table).selectObject(table.id))
            setContent { ViveNotesTheme(darkTheme = true) { WorkspaceScreen(state.value, { state.value = it(state.value) }) } }
            onNodeWithTag(TableTags.BorderWidth).assertContentDescriptionEquals("Change border width").performClick()
            onNodeWithTag("${TableTags.BorderWidth}-slider-plus").performClick()
            runOnIdle { assertEquals(2f, state.value.grid.borderWidth) }
            onNodeWithTag("${TableTags.BorderWidth}-slider").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("${TableTags.BorderWidth}-slider").assertDoesNotExist()
            onNodeWithTag(TableTags.ObjectFill).assertContentDescriptionEquals("Change fill color").performClick()
            onNodeWithContentDescription("Blue").performClick()
            runOnIdle { assertNotNull(state.value.grid.fillArgb) }
            onNodeWithTag(TableTags.ObjectFill).performClick()
            onNodeWithTag(SelectionColorTags.NoFill).performClick()
            runOnIdle { assertNull(state.value.grid.fillArgb) }
        }

    private fun save(node: SemanticsNodeInteraction, name: String) {
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, name).writeBytes(it.bytes) }
        }
    }
}
