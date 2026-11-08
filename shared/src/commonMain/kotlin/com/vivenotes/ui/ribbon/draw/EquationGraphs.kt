package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.MathText
import com.vivenotes.localization.Strings
import com.vivenotes.math.MathEngine
import com.vivenotes.math.MathOperationResult
import com.vivenotes.model.Outline
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.insertGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The engine's Graph action, which the equation toolkit runs on a placed formula. */
internal const val GRAPH_ACTION = "graph"

/**
 * The equation toolkit's Graph button (Android `NotesApp.graphEquation`): which equation objects can
 * be graphed, and placing one's graph just below it.
 *
 * Which formulas can be graphed is asked of the engine once per formula and remembered for the
 * session — so after the first time the button is there as the toolkit opens. A check cancelled
 * before the engine answered is not remembered: the next toolkit asks again.
 */
internal class EquationGraphs(
    private val engine: MathEngine,
    private val scope: CoroutineScope,
    private val read: () -> WorkspaceState,
    private val change: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val log: DebugLog,
) : GraphableFormulas {
    private val answers = HashMap<String, Boolean>()

    /** Equations whose graph is being drawn up, so a second click does not place a second graph. */
    var graphing: Set<String> by mutableStateOf(emptySet())
        private set

    override fun known(latex: String): Boolean? = answers[latex]

    override suspend fun check(latex: String): Boolean = answers[latex] ?: try {
        engine.analyze(latex).actions.any { it.id == GRAPH_ACTION }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }.also { answers[latex] = it }

    /** The Math pane's Graph result for [equation], placed just below the formula. */
    fun graph(equation: Outline.Equation) {
        if (equation.id in graphing) return
        val pageId = read().selectedPageId
        graphing = graphing + equation.id
        change { it.copy(graphError = null) }
        log.event("graph") { "equation graph started" }
        scope.launch {
            try {
                val result = try {
                    engine.execute(equation.latex, GRAPH_ACTION)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    MathOperationResult(error = failure.message)
                }
                val graph = result.graph?.takeIf { it.expression != null }
                val below = Rect(equation.x, equation.y, equation.x + equation.width, equation.y + equation.height)
                change { current ->
                    when {
                        current.selectedPageId != pageId -> current
                        graph != null -> current.insertGraph(graph, below)
                        else -> current.copy(graphError = result.error?.let(MathText::translate) ?: Strings.graph_equation_failed)
                    }
                }
                log.event("graph") { if (graph != null) "equation graph placed" else "equation graph failed" }
            } finally {
                graphing = graphing - equation.id
            }
        }
    }
}
