package com.vivenotes.desktop.touch

import com.vivenotes.ui.canvas.TouchpadPinch

/** A touchpad pinch's step on a Wayland surface, at the cursor, in that surface's coordinates. */
internal data class SurfacePinch(
    val phase: TouchpadPinch.Phase,
    val surface: Long,
    val x: Double,
    val y: Double,
    val timeMillis: Long,
    val scale: Double = 1.0,
)

/**
 * `zwp_pointer_gesture_pinch_v1` events placed at the cursor. A pinch reports no position of its
 * own, and the cursor stays where it is while the fingers are on the touchpad, so a pinch is where
 * the seat's `wl_pointer` last saw the cursor. One that begins on a surface the cursor is not known
 * to be on is left out: there is no point to zoom about.
 *
 * Keyed by seat. A seat's pinch that is going on when its pointer goes ends there.
 */
internal class WaylandPinchGestures(private val emit: (SurfacePinch) -> Unit) {
    private class Cursor(var surface: Long, var x: Double, var y: Double)
    private class Pinch(val surface: Long, val x: Double, val y: Double, var time: Long)

    private val cursors = HashMap<Int, Cursor>()
    private val pinches = HashMap<Int, Pinch>()

    fun enter(seat: Int, surface: Long, x: Double, y: Double) {
        cursors[seat] = Cursor(surface, x, y)
    }

    fun motion(seat: Int, x: Double, y: Double) {
        val cursor = cursors[seat] ?: return
        cursor.x = x
        cursor.y = y
    }

    fun leave(seat: Int) {
        cursors.remove(seat)
    }

    fun begin(seat: Int, timeMillis: Long, surface: Long) {
        val cursor = cursors[seat]?.takeIf { it.surface == surface }
        if (cursor == null) {
            pinches.remove(seat)
            return
        }
        val pinch = Pinch(surface, cursor.x, cursor.y, timeMillis)
        pinches[seat] = pinch
        emit(SurfacePinch(TouchpadPinch.Phase.Begin, pinch.surface, pinch.x, pinch.y, timeMillis))
    }

    fun update(seat: Int, timeMillis: Long, scale: Double) {
        val pinch = pinches[seat] ?: return
        pinch.time = timeMillis
        emit(SurfacePinch(TouchpadPinch.Phase.Update, pinch.surface, pinch.x, pinch.y, timeMillis, scale))
    }

    /** The fingers left the touchpad, or the compositor took the gesture: either way it is over. */
    fun end(seat: Int, timeMillis: Long) {
        val pinch = pinches.remove(seat) ?: return
        emit(SurfacePinch(TouchpadPinch.Phase.End, pinch.surface, pinch.x, pinch.y, timeMillis))
    }

    /** The seat has no pointer any more; a pinch going on ends at its last step's time. */
    fun pointerGone(seat: Int) {
        pinches[seat]?.let { end(seat, it.time) }
        cursors.remove(seat)
    }
}
