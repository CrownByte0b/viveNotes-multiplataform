package com.vivenotes.ui.canvas

import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.PageContent
import com.vivenotes.workspace.PendingDrawEquation
import com.vivenotes.workspace.ShapeToolSettings
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which object the badge beside the mouse pointer shows: the one a click on the page would insert. */
class PendingInsertTest {
    private val demo = WorkspaceState.demo()

    @Test
    fun eachInsertingToolShowsWhatItInserts() {
        assertNull(demo.pendingInsert(), "the ordinary pointer inserts nothing")
        assertEquals(PendingInsert.TextBox, demo.toggleTextTool().pendingInsert())
        assertEquals(PendingInsert.Table, demo.selectDrawPlacement(DrawPlacementTool.Table).pendingInsert())
        assertEquals(PendingInsert.Equation,
            demo.armEquation(PendingDrawEquation("x^2", 40f, 20f)).pendingInsert())
    }

    @Test
    fun aShapeShowsTheKindChosen() {
        val shapes = demo.toggleShapeTool()
        assertEquals(PendingInsert.Shape(ShapeToolSettings().kind), shapes.pendingInsert())
        assertEquals(PendingInsert.Shape(ShapeKind.Ellipse),
            shapes.setShapeSettings(ShapeToolSettings(kind = ShapeKind.Ellipse)).pendingInsert())
    }

    @Test
    fun toolsThatPlaceNoObjectShowNothing() {
        assertNull(demo.selectDrawPlacement(DrawPlacementTool.InsertSpace).pendingInsert(), "space is not an object")
        assertNull(demo.selectDrawPlacement(DrawPlacementTool.Equation).pendingInsert(),
            "an equation tool with no formula written yet has nothing to place")
        assertNull(demo.toggleTextTool().toggleTextTool().pendingInsert())
        assertNull(demo.toggleTextTool().selectPointer().pendingInsert())
    }

    @Test
    fun aPageThatCannotBeEditedTakesNothing() {
        val unreadable = demo.copy(notebooks = demo.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { it.copy(content = PageContent.Unreadable) })
            })
        })
        assertNull(unreadable.toggleTextTool().pendingInsert())
        assertNull(unreadable.toggleShapeTool().pendingInsert())
    }
}
