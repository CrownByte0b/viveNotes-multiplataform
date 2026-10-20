package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.model.ink.*
import kotlin.test.*

class DrawPlacementTest {
    @Test
    fun tableUsesItsKitDisarmsOnPlacementAndKeepsTypedCellsInTheDocument() {
        val settings = TableToolSettings(inkOnly = false, columns = 4, rows = 2, headerRow = false,
            headerColumn = true, borderWidth = 3, borderArgb = 0xff123456.toInt(), fillArgb = 0xffeeeeee.toInt(), colorFollowsTheme = false)
        val initial = WorkspaceState.demo().setInkSettings(InkSettings(table = settings)).selectDrawPlacement(DrawPlacementTool.Table)
        assertSame(initial, initial.placeDrawObject(100f, 0f))
        val placed = initial.placeDrawObject(100f, 200f)
        val table = placed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        assertEquals(4, table.columns.size); assertEquals(2, table.rows.size)
        assertTrue(table.headerColumn); assertFalse(table.headerRow); assertFalse(table.inkOnly)
        assertEquals(settings.fillArgb, table.fillArgb); assertEquals(settings.borderArgb, table.borderArgb)
        assertEquals(3f, table.borderWidth)
        assertNull(placed.drawPlacement)
        val typed = placed.updateTableCell(table.id, 1, 2, "Cell content")
        assertEquals("Cell content", typed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().cellAt(1, 2)!!.plainText)
        assertEquals(placed.structuralUndo.size, typed.structuralUndo.size)
        val edited = typed.editTable(TableEdit.InsertColumn(table.id, table.columnCount))
            .editTable(TableEdit.InsertRow(table.id, table.rowCount))
        val grid = edited.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        assertEquals(5, grid.columnCount); assertEquals(3, grid.rowCount)
        assertEquals("Cell content", grid.cellAt(1, 2)!!.plainText)
        assertEquals(5, edited.undoStructure().selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single().columnCount)
    }

    @Test
    fun equationPlacementAndEditingUseMeasuredDimensionsAndOneUndoEach() {
        val initial = WorkspaceState.demo()
        val armed = initial.armEquation(PendingDrawEquation("x^2", 60f, 30f))
        val placed = armed.placeDrawObject(200f, 240f)
        val equation = placed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single()
        assertEquals("x^2", equation.latex); assertEquals(60f, equation.width); assertEquals(30f, equation.height)
        assertNull(placed.drawPlacement); assertNull(placed.pendingEquation)
        val changed = placed.updateSelectedEquation("y^3", 75f, 40f)
        assertEquals("y^3", changed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single().latex)
        assertEquals(equation, changed.undoStructure().selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>().single())
    }

    @Test
    fun holdStraightLineUsesTheCapturedPenAndCanBeUndone() {
        val initial = WorkspaceState.demo().selectInkTool(InkTool.Pen, 2)
        val pen = InkPenSettings(sizeDp = 6f, colorArgb = 0xff123456.toInt(), colorFollowsTheme = false, lineType = LineType.Dotted)
        val changed = initial.createStraightLine(StraightLine(20f, 200f, 120f, 200f), pen)
        val line = changed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().last()
        assertEquals(ShapeKind.Line, line.kind); assertEquals(6f, line.borderWidth)
        assertEquals(pen.colorArgb, line.borderArgb); assertEquals(pen.lineType, line.lineType)
        assertEquals(initial.selectedPage!!.document, changed.undoStructure().selectedPage!!.document)
        assertEquals(2, changed.activePenIndex); assertEquals(InkTool.Pen, changed.inkTool)
    }
    @Test
    fun invalidEquationMeasurementsAndBlankSourcesDoNotArmPlacement() {
        val state = WorkspaceState.demo()
        for (pending in listOf(PendingDrawEquation("", 60f, 30f), PendingDrawEquation("x", Float.NaN, 30f),
            PendingDrawEquation("x", 60f, Float.POSITIVE_INFINITY))) assertSame(state, state.armEquation(pending))
    }
    @Test
    fun openingDocumentToolsRetainsTheFocusedCellAndItsTextSelection() {
        val placed = WorkspaceState.demo().setInkSettings(InkSettings(table = TableToolSettings(inkOnly = false)))
            .selectDrawPlacement(DrawPlacementTool.Table).placeDrawObject(100f, 200f)
        val table = placed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()
        val cell = table.cellAt(0, 0)!!
        val focused = placed.focusTextBox(cell.id).editSelectedText("Cell", com.vivenotes.richtext.TextSelection(0, 4))
        val document = focused.selectRibbonTab(RibbonTab.Document)
        assertEquals(cell.id, document.focusedTextOutlineId)
        assertEquals(focused.editorSelection, document.editorSelection)
        assertEquals(focused.richText!!.text, document.richText!!.text)
        assertNull(document.selectRibbonTab(RibbonTab.Draw).focusedTextOutlineId)
    }
}
