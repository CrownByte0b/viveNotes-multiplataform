package com.vivenotes.ui.ribbon.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.graph.GraphDrag
import com.vivenotes.localization.Strings
import com.vivenotes.math.MathEngine
import com.vivenotes.model.Outline
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.rewriteGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class GraphRewritePreview(val from: Outline.Graph, val drag: GraphDrag)

/** Keep the numerical preview until SymPy returns the exact function, without accepting stale work. */
internal class GraphMutations(
    private val engine: MathEngine,
    private val scope: CoroutineScope,
    private val read: () -> WorkspaceState,
    private val change: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val log: DebugLog,
) {
    var pending: Map<String, GraphRewritePreview> by mutableStateOf(emptyMap())
        private set

    fun mutate(from: Outline.Graph, drag: GraphDrag) {
        val captured = read()
        if (!drag.changes || captured.selectedPage?.editable != true || pending.containsKey(from.id)) return
        if (captured.selectedPage?.document?.outlines?.find { it.id == from.id } != from) return
        val request = GraphRewritePreview(from, drag)
        pending = pending + (from.id to request)
        change { it.copy(graphError = null) }
        log.event("graph") { "rewrite started" }
        scope.launch {
            try {
                val result = engine.mutateGraph(from.expression, from.variable, drag.request(), from.parameters.map { it.symbol })
                change { current ->
                    if (!current.matches(captured, from)) current
                    else if (result.error != null || result.expression == null || result.latex == null ||
                        com.vivenotes.graph.GraphExpression.parse(result.expression) == null)
                        current.copy(graphError = Strings.graph_rewrite_failed)
                    else current.rewriteGraph(captured.selectedPageId, from, result.expression, result.latex)
                }
                log.event("graph") { if (result.error == null) "rewrite completed" else "rewrite failed" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                change { if (it.matches(captured, from)) it.copy(graphError = Strings.graph_rewrite_failed) else it }
                log.event("graph") { "rewrite failed" }
            } finally {
                if (pending[from.id] === request) pending = pending - from.id
            }
        }
    }

    private fun WorkspaceState.matches(captured: WorkspaceState, from: Outline.Graph): Boolean =
        selectedPageId == captured.selectedPageId && selectedPage?.editable == true &&
            structuralUndo === captured.structuralUndo && structuralRedo === captured.structuralRedo &&
            selectedPage?.document?.outlines?.find { it.id == from.id } == from
}
