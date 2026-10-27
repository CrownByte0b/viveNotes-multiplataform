package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType

/** Android's stationary, single-pointer paste gesture, using the platform's timing and slop. */
internal class CanvasDoubleTap(private val minimum: Long, private val maximum: Long, private val slop: Float) {
    private var downAt = -1L
    private var down = Offset.Zero
    private var firstAt = -1L
    private var first = Offset.Zero
    private var still = false

    fun begin(type: PointerType, time: Long, position: Offset, acceptStylus: Boolean = false, acceptMouse: Boolean = false) {
        if (type != PointerType.Touch && !(type == PointerType.Stylus && acceptStylus) &&
            !(type == PointerType.Mouse && acceptMouse)) { cancel(); return }
        downAt = time
        down = position
        still = true
    }

    fun move(position: Offset) {
        if ((position - down).getDistance() > slop) still = false
    }

    fun end(time: Long, position: Offset): Boolean {
        move(position)
        val valid = downAt >= 0 && still && time - downAt in 0..maximum
        downAt = -1L
        if (!valid) { cancel(); return false }
        if (firstAt >= 0 && time - firstAt in minimum..maximum && (position - first).getDistance() <= slop * 2) {
            cancel()
            return true
        }
        firstAt = time
        first = position
        return false
    }

    fun cancel() { downAt = -1L; firstAt = -1L; still = false }
}
