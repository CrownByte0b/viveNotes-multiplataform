package com.vivenotes.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.graph.GraphExpression
import com.vivenotes.math.MathGraph
import com.vivenotes.model.*
import kotlin.test.*

class GraphEditsTest {
    private val result = MathGraph("t", "v", emptyList(), emptyList(), "((x^2)+(-4))", "t^{2} - 4")
    private fun WorkspaceState.graph() = selectedPage!!.document.outlines.filterIsInstance<Outline.Graph>().single()

    @Test
    fun placementFitsTheFunctionDisarmsToolsAndMakesOneUndoStep() {
        val before = WorkspaceState.demo().toggleObjectLasso()
        val placed = before.insertGraph(result, Rect(100f, 200f, 220f, 300f))
        val graph = placed.graph()
        assertEquals(100f, graph.x); assertEquals(324f, graph.y)
        assertEquals("t", graph.variable); assertEquals("v", graph.dependent)
        assertTrue(graph.yMin < -4 && graph.yMax > 0)
        assertFalse(placed.objectLassoArmed); assertNull(placed.inkTool)
        assertEquals(setOf(graph.id), placed.selectedObjectIds)
        assertEquals(before.structuralUndo.size + 1, placed.structuralUndo.size)
        assertEquals(before.selectedPage!!.document, placed.undoStructure().selectedPage!!.document)
        assertEquals(placed.selectedPage!!.document, placed.undoStructure().redoStructure().selectedPage!!.document)
    }

    @Test
    fun placementUsesTheVisibleOriginAndRefusesUnsupportedOrReadOnlyResults() {
        val base = WorkspaceState.demo()
        val graph = base.insertGraph(result, origin = Offset(200f, 500f)).graph()
        assertEquals(224f, graph.x); assertEquals(524f, graph.y)
        assertSame(base, base.insertGraph(result.copy(expression = null)))
        assertSame(base, base.insertGraph(result.copy(expression = "__import__(os)")))
        val unreadable = base.updatePage(base.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertSame(unreadable, unreadable.insertGraph(result))
    }

    @Test
    fun graphUsesThePrimeObjectClipboardLockMoveResizeAndRecolorContracts() {
        val placed = WorkspaceState.demo().insertGraph(result)
        val original = placed.graph()
        val moved = placed.moveSelectedObjects(20f, 30f)
        assertEquals(original.x + 20, moved.graph().x)
        val sized = moved.resizeSelectedObjects(0f, 0f, .01f, .01f)
        assertEquals(Outline.Graph.MIN_SIZE, sized.graph().width)
        assertEquals(Outline.Graph.MIN_SIZE, sized.graph().height)
        val locked = placed.toggleObjectLock()
        assertEquals(locked.graph(), locked.moveSelectedObjects(50f, 60f).graph())
        assertEquals(locked.graph(), locked.resizeSelectedObjects(0f, 0f, 2f, 2f).graph())
        val copied = locked.copySelectedObjects().pasteCanvasAt(400f, 500f)
        val duplicate = copied.selectedPrimeObjects.single() as Outline.Graph
        assertNotEquals(original.id, duplicate.id); assertNull(duplicate.lockGroup)
        assertEquals(original.expression, duplicate.expression)
        val recolored = placed.colorSelectedObjects(0xffff0000.toInt())
        assertEquals(0xffff0000.toInt(), recolored.graph().colorArgb)
        assertNull(recolored.colorSelectedObjects(0xff000000.toInt(), followsTheme = true).graph().colorArgb)
        assertTrue(placed.deleteSelectedObjects().selectedPage!!.document.outlines.none { it is Outline.Graph })
    }

    @Test
    fun mutateIsIndependentOfLockAndResetRestoresTheFirstFunction() {
        val placed = WorkspaceState.demo().insertGraph(result).toggleObjectLock()
        val original = placed.graph()
        val mutable = placed.updateGraphs(setOf(original.id)) { it.copy(mutable = true) }
        assertTrue(mutable.graph().mutable); assertNotNull(mutable.graph().lockGroup)
        val from = mutable.graph()
        val rewritten = mutable.rewriteGraph(mutable.selectedPageId, from, "((x^2)+(-1))", "t^{2}-1")
        assertEquals(original.expression, rewritten.graph().originalExpression)
        assertEquals(-1.0, GraphExpression.parse(rewritten.graph().expression)!!.evaluate(0.0))
        assertEquals(from, rewritten.undoStructure().graph())
        assertEquals(original.expression, rewritten.updateGraphs(setOf(from.id)) { it.reset() }.graph().expression)
        assertSame(mutable, mutable.rewriteGraph("another-page", from, "x", "t"))
        assertSame(mutable, mutable.rewriteGraph(mutable.selectedPageId, from.copy(expression = "x"), "x", "t"))
    }

    @Test
    fun zoomAndFitChangeOnlyTheWindowAndAreUndoable() {
        val placed = WorkspaceState.demo().insertGraph(result)
        val graph = placed.graph()
        val zoomed = placed.zoomGraphs(setOf(graph.id), 2.0 / 3.0)
        assertTrue(zoomed.graph().xMax - zoomed.graph().xMin < graph.xMax - graph.xMin)
        assertEquals(graph.expression, zoomed.graph().expression)
        assertEquals(graph, zoomed.undoStructure().graph())
        assertEquals(graph, zoomed.fitGraphs(setOf(graph.id)).graph())
    }

    @Test
    fun consecutiveZoomPressesShareOneUndoUntilAnotherEditOrTheTimeout() {
        val placed = WorkspaceState.demo().insertGraph(result)
        val ids = placed.selectedObjectIds
        val once = placed.zoomGraphs(ids, .75, atMillis = 1000)
        val twice = once.zoomGraphs(ids, .75, atMillis = 1200)
        assertEquals(once.structuralUndo.size, twice.structuralUndo.size)
        assertEquals(placed.graph(), twice.undoStructure().graph())
        assertEquals(twice.structuralUndo.size + 1, twice.zoomGraphs(ids, .75, atMillis = 3000).structuralUndo.size)
        val edited = twice.updateGraphs(ids) { it.copy(showLabels = false) }
        assertEquals(edited.structuralUndo.size + 1, edited.zoomGraphs(ids, .75, atMillis = 1300).structuralUndo.size)
    }
}
