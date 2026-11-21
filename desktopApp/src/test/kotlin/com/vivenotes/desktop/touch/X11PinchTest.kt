package com.vivenotes.desktop.touch

import com.vivenotes.ui.canvas.TouchpadPinch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** XInput 2.4 pinch events, laid out as XCB hands them over. */
class X11PinchTest {
    /**
     * An event as `xcb_input_gesture_pinch_begin_event_t` lays it out (xinput.h, libxcb 1.17): the
     * generic event header, `full_sequence` at 32, then the FP16.16 fields, 104 bytes in all.
     */
    private fun event(type: Int, window: Int, time: Int, rootX: Double, rootY: Double, scale: Double): ByteBuffer =
        ByteBuffer.allocate(104).order(ByteOrder.nativeOrder()).apply {
            fun fixed(value: Double) = Math.round(value * 65536).toInt()
            put(0, 35.toByte()) // GenericEvent
            put(1, 131.toByte()) // the XInput major opcode, whatever the server gave it
            putInt(4, (104 - 32) / 4) // length, in 4-byte units past the first 32 bytes on the wire
            putShort(8, type.toShort())
            putShort(10, 2) // the master pointer
            putInt(12, time)
            putInt(16, 2) // detail: two fingers
            putInt(20, 0x4a1) // root
            putInt(24, window)
            putInt(28, 0) // child
            putInt(32, 0x1234) // full_sequence
            putInt(36, fixed(rootX))
            putInt(40, fixed(rootY))
            putInt(44, fixed(rootX - 100))
            putInt(48, fixed(rootY - 50))
            putInt(52, fixed(-3.5)) // delta_x
            putInt(56, fixed(1.25)) // delta_y
            putInt(68, fixed(scale))
            putInt(72, fixed(4.0)) // delta_angle
            putShort(76, 11) // sourceid: the touchpad
            putInt(100, 0) // flags
        }

    @Test
    fun aPinchCarriesItsWindowTheCursorAndTheSpreadSinceItBegan() {
        assertEquals(X11Pinch(TouchpadPinch.Phase.Begin, 0x2a00007, 5000, 812.5, 440.25, 1.0),
            x11Pinch(event(27, 0x2a00007, 5000, 812.5, 440.25, 1.0)))
        assertEquals(X11Pinch(TouchpadPinch.Phase.Update, 0x2a00007, 5016, 812.5, 440.25, 1.75),
            x11Pinch(event(28, 0x2a00007, 5016, 812.5, 440.25, 1.75)))
        assertEquals(X11Pinch(TouchpadPinch.Phase.End, 0x2a00007, 5040, 812.5, 440.25, 0.5),
            x11Pinch(event(29, 0x2a00007, 5040, 812.5, 440.25, 0.5)))
    }

    @Test
    fun otherEventsAndShortOnesAreNotPinches() {
        // XI_TouchUpdate and XI_GestureSwipeBegin.
        assertNull(x11Pinch(event(19, 7, 0, 0.0, 0.0, 1.0)))
        assertNull(x11Pinch(event(30, 7, 0, 0.0, 0.0, 1.0)))
        // Cut short at the size of a core event.
        assertNull(x11Pinch(event(28, 7, 0, 0.0, 0.0, 1.0).limit(64)))
    }
}
