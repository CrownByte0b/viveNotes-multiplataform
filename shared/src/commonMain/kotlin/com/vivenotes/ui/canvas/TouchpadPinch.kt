package com.vivenotes.ui.canvas

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType

/**
 * One step of a two-finger pinch on a touchpad. Compose has no pointer event for one, so a platform
 * that reads the pinch itself hands it to the scene as a [PointerEventType.Scroll] that scrolls by
 * nothing, at the cursor, with this as the event's `nativeEvent`. Hit testing and coordinates are
 * then the mouse's, and anything under the cursor that is not the page scrolls by nothing.
 *
 * [zoomChange] is how far the fingers spread since the previous step: 1 when the pinch begins.
 */
data class TouchpadPinch(val phase: Phase, val zoomChange: Float = 1f) {
    enum class Phase { Begin, Update, End }
}

/** The touchpad pinch step this event carries, or null for any other event. */
internal val PointerEvent.touchpadPinch: TouchpadPinch?
    get() = if (type == PointerEventType.Scroll) nativeEvent as? TouchpadPinch else null
