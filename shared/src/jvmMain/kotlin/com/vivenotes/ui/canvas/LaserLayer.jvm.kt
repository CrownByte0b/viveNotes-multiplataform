package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.ink.LaserPaint
import com.vivenotes.ink.LaserPointer
import kotlinx.coroutines.flow.collectLatest

/** The clock marks are stamped from and aged against: monotonic, in milliseconds. */
internal fun laserClock(): Long = System.nanoTime() / 1_000_000L

@Composable
internal actual fun LaserLayer(canvasSize: DpSize, zoom: Float, active: Boolean) {
    val laser = remember { LaserPointer() }
    DisposableEffect(laser) { onDispose { laser.close() } }
    val density = LocalDensity.current.density
    val paint = remember(density) { LaserPaint(density) }
    DisposableEffect(paint) { onDispose { paint.close() } }
    val renderer = remember { InkPathRenderer() }
    DisposableEffect(renderer) { onDispose { renderer.clearCache() } }
    val currentZoom by rememberUpdatedState(zoom)
    LaunchedEffect(active) { if (!active) laser.clear() }

    // The laser's clock. Its trace thins away and its dot fades with no pointer event arriving, so
    // the layer has to ask for frames itself — but only while something is actually changing, which
    // is what [LaserPointer.wakeups] restarts this on. A pen held still with its trace spent costs
    // nothing.
    LaunchedEffect(laser) {
        snapshotFlow { laser.wakeups }.collectLatest {
            while (laser.animating) {
                withFrameMillis { laser.tick(laserClock()) }
            }
        }
    }
    if (!active) return

    val input = Modifier.pointerInput(laser, density) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitInkDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) continue
                // The brush is sized for the zoom the press lands at: [TRACE_WIDTH] on the screen.
                val pagePerPixel = 1f / (density * currentZoom)
                laser.press(down.position / density, laserClock(), paint.traceWidth * pagePerPixel,
                    EPSILON_PIXELS * pagePerPixel)
                down.consume()
                var ended = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // A second finger is a pinch or a palm: the dot lets go and the gesture is
                        // passed on, as a stroke in progress does.
                        if (change == null || (down.type == PointerType.Touch &&
                                event.changes.count { it.pressed && it.type == PointerType.Touch } > 1)) {
                            laser.lift(laserClock())
                            ended = true
                            break
                        }
                        // Taken by something else on the way down: never a point being made.
                        if (change.isConsumed) break
                        val now = laserClock()
                        val finished = change.changedToUpIgnoreConsumed() ||
                            (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
                        change.consume()
                        if (finished) {
                            laser.lift(now)
                            ended = true
                            break
                        }
                        // The batched samples as well, or a quick flick reaches the modeller as a few
                        // long chords and the smoothing has nothing between them to work with. Their
                        // spacing is kept, measured back from now.
                        laser.follow(change.historical.map { it.position / density to now - (change.uptimeMillis - it.uptimeMillis) } +
                            (change.position / density to now))
                    }
                } finally {
                    if (!ended) laser.cancelGesture()
                }
            }
        }
    }
    // Draw and input outside the extent, whose child is measured unbounded: see [InkLayer].
    Canvas(Modifier.testTag(LASER_LAYER_TAG).then(input).drawBehind {
        @Suppress("UNUSED_VARIABLE") val revision = laser.renderRevision
        laser.draw(this, density, currentZoom, laserClock(), paint, renderer)
    }.documentExtent(canvasSize)) { }
}

/** A quarter pixel, the finest detail worth meshing at the zoom the press landed at. */
private const val EPSILON_PIXELS = 0.25f
