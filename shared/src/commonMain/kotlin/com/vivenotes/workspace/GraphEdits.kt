package com.vivenotes.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.graph.GraphAnalysis
import com.vivenotes.graph.GraphExpression
import com.vivenotes.graph.GraphFrame
import com.vivenotes.graph.function
import com.vivenotes.graph.window
import com.vivenotes.math.MathGraph
import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import kotlin.time.Clock

/**
 * Android's placement: below the recognized formula, otherwise just inside the visible page.
 *
 * The window is fitted here rather than taken from the pane's preview, which always shows −10…10.
 * A formula with sliders arrives taller by their band, so its plot is the size every other graph's is.
 */
fun WorkspaceState.insertGraph(graph: MathGraph, near: Rect? = null, origin: Offset = Offset.Zero): WorkspaceState {
    val page = selectedPage?.takeIf { it.editable } ?: return this
    if (notebookTransfer.running || filePane.busy) return this
    val source = graph.expression ?: return this
    val function = GraphExpression.parse(source)?.with(graph.parameters.map { it.value }) ?: return this
    val window = GraphAnalysis.fit(function::evaluate)
    val created = Outline.Graph(
        id = newId(),
        x = (near?.left ?: (origin.x + 24f)).coerceAtLeast(0f),
        y = (near?.let { it.bottom + 24f } ?: (origin.y + 24f)).coerceAtLeast(page.document.style.titleFloor),
        expression = source, latex = graph.functionLatex.orEmpty(),
        variable = graph.xLabel, dependent = graph.yLabel,
        xMin = window.xMin, xMax = window.xMax, yMin = window.yMin, yMax = window.yMax,
        parameters = graph.parameters,
        height = Outline.Graph.DEFAULT_HEIGHT + graph.parameters.size * GraphFrame.SLIDER_ROW_DP,
    )
    return editOutlines { it + created }.selectPointer().copy(selectedObjectIds = setOf(created.id))
}

fun WorkspaceState.updateGraphs(ids: Set<String>, change: (Outline.Graph) -> Outline.Graph): WorkspaceState =
    if (ids.isEmpty() || notebookTransfer.running || filePane.busy) this else editOutlines { outlines ->
        outlines.map { if (it is Outline.Graph && it.id in ids) change(it) else it }
    }

fun WorkspaceState.zoomGraphs(ids: Set<String>, factor: Double, atMillis: Long = Clock.System.now().toEpochMilliseconds()): WorkspaceState {
    if (!factor.isFinite() || factor <= 0.0) return this
    val changed = updateGraphs(ids) { graph ->
        val next = graph.window().zoomed(factor)
        if (!next.isUsable) graph else graph.copy(xMin = next.xMin, xMax = next.xMax, yMin = next.yMin, yMax = next.yMax)
    }
    if (changed === this) return this
    val key = "graph-zoom:${ids.sorted().joinToString()}"
    val prior = structuralUndo.lastOrNull()
    val joins = prior?.pageId == selectedPageId && prior.coalesceKey == key && atMillis - prior.atMillis in 0..750
    val history = if (joins) structuralUndo else changed.structuralUndo
    return changed.copy(structuralUndo = history.dropLast(1) + history.last().copy(coalesceKey = key, atMillis = atMillis))
}

fun WorkspaceState.fitGraphs(ids: Set<String>): WorkspaceState = updateGraphs(ids) { graph ->
    val function = graph.function() ?: return@updateGraphs graph
    val next = GraphAnalysis.fit(function::evaluate)
    graph.copy(xMin = next.xMin, xMax = next.xMax, yMin = next.yMin, yMax = next.yMax)
}

/** One slider let go, paused or set from assistive technology: snapped, one Undo, none when unchanged. */
fun WorkspaceState.setGraphParameter(id: String, index: Int, value: Double): WorkspaceState =
    updateGraphs(setOf(id)) { it.withParameterValue(index, value) }

/** The Sliders dialog's answer, refused unless it names the same letters in the same order. */
fun WorkspaceState.setGraphSliders(id: String, parameters: List<GraphParameter>): WorkspaceState =
    updateGraphs(setOf(id)) { it.withParameters(parameters) }

/** A late engine answer cannot replace another page, a newer drag, or an object edited meanwhile. */
fun WorkspaceState.rewriteGraph(pageId: String, from: Outline.Graph, expression: String, latex: String): WorkspaceState {
    if (selectedPageId != pageId || GraphExpression.parse(expression) == null) return this
    return updateGraphs(setOf(from.id)) { if (it == from) it.rewritten(expression, latex) else it }
}
