package com.vivenotes.desktop.touch

import org.junit.Assume.assumeTrue
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `wl_interface` structs built for pointer gestures, against libwayland-client itself: its own
 * structs read through the same layout, and the opcodes the touch source sends are where libwayland
 * has those requests.
 */
class WaylandInterfacesTest {
    private fun <T> withLibwayland(block: (Arena, (String) -> MemorySegment) -> T): T {
        val arena = Arena.ofConfined()
        try {
            val lib = try {
                SymbolLookup.libraryLookup("libwayland-client.so.0", arena)
            } catch (_: IllegalArgumentException) {
                null
            }
            assumeTrue("libwayland-client is not installed", lib != null)
            return block(arena) { name -> lib!!.find(name).orElseThrow() }
        } finally {
            arena.close()
        }
    }

    @Test
    fun theLayoutReadsLibwaylandsOwnInterfaces() = withLibwayland { _, symbol ->
        val seat = WaylandInterfaces.describe(symbol("wl_seat_interface"))
        assertEquals("wl_seat", seat.name)
        // The touch source's GET_POINTER and GET_TOUCH.
        assertEquals("get_pointer(n wl_pointer)", seat.requests[0])
        assertEquals("get_touch(n wl_touch)", seat.requests[2])

        val pointer = WaylandInterfaces.describe(symbol("wl_pointer_interface"))
        assertEquals("wl_pointer", pointer.name)
        // POINTER_RELEASE, and the events whose listener entries the source fills first.
        assertEquals(listOf("set_cursor(u?oii -,wl_surface,-,-)", "release(3)"), pointer.requests)
        assertEquals(listOf("enter(uoff -,wl_surface,-,-)", "leave(uo -,wl_surface)", "motion(uff)"),
            pointer.events.take(3))
        assertEquals(listOf("button(uuuu)", "axis(uuf)", "frame(5)", "axis_source(5u)", "axis_stop(5uu)",
            "axis_discrete(5ui)"), pointer.events.subList(3, 9))
    }

    @Test
    fun thePinchInterfacesAreThoseOfTheProtocolsFirstVersion() = withLibwayland { arena, symbol ->
        val interfaces = PointerGestureInterfaces(arena, symbol("wl_pointer_interface"), symbol("wl_surface_interface"))

        assertEquals(WaylandInterfaces.Description("zwp_pointer_gesture_pinch_v1", 1,
            requests = listOf("destroy()"),
            events = listOf("begin(uuou -,-,wl_surface,-)", "update(uffff)", "end(uui)")),
            WaylandInterfaces.describe(interfaces.pinch))
        val gestures = WaylandInterfaces.describe(interfaces.gestures)
        assertEquals(WaylandInterfaces.Description("zwp_pointer_gestures_v1", 1,
            requests = listOf("get_swipe_gesture(no -,wl_pointer)",
                "get_pinch_gesture(no zwp_pointer_gesture_pinch_v1,wl_pointer)"),
            events = emptyList()), gestures)
        assertEquals("get_pinch_gesture", gestures.requests[PointerGestureInterfaces.GET_PINCH_GESTURE].substringBefore('('))
    }
}
