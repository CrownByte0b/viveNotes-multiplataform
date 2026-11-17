package com.vivenotes.workspace

import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.*
import kotlin.test.*

class EducationalSolidWorkspaceTest {
    @Test
    fun wallThicknessChangesThePlacedOpeningAndCopyUndoAndRedoKeepItsBoundaries() {
        val base = WorkspaceState.demo()
        val empty = base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList())) }
        val placed = listOf(5, 80).map { thickness ->
            empty.setShapeSettings(ShapeToolSettings(kind = ShapeKind.HollowCylinder, wallThickness = thickness))
                .toggleShapeTool().createShape(100f, 220f, 340f, 400f)
        }
        val shapes = placed.map { it.selectedPage!!.document.outlines.single() as Outline.Shape }
        assertEquals(shapes[0].width, shapes[1].width)
        val holes = shapes.map { it.fillRegion()[1].filterIndexed { index, _ -> index % 2 == 0 } }
        assertTrue(holes[0].max() - holes[0].min() > holes[1].max() - holes[1].min())
        placed.forEach { state ->
            val original = state.selectedPage!!.document
            assertTrue(state.undoStructure().selectedPage!!.document.outlines.isEmpty())
            assertEquals(original, state.undoStructure().redoStructure().selectedPage!!.document)
            val copied = state.copySelectedObjects().pasteCanvasAt(500f, 500f)
            val pasted = copied.selectedPage!!.document.outlines.last() as Outline.Shape
            assertEquals((original.outlines.single() as Outline.Shape).segments.map { it.fillContour },
                pasted.segments.map { it.fillContour })
            assertEquals(2, pasted.fillRegion().size)
        }
        assertEquals(5, empty.setShapeSettings(ShapeToolSettings(wallThickness = -5)).shapeSettings.wallThickness)
        assertEquals(80, empty.setShapeSettings(ShapeToolSettings(wallThickness = 100)).shapeSettings.wallThickness)
    }
}
