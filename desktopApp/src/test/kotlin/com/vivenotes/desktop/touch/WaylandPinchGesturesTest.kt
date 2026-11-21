package com.vivenotes.desktop.touch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `zwp_pointer_gesture_pinch_v1` events placed at the cursor its seat's `wl_pointer` follows. */
class WaylandPinchGesturesTest {
    private val seen = mutableListOf<SurfacePinch>()
    private val pinches = WaylandPinchGestures(seen::add)

    private fun SurfacePinch.summary() = "$phase@$surface:${x.toInt()},${y.toInt()} x$scale t$timeMillis"

    @Test
    fun aPinchIsWhereTheCursorLastWasOnItsSurface() {
        pinches.enter(seat = 0, surface = 7, x = 10.0, y = 20.0)
        pinches.motion(seat = 0, x = 300.5, y = 200.25)
        pinches.begin(seat = 0, timeMillis = 100, surface = 7)
        pinches.update(seat = 0, timeMillis = 116, scale = 1.5)
        pinches.update(seat = 0, timeMillis = 132, scale = 2.0)
        pinches.end(seat = 0, timeMillis = 140)

        assertEquals(listOf("Begin@7:300,200 x1.0 t100", "Update@7:300,200 x1.5 t116",
            "Update@7:300,200 x2.0 t132", "End@7:300,200 x1.0 t140"), seen.map { it.summary() })
        assertEquals(300.5, seen.first().x)
        assertEquals(200.25, seen.first().y)
    }

    /** The cursor does not move while the fingers are on the touchpad; one that did would not move the pinch. */
    @Test
    fun aPinchStaysWhereItBegan() {
        pinches.enter(seat = 0, surface = 7, x = 50.0, y = 60.0)
        pinches.begin(seat = 0, timeMillis = 100, surface = 7)
        pinches.motion(seat = 0, x = 400.0, y = 400.0)
        pinches.update(seat = 0, timeMillis = 116, scale = 1.5)
        assertEquals(listOf("Begin@7:50,60 x1.0 t100", "Update@7:50,60 x1.5 t116"), seen.map { it.summary() })
    }

    @Test
    fun aPinchOnASurfaceTheCursorIsNotKnownToBeOnIsLeftOut() {
        pinches.begin(seat = 0, timeMillis = 100, surface = 7)
        pinches.enter(seat = 0, surface = 8, x = 10.0, y = 10.0)
        pinches.begin(seat = 0, timeMillis = 110, surface = 7)
        pinches.update(seat = 0, timeMillis = 116, scale = 1.5)
        pinches.end(seat = 0, timeMillis = 130)
        pinches.enter(seat = 0, surface = 7, x = 10.0, y = 10.0)
        pinches.leave(seat = 0)
        pinches.begin(seat = 0, timeMillis = 140, surface = 7)
        pinches.update(seat = 0, timeMillis = 150, scale = 1.5)
        assertTrue(seen.isEmpty(), seen.map { it.summary() }.toString())
    }

    @Test
    fun seatsKeepTheirOwnCursorAndPinch() {
        pinches.enter(seat = 0, surface = 7, x = 10.0, y = 10.0)
        pinches.enter(seat = 1, surface = 7, x = 90.0, y = 90.0)
        pinches.begin(seat = 1, timeMillis = 100, surface = 7)
        pinches.update(seat = 0, timeMillis = 110, scale = 3.0)
        pinches.update(seat = 1, timeMillis = 116, scale = 1.5)
        assertEquals(listOf("Begin@7:90,90 x1.0 t100", "Update@7:90,90 x1.5 t116"), seen.map { it.summary() })
    }

    @Test
    fun aPointerThatGoesEndsItsPinchAtTheLastStep() {
        pinches.enter(seat = 0, surface = 7, x = 10.0, y = 10.0)
        pinches.begin(seat = 0, timeMillis = 100, surface = 7)
        pinches.update(seat = 0, timeMillis = 116, scale = 1.5)
        pinches.pointerGone(seat = 0)
        pinches.update(seat = 0, timeMillis = 132, scale = 2.0)
        pinches.begin(seat = 0, timeMillis = 140, surface = 7)
        assertEquals(listOf("Begin@7:10,10 x1.0 t100", "Update@7:10,10 x1.5 t116", "End@7:10,10 x1.0 t116"),
            seen.map { it.summary() })
    }
}
