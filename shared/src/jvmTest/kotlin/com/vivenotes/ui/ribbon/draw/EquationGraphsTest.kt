package com.vivenotes.ui.ribbon.draw

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.math.*
import com.vivenotes.model.Outline
import com.vivenotes.workspace.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The equation toolkit's Graph button behind the scenes: which formulas can be graphed, and placing one. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EquationGraphsTest {
    private class Engine : MathEngine {
        var analyses = 0
        val analysis = CompletableDeferred<MathAnalysis>()
        val graph = CompletableDeferred<MathOperationResult>()
        override suspend fun analyze(latex: String): MathAnalysis { analyses++; return analysis.await() }
        override suspend fun execute(latex: String, actionId: String): MathOperationResult {
            assertEquals(GRAPH_ACTION, actionId)
            return graph.await()
        }
    }

    private val equation = Outline.Equation(id = "eq", x = 100f, y = 200f, width = 160f, height = 48f, latex = "y=x^2-4")
    private val parabola = MathGraph("x", "y", emptyList(), emptyList(), "((x^2)+(-4))", "x^{2} - 4")

    private fun page(): WorkspaceState {
        val base = WorkspaceState.demo()
        return base.updatePage(base.selectedPageId) { it.copy(document = it.document.copy(outlines = listOf(equation))) }
            .selectObject(equation.id)
    }

    @Test
    fun theEngineIsAskedOncePerFormulaAndTheAnswerIsKnownFromThenOn() = runTest {
        val engine = Engine()
        var state = page()
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog())
        assertNull(graphs.known(equation.latex))

        engine.analysis.complete(MathAnalysis(actions = listOf(MathAction("solve", "Solve"), MathAction("graph", "Graph"))))
        assertTrue(graphs.check(equation.latex))
        assertTrue(graphs.check(equation.latex))

        assertEquals(1, engine.analyses)
        assertEquals(true, graphs.known(equation.latex))
    }

    @Test
    fun aFormulaWithoutAGraphActionOrAFailingEngineCannotBeGraphed() = runTest {
        val engine = Engine()
        var state = page()
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog())
        engine.analysis.completeExceptionally(IllegalStateException("not installed"))
        assertFalse(graphs.check("\\int x dx"))
        assertEquals(false, graphs.known("\\int x dx"))
    }

    /** The toolkit closing mid-question is not an answer: the next one asks again. */
    @Test
    fun aCheckCancelledBeforeTheEngineAnsweredIsNotRemembered() = runTest {
        val engine = Engine()
        var state = page()
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog())
        val asking = async { graphs.check(equation.latex) }
        runCurrent()
        asking.cancel()
        runCurrent()
        assertNull(graphs.known(equation.latex))
    }

    @Test
    fun theGraphLandsJustBelowTheEquationAsOneUndoStepAndASecondClickWaits() = runTest {
        val engine = Engine()
        var state = page()
        val before = state
        val lines = mutableListOf<String>()
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog(true, lines::add))

        graphs.graph(equation)
        graphs.graph(equation)
        runCurrent()
        assertEquals(setOf("eq"), graphs.graphing)
        engine.graph.complete(MathOperationResult(title = "Graph", graph = parabola))
        runCurrent()

        val placed = state.selectedPage!!.document.outlines.filterIsInstance<Outline.Graph>().single()
        assertEquals(equation.x, placed.x)
        assertEquals(equation.y + equation.height + 24f, placed.y)
        assertEquals(setOf(placed.id), state.selectedObjectIds)
        assertEquals(before.structuralUndo.size + 1, state.structuralUndo.size)
        assertTrue(graphs.graphing.isEmpty())
        // Operations only, never the formula.
        assertTrue(lines.none { "x^2" in it || "y=" in it }, lines.toString())
    }

    @Test
    fun aFormulaTheEngineCannotGraphSaysSoAndChangesNothing() = runTest {
        val engine = Engine()
        var state = page()
        val before = state.selectedPage!!.document
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog())

        graphs.graph(equation)
        engine.graph.complete(MathOperationResult(graph = parabola.copy(expression = null)))
        runCurrent()

        assertEquals(before, state.selectedPage!!.document)
        assertEquals(com.vivenotes.localization.Strings.graph_equation_failed, state.graphError)
    }

    @Test
    fun anAnswerForAPageNoLongerShownIsDropped() = runTest {
        val engine = Engine()
        var state = page()
        val graphs = EquationGraphs(engine, this, { state }, { state = it(state) }, DebugLog())

        graphs.graph(equation)
        runCurrent()
        state = state.copy(selectedPageId = "other")
        val moved = state
        engine.graph.complete(MathOperationResult(graph = parabola))
        runCurrent()

        assertEquals(moved, state)
    }
}
