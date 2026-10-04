package com.vivenotes.ui.shell

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import com.vivenotes.workspace.CanvasViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * What fingers do to the page's view: drag it, fling it, and pinch it — Android's `ScrollStatePan`
 * and the pinch half of its editor pane.
 *
 * Every step scrolls at once with raw deltas, so the page keeps up with the fingers exactly. A zoom
 * is the window's View setting, asked for through [onZoom]; [zoom] is the one the last step asked
 * for, because fingers report faster than the page lays out and chaining from the laid-out zoom
 * would apply one sample's scale to another's. Until the new zoom is laid out, a scroll past the old
 * range is clamped to it; the next step starts from where the scroll got to, so it corrects itself.
 */
internal class CanvasTouchViewport(
    private val horizontal: ScrollState,
    private val vertical: ScrollState,
    private val zoom: () -> Float,
    private val onZoom: (Float) -> Unit,
    private val scope: CoroutineScope,
    private val decay: DecayAnimationSpec<Float>,
) {
    private var glide: Job? = null

    private fun current(): CanvasViewport =
        CanvasViewport(zoom(), horizontal.value.toFloat(), vertical.value.toFloat())

    fun pan(dx: Float, dy: Float) {
        val base = current()
        move(base, base.panBy(dx, dy))
    }

    fun pinch(focus: Offset, pan: Offset, zoomChange: Float) {
        val base = current()
        move(base, base.pinch(focus.x, focus.y, pan.x, pan.y, zoomChange))
    }

    /** The page glides on after the finger leaves at [vx], [vy] pixels per second, as a scroll does. */
    fun fling(vx: Float, vy: Float) {
        stopFling()
        glide = scope.launch {
            launch { glide(horizontal, -vx) }
            launch { glide(vertical, -vy) }
        }
    }

    fun stopFling() {
        glide?.cancel()
        glide = null
    }

    private fun move(base: CanvasViewport, next: CanvasViewport) {
        if (next.zoom != base.zoom) onZoom(next.zoom)
        horizontal.dispatchRawDelta(next.scrollX - base.scrollX)
        vertical.dispatchRawDelta(next.scrollY - base.scrollY)
    }

    private suspend fun glide(state: ScrollState, velocity: Float) {
        // Below this a fling is the tremor at the end of a deliberate drag, and gliding on it makes the
        // page drift after the finger has stopped.
        if (abs(velocity) < MIN_FLING_VELOCITY) return
        var travelled = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(decay) {
            val step = value - travelled
            travelled = value
            // Stopped by an edge: nothing more to glide toward.
            if (abs(state.dispatchRawDelta(step)) < abs(step) / 2) cancelAnimation()
        }
    }

    private companion object {
        const val MIN_FLING_VELOCITY = 50f
    }
}
