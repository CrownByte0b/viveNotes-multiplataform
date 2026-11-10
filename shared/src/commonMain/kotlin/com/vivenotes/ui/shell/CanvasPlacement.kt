package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.ui.canvas.CanvasPasteLayoutRequest
import com.vivenotes.workspace.PasteFrames
import kotlinx.coroutines.withTimeoutOrNull

/** A height on the open page for the canvas to bring into view, because something just landed there. */
internal data class CanvasScrollTarget(val pageId: String, val y: Float)

/** How far down the window something can land and still count as in view. */
internal const val SCROLL_TARGET_COMFORTABLE = 0.6f

/**
 * Android's `REVEAL_MARGIN`: a landing scrolled to sits this far below the window's top edge, so it
 * reads as arrived rather than as overshot.
 */
internal val SCROLL_TARGET_MARGIN = 48.dp

/** Long enough for the canvas to grow to hold what just landed; a scroll can only wait so long. */
internal const val SCROLL_RANGE_WAIT_MS = 1_000L

/**
 * What placing things from outside the canvas needs from the laid-out canvas, and what it asks back:
 * Android's `NotesViewModel.reportViewport`, `pendingPasteLayout` with its content bottom, and
 * `canvasScrollTarget`. File ▸ Import ▸ File is what uses it today.
 *
 * Layout facts are plain fields, written by [PageCanvas] as it lays out; the requests are state, so
 * the canvas sees them arrive.
 */
internal class CanvasPlacement {
    /** The page point at the view's top-left corner, in page dp. */
    var visibleOrigin: () -> Offset = { Offset.Zero }

    /** How wide the view is, in page dp; zero until the canvas has said. */
    var visibleWidth: () -> Float = { 0f }

    /** How far down the page what is already on it reaches, as the canvas drew it; null before it has. */
    var contentBottom: () -> Float? = { null }

    /** Outlines for the canvas to measure, as a paste's are. */
    var pendingLayout by mutableStateOf<CanvasPasteLayoutRequest?>(null)
        private set

    /** Where the canvas has been asked to scroll to, until it has. */
    var scrollTarget by mutableStateOf<CanvasScrollTarget?>(null)
        private set

    fun scrollTo(target: CanvasScrollTarget) {
        scrollTarget = target
    }

    fun onScrollTargetHandled() {
        scrollTarget = null
    }

    /**
     * How tall the canvas draws [outlines], and where its content ends, or null when no canvas
     * answered within [timeoutMs] — the editor left the screen, or the page was never drawn.
     */
    suspend fun measure(outlines: List<Outline>, timeoutMs: Long): PasteFrames? {
        val request = CanvasPasteLayoutRequest(outlines)
        pendingLayout = request
        return try {
            withTimeoutOrNull(timeoutMs) { request.result.await() }
        } finally {
            if (pendingLayout === request) pendingLayout = null
        }
    }

    /** The canvas's answer to [pendingLayout]: how tall it draws each outline. */
    fun complete(request: CanvasPasteLayoutRequest, heights: Map<String, Float>) {
        if (pendingLayout !== request) return
        request.result.complete(PasteFrames(heights, contentBottom()))
    }
}
