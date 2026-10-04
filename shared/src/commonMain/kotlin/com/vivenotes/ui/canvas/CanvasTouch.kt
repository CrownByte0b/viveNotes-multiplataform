package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs

/**
 * What a finger that lands on the page is for, decided when it lands — Android's split between the
 * ink overlay's tool, its `panPage`, and the page's own scroll.
 */
internal enum class FingerRole {
    /** A drawing tool is armed and fingers may draw: the tool has it. */
    Tool,

    /**
     * A drawing tool is armed but fingers may not draw: the finger moves the page, and the tool never
     * sees it. Two taps in a row offer Paste, as they do with no tool.
     */
    Pan,

    /** Nothing under it to act on: a tap is the page's own, and a drag moves the page. */
    PanAfterSlop,

    /** On an object, its handles or its toolbar: those have it. */
    Content,
}

/** What the page does for a finger. All positions and distances are in the canvas's view pixels. */
internal interface CanvasTouchActions {
    fun roleAt(position: Offset): FingerRole

    /** A finger moved the page by this much; content follows the finger. */
    fun pan(dx: Float, dy: Float)

    /** The panning finger left at this velocity, in pixels per second. */
    fun fling(vx: Float, vy: Float)

    /** A new touch catches a page that is still gliding. */
    fun stopFling()

    /** One pinch sample: the fingers' centre was at [focus], moved by [pan], and spread by [zoomChange]. */
    fun pinch(focus: Offset, pan: Offset, zoomChange: Float)

    /** A finger's second quick tap in one place, while a tool that pans the page is armed. */
    fun doubleTap(position: Offset)
}

/**
 * Touch on the page, as Android's editor has it: two fingers pinch to zoom and move together to pan,
 * anchored on the point between them; one finger pans with a fling unless a tool may use it.
 *
 * Read on the [PointerEventPass.Initial] pass of the page's outermost node, for the reason
 * Android's `detectPinchZoom` gives: everything inside — the ink layer, objects' drags, text fields —
 * owns its pointer, and only an ancestor is asked before them. Consuming a pointer there is how a
 * second finger takes a stroke or a drag back from whoever had it; they already stand down for that.
 *
 * Mouse and stylus input pass through untouched.
 */
internal fun Modifier.canvasTouchGestures(actions: State<CanvasTouchActions>): Modifier =
    pointerInput(Unit) {
        var lastTapTime = -1L
        var lastTapPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch || down.isConsumed) return@awaitEachGesture
            val handler = actions.value
            handler.stopFling()
            val role = handler.roleAt(down.position)
            // The tool would start on the press, so a finger that may not use it takes the press too.
            if (role == FingerRole.Pan) down.consume()
            val slop = viewConfiguration.touchSlop
            val velocity = VelocityTracker().apply { addPointerInputChange(down) }
            var panning = false
            // Past the slop at some point: a drag, not a tap.
            var wandered = false
            var panFinger = down.id
            var pinching = false
            var spread = 1f
            var travel = Offset.Zero
            var lastUp: PointerInputChange? = null

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val touches = event.changes.filter { it.type == PointerType.Touch }
                val pressed = touches.filter { it.pressed }
                if (pressed.isEmpty()) {
                    lastUp = touches.firstOrNull { it.id == down.id }
                    // The lift too, as Android's tracker has it: a finger that came to rest before
                    // lifting leaves no velocity behind.
                    touches.firstOrNull { it.id == panFinger }?.let(velocity::addPointerInputChange)
                    if (pinching || role == FingerRole.Pan) touches.forEach { it.consume() }
                    break
                }
                if (pressed.size >= 2 || pinching) {
                    // A second finger is a pinch, never a fling: the hand has not let go.
                    panning = false
                    if (pressed.size >= 2) {
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        val focus = event.calculateCentroid(useCurrent = false)
                        if (!pinching && focus.isSpecified) {
                            // Android's slop, applied for its reason: two fingers resting on the
                            // page are not yet a gesture.
                            spread *= zoomChange
                            travel += pan
                            val spreadPixels = abs(1 - spread) * event.calculateCentroidSize(useCurrent = false)
                            if (spreadPixels > slop || travel.getDistance() > slop) pinching = true
                        }
                        if (pinching && focus.isSpecified) handler.pinch(focus, pan, zoomChange)
                    }
                    // Every finger, once it is a pinch: a third, or the one left after a lift, would
                    // otherwise still draw or scroll. A pinch does not turn back into a pan.
                    if (pinching || role == FingerRole.Pan) touches.forEach { it.consume() }
                    continue
                }
                val finger = pressed.single()
                if (role != FingerRole.Pan && role != FingerRole.PanAfterSlop) continue
                panFinger = finger.id
                velocity.addPointerInputChange(finger)
                val fromDown = finger.position - down.position
                if (fromDown.getDistance() > slop) wandered = true
                if (panning || role == FingerRole.Pan) {
                    // Android's `panPage`: under a tool, the page follows the finger from its first move.
                    panning = true
                    val moved = finger.position - finger.previousPosition
                    handler.pan(moved.x, moved.y)
                } else if (wandered) {
                    // A scroll's start: the page moves once the finger has passed the slop, from there.
                    panning = true
                    val over = fromDown * ((fromDown.getDistance() - slop) / fromDown.getDistance())
                    handler.pan(over.x, over.y)
                }
                if (panning) finger.consume()
            }

            if (panning && wandered) {
                val released = velocity.calculateVelocity()
                handler.fling(released.x, released.y)
            } else if (role == FingerRole.Pan && !pinching && !wandered && lastUp != null) {
                val up = lastUp
                val interval = up.uptimeMillis - lastTapTime
                val close = (up.position - lastTapPosition).getDistance() <= slop * 2
                if (lastTapTime >= 0 && close &&
                    interval in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis) {
                    lastTapTime = -1L
                    handler.doubleTap(up.position)
                } else {
                    lastTapTime = up.uptimeMillis
                    lastTapPosition = up.position
                }
            }
        }
    }
