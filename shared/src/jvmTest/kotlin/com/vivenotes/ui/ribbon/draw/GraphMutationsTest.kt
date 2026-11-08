package com.vivenotes.ui.ribbon.draw

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.graph.*
import com.vivenotes.math.*
import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import com.vivenotes.workspace.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GraphMutationsTest {
    private class Engine : MathEngine {
        val reply = CompletableDeferred<GraphRewrite>()
        var sentParameters: List<String>? = null
        override suspend fun analyze(latex: String) = MathAnalysis()
        override suspend fun execute(latex: String, actionId: String) = MathOperationResult()
        override suspend fun mutateGraph(expression: String, variable: String, mutation: String, parameters: List<String>): GraphRewrite {
            sentParameters = parameters
            return reply.await()
        }
    }
    private val result = MathGraph("x", "y", emptyList(), emptyList(), "((x^2)+(-4))", "x^{2}-4")
    private fun WorkspaceState.graph() = selectedPrimeObjects.single() as Outline.Graph
    private fun drag(from: Outline.Graph): GraphDrag {
        val f = GraphExpression.parse(from.expression)!!
        return GraphDrag.of(f::evaluate, f.polynomial(), GraphPoint(2.0, 0.0, PointKind.Zero), 3.5, 0.0, from.window())
    }

    @Test
    fun pendingPreviewLandsAsOneUndoableExactRewriteWithoutLoggingContent() = runTest {
        val engine = Engine(); val lines = mutableListOf<String>()
        var state = WorkspaceState.demo().insertGraph(result)
        val before = state; val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog(true, lines::add))
        mutations.mutate(graph, drag(graph)); runCurrent()
        assertTrue(mutations.pending.containsKey(graph.id)); assertEquals(before.selectedPage!!.document, state.selectedPage!!.document)
        engine.reply.complete(GraphRewrite("((x+2)*(x+(-3.5)))", "x^2-1.5x-7")); runCurrent()
        assertTrue(mutations.pending.isEmpty()); assertEquals(before.structuralUndo.size + 1, state.structuralUndo.size)
        assertEquals(graph.expression, state.graph().originalExpression)
        assertEquals(before.selectedPage!!.document, state.undoStructure().selectedPage!!.document)
        assertTrue(lines.none { graph.expression in it || "x^2" in it })
    }

    /** The engine prints `$0`, `$1`… as the graph's own letters, so it is sent them in slider order. */
    @Test
    fun aGraphWithSlidersSendsTheEngineItsLettersInOrder() = runTest {
        val engine = Engine()
        val wave = result.copy(
            expression = "((\$0*(x^2))+\$1)",
            parameters = listOf(GraphParameter("a", "a", 1.0), GraphParameter("omega", "ω", 0.0)),
        )
        var state = WorkspaceState.demo().insertGraph(wave)
        val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        val f = graph.function()!!
        mutations.mutate(graph, GraphDrag.of(f::evaluate, f.polynomial(), GraphPoint(0.0, 0.0, PointKind.Minimum), 1.0, 1.0, graph.window()))
        runCurrent()
        assertEquals(listOf("a", "omega"), engine.sentParameters)
        engine.reply.complete(GraphRewrite(error = "stop")); runCurrent()
    }

    @Test
    fun undoWhileTheEngineWorksDiscardsTheAnswer() = runTest {
        val engine = Engine(); var state = WorkspaceState.demo().insertGraph(result)
        val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        mutations.mutate(graph, drag(graph)); runCurrent()
        state = state.undoStructure(); val undone = state
        engine.reply.complete(GraphRewrite("x", "x")); runCurrent()
        assertEquals(undone, state); assertTrue(mutations.pending.isEmpty())
    }

    @Test
    fun changingTheSelectedPageDiscardsTheAnswer() = runTest {
        val engine = Engine(); var state = WorkspaceState.demo().insertGraph(result)
        val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        mutations.mutate(graph, drag(graph)); runCurrent()
        state = state.copy(selectedPageId = "other"); val changed = state
        engine.reply.complete(GraphRewrite("x", "x")); runCurrent()
        assertEquals(changed, state)
    }

    @Test
    fun failedRewriteKeepsTheOriginalAndReportsAnError() = runTest {
        val engine = Engine(); var state = WorkspaceState.demo().insertGraph(result)
        val before = state; val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        mutations.mutate(graph, drag(graph)); runCurrent()
        engine.reply.complete(GraphRewrite(error = "no exact root")); runCurrent()
        assertEquals(before.selectedPage!!.document, state.selectedPage!!.document)
        assertEquals(before.structuralUndo, state.structuralUndo); assertNotNull(state.graphError)
        assertTrue(mutations.pending.isEmpty())
    }

    @Test
    fun malformedRewriteIsRefusedAndThePreviewIsRemoved() = runTest {
        val engine = Engine(); var state = WorkspaceState.demo().insertGraph(result)
        val before = state; val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        mutations.mutate(graph, drag(graph)); runCurrent()
        engine.reply.complete(GraphRewrite("unknown(x)", "unknown")); runCurrent()
        assertEquals(before.selectedPage!!.document, state.selectedPage!!.document)
        assertNotNull(state.graphError); assertTrue(mutations.pending.isEmpty())
    }

    @Test
    fun aFailureAfterUndoDoesNotPublishAnErrorOnTheRestoredPage() = runTest {
        val engine = Engine(); var state = WorkspaceState.demo().insertGraph(result)
        val graph = state.graph()
        val mutations = GraphMutations(engine, this, { state }, { state = it(state) }, DebugLog())
        mutations.mutate(graph, drag(graph)); runCurrent()
        state = state.undoStructure(); val undone = state
        engine.reply.completeExceptionally(IllegalStateException("stopped")); runCurrent()
        assertEquals(undone, state); assertTrue(mutations.pending.isEmpty())
    }
}
