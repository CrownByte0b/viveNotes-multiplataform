package com.vivenotes.workspace

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sign

/** Page zoom and scroll offsets, all scroll/anchor coordinates in view pixels. */
data class CanvasViewport(val zoom: Float = 1f, val scrollX: Float = 0f, val scrollY: Float = 0f) {
    val percent: Int get() = (zoom * 100).roundToInt()

    /** Auto-scroll from a fixed middle-click anchor; distance controls speed on each frame. */
    fun autoScrollBy(dx: Float, dy: Float, elapsedSeconds: Float,
                     maxScrollX: Float, maxScrollY: Float): CanvasViewport {
        if (!dx.isFinite() || !dy.isFinite() || !elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return this
        fun step(distance: Float): Float {
            val beyondDeadZone = (abs(distance) - 16f).coerceAtLeast(0f)
            return distance.sign * (beyondDeadZone * 6f).coerceAtMost(1800f) * elapsedSeconds
        }
        return copy(
            scrollX = (scrollX + step(dx)).coerceIn(0f, maxScrollX.coerceAtLeast(0f)),
            scrollY = (scrollY + step(dy)).coerceIn(0f, maxScrollY.coerceAtLeast(0f)),
        )
    }

    /** Keep the page point under the cursor still as a wheel step changes scale. */
    fun wheel(deltaY: Float, cursorX: Float, cursorY: Float): CanvasViewport {
        if (!deltaY.isFinite() || deltaY == 0f) return this
        return zoomTo(zoom * 1.1f.pow((-deltaY).coerceIn(-4f, 4f)), cursorX, cursorY)
    }

    /**
     * Scales to [next], clamped to the View tab's range, keeping the page point at view pixel
     * ([anchorX], [anchorY]) where it is. The ribbon anchors at the window's centre, the wheel at
     * the cursor.
     */
    fun zoomTo(next: Float, anchorX: Float, anchorY: Float): CanvasViewport {
        if (!next.isFinite() || !anchorX.isFinite() || !anchorY.isFinite()) return this
        val clamped = next.coerceIn(ViewSettings.MIN_ZOOM, ViewSettings.MAX_ZOOM)
        val ratio = clamped / zoom
        return CanvasViewport(
            zoom = clamped,
            scrollX = ((scrollX + anchorX) * ratio - anchorX).coerceAtLeast(0f),
            scrollY = ((scrollY + anchorY) * ratio - anchorY).coerceAtLeast(0f),
        )
    }

    /**
     * One step of a two-finger pinch, Android's `pinchStep`: scale by [zoomChange] about [focusX],
     * [focusY] — where the fingers' centre was — and then follow the centre's move by [panX],
     * [panY], so the page point that was between the fingers stays between them. Past the View
     * range the scale stops and the pan goes on.
     */
    fun pinch(focusX: Float, focusY: Float, panX: Float, panY: Float, zoomChange: Float): CanvasViewport {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return this
        return zoomTo(zoom * zoomChange, focusX, focusY).panBy(panX, panY)
    }

    /** Content follows a finger moved by [dx], [dy]: right and down scroll back toward the origin. */
    fun panBy(dx: Float, dy: Float): CanvasViewport {
        if (!dx.isFinite() || !dy.isFinite()) return this
        return copy(scrollX = (scrollX - dx).coerceAtLeast(0f), scrollY = (scrollY - dy).coerceAtLeast(0f))
    }
}
