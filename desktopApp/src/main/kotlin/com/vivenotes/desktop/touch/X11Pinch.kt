package com.vivenotes.desktop.touch

import com.vivenotes.ui.canvas.TouchpadPinch
import java.nio.ByteBuffer

/**
 * An XInput 2.4 touchpad pinch event: [eventWindow] is the X window it was delivered to, the root
 * position is the cursor's, and [scale] the fingers' spread since the pinch began.
 */
internal data class X11Pinch(
    val phase: TouchpadPinch.Phase,
    val eventWindow: Int,
    val time: Int,
    val rootX: Double,
    val rootY: Double,
    val scale: Double,
)

/**
 * A pinch event as XCB hands it over (`xcb_input_gesture_pinch_begin_event_t`, which the update and
 * end share): in the connection's byte order, with XCB's `full_sequence` after the first 32 bytes.
 * Null for any other event, or one too short to be a pinch.
 */
internal fun x11Pinch(event: ByteBuffer): X11Pinch? {
    if (event.limit() < PINCH_EVENT_BYTES) return null
    val phase = when (event.getShort(8).toInt()) {
        27 -> TouchpadPinch.Phase.Begin
        28 -> TouchpadPinch.Phase.Update
        29 -> TouchpadPinch.Phase.End
        else -> return null
    }
    fun fixed(offset: Int) = event.getInt(offset) / 65536.0
    return X11Pinch(phase, eventWindow = event.getInt(24), time = event.getInt(12),
        rootX = fixed(36), rootY = fixed(40), scale = fixed(68))
}

/** Through `flags`, the last field. */
private const val PINCH_EVENT_BYTES = 104
