package com.vivenotes.desktop.touch

import com.vivenotes.ui.canvas.TouchpadPinch
import java.awt.Window

/**
 * A touchpad pinch as the platform reports it, at the cursor in [window]'s own AWT coordinates, the
 * coordinates a mouse event on that window carries. [scale] is the spread since the pinch began, as
 * Wayland and XInput both report it; a begin and an end carry none.
 */
internal data class PinchSample(
    val phase: TouchpadPinch.Phase,
    val window: Window?,
    val x: Double,
    val y: Double,
    val timeMillis: Long,
    val scale: Double = 1.0,
)

/**
 * A pinch whose scale is measured from where it began, as the steps the page zooms by: each one's
 * spread since the step before. A scale that is not a spread (zero, negative, not a number) is left
 * out, and so is anything outside a begin and its end. A begin while a pinch is going on starts again.
 */
internal class TouchpadPinchSteps {
    private var last: Double? = null

    fun accept(sample: PinchSample): TouchpadPinch? = when (sample.phase) {
        TouchpadPinch.Phase.Begin -> {
            last = 1.0
            TouchpadPinch(TouchpadPinch.Phase.Begin)
        }
        TouchpadPinch.Phase.Update -> {
            val previous = last
            val scale = sample.scale
            if (previous == null || !scale.isFinite() || scale <= 0.0) null else {
                last = scale
                TouchpadPinch(TouchpadPinch.Phase.Update, (scale / previous).toFloat())
            }
        }
        TouchpadPinch.Phase.End -> if (last == null) null else {
            last = null
            TouchpadPinch(TouchpadPinch.Phase.End)
        }
    }
}
