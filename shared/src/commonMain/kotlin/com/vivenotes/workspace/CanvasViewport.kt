package com.vivenotes.workspace

import kotlin.math.pow
import kotlin.math.roundToInt

/** Page zoom and scroll offsets, all scroll/anchor coordinates in view pixels. */
data class CanvasViewport(val zoom: Float = 1f, val scrollX: Float = 0f, val scrollY: Float = 0f) {
    val percent: Int get() = (zoom * 100).roundToInt()

    /** Keep the page point under the cursor still as a wheel step changes scale. */
    fun wheel(deltaY: Float, cursorX: Float, cursorY: Float): CanvasViewport {
        if (!deltaY.isFinite() || deltaY == 0f) return this
        val next = (zoom * 1.1f.pow((-deltaY).coerceIn(-4f, 4f))).coerceIn(0.25f, 4f)
        val ratio = next / zoom
        return CanvasViewport(
            zoom = next,
            scrollX = ((scrollX + cursorX) * ratio - cursorX).coerceAtLeast(0f),
            scrollY = ((scrollY + cursorY) * ratio - cursorY).coerceAtLeast(0f),
        )
    }
}
