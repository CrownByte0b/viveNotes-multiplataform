package com.vivenotes.ui.shell

import com.vivenotes.model.ink.InkSample
import com.vivenotes.workspace.DrawPlacementTool
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class CanvasSelectionContext(val pageId: String, val lasso: Boolean, val text: Boolean,
    val shape: Boolean, val ink: InkTool?, val placement: DrawPlacementTool?, val penIndex: Int,
    val zoom: Float, val drawWithFinger: Boolean, val density: Float = 1f, val fontScale: Float = 1f)

internal fun WorkspaceState.preparesCanvasLassoOffThread(points: Int): Boolean = points >= 256 ||
    (selectedPage?.ink?.strokes?.size?.toLong() ?: 0L) +
    (selectedPage?.document?.outlines?.size?.toLong() ?: 0L) >= 128L

/** UI-owned requests; one worker lane keeps uncancellable native preparation from overlapping. */
internal class CanvasSelectionWorker(private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val prepare: suspend (WorkspaceState, List<InkSample>, Map<String, Float>) -> WorkspaceState =
        { state, path, heights -> state.selectObjectsInLasso(path, heights) }) {
    private val lane = Mutex()
    private var generation = 0L
    private var pending: Job? = null
    private var context: CanvasSelectionContext? = null
    private var closed = false

    fun updateContext(next: CanvasSelectionContext) {
        if (context != next) {
            context = next
            cancel()
        }
    }

    fun submit(source: WorkspaceState, path: List<InkSample>, heights: Map<String, Float>,
        publish: ((WorkspaceState) -> WorkspaceState) -> Unit): Job {
        check(!closed)
        cancel()
        val capturedGeneration = generation
        val exactPath = path.toList()
        val exactHeights = heights.toMap()
        return scope.launch {
            val prepared = withContext(dispatcher) {
                lane.withLock {
                    coroutineContext.ensureActive()
                    prepare(source, exactPath, exactHeights)
                }
            }
            coroutineContext.ensureActive()
            if (generation == capturedGeneration) publish { current ->
                if (!closed && generation == capturedGeneration && current === source) prepared else current
            }
        }.also { pending = it }
    }

    fun cancel() {
        generation++
        pending?.cancel()
        pending = null
    }

    fun close() {
        cancel()
        closed = true
    }
}
