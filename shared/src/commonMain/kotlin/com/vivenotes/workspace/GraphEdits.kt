package com.vivenotes.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.graph.GraphAnalysis
import com.vivenotes.graph.GraphExpression
import com.vivenotes.graph.window
import com.vivenotes.math.MathGraph
import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import kotlin.time.Clock

/** Android's placement: below the recognized formula, otherwise just inside the visible page. */
fun WorkspaceState.insertGraph(graph: MathGraph, near: Rect? = null, origin: Offset = Offset.Zero): WorkspaceState {
    val page = selectedPage?.takeIf { it.editable } ?: return this
    if (notebookTransfer.running || filePane.busy) return this
    val source = graph.expression ?: return this
    val function = GraphExpression.parse(source) ?: return this
    val window = GraphAnalysis.fit(function::evaluate)
    val created = Outline.Graph(
        id = newId(),
        x = (near?.left ?: (origin.x + 24f)).coerceAtLeast(0f),
        y = (near?.let { it.bottom + 24f } ?: (origin.y + 24f)).coerceAtLeast(page.document.style.titleFloor),
        expression = source, latex = graph.functionLatex.orEmpty(),
        variable = graph.xLabel, dependent = graph.yLabel,
        xMin = window.xMin, xMax = window.xMax, yMin = window.yMin, yMax = window.yMax,
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
    val function = GraphExpression.parse(graph.expression) ?: return@updateGraphs graph
    val next = GraphAnalysis.fit(function::evaluate)
    graph.copy(xMin = next.xMin, xMax = next.xMax, yMin = next.yMin, yMax = next.yMax)
}

/** A late engine answer cannot replace another page, a newer drag, or an object edited meanwhile. */
fun WorkspaceState.rewriteGraph(pageId: String, from: Outline.Graph, expression: String, latex: String): WorkspaceState {
    if (selectedPageId != pageId || GraphExpression.parse(expression) == null) return this
    return updateGraphs(setOf(from.id)) { if (it == from) it.rewritten(expression, latex) else it }
}
