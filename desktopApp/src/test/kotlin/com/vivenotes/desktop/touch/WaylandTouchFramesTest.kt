package com.vivenotes.desktop.touch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `wl_touch` events into frames, including what compositors leave out. */
class WaylandTouchFramesTest {
    private val frames = mutableListOf<SurfaceTouchFrame>()
    private val touch = WaylandTouchFrames(frames::add)

    private fun SurfaceTouchFrame.summary() =
        if (cancelled) "cancel" else changes.joinToString { "${it.phase} ${it.id}@${it.surface}:${it.x.toInt()},${it.y.toInt()}" }

    @Test
    fun aContactKeepsItsSurfaceAndAFrameClosesWhatBelongsTogether() {
        touch.down(1, 100, surface = 7, x = 10.0, y = 20.0)
        touch.down(2, 100, surface = 7, x = 50.0, y = 20.0)
        touch.frame()
        touch.motion(1, 116, x = 12.0, y = 20.0)
        touch.motion(2, 116, x = 48.0, y = 20.0)
        touch.frame()
        touch.up(1, 130)
        touch.frame()

        assertEquals(listOf("Down 1@7:10,20, Down 2@7:50,20", "Move 1@7:12,20, Move 2@7:48,20", "Up 1@7:12,20"),
            frames.map { it.summary() })
        assertEquals(listOf(100L, 116L, 130L), frames.map { it.timeMillis })
    }

    /** Mutter sends no `frame` after a lift; the source closes the frame when its read ends. */
    @Test
    fun aLiftWithoutAFrameWaitsOnlyUntilTheFrameIsClosed() {
        touch.down(1, 100, surface = 7, x = 10.0, y = 20.0)
        touch.frame()
        touch.up(1, 160)
        assertEquals(1, frames.size)
        touch.frame()
        assertEquals("Up 1@7:10,20", frames.last().summary())
        touch.frame()
        assertEquals(2, frames.size, "an empty frame is not sent")
    }

    @Test
    fun aContactsSecondChangeStartsAFrameOfItsOwn() {
        touch.down(1, 100, surface = 7, x = 10.0, y = 20.0)
        touch.motion(1, 108, x = 11.0, y = 20.0)
        touch.frame()
        assertEquals(listOf("Down 1@7:10,20", "Move 1@7:11,20"), frames.map { it.summary() })
    }

    @Test
    fun eventsForUnknownContactsAreDroppedAndCancelForgetsEveryContact() {
        touch.motion(9, 100, x = 1.0, y = 1.0)
        touch.up(9, 100)
        touch.frame()
        assertTrue(frames.isEmpty())

        touch.down(1, 100, surface = 7, x = 10.0, y = 20.0)
        touch.cancel()
        assertEquals(listOf("cancel"), frames.map { it.summary() })
        touch.up(1, 120)
        touch.frame()
        touch.cancel()
        assertEquals(1, frames.size, "nothing left to cancel or lift")
    }

    @Test
    fun deviceTimesKeepTheirSpacingOnTheWallClockAcrossAWrap() {
        var now = 1_000_000L
        val clock = EventClock { now }
        assertEquals(1_000_000L, clock.millis(500))
        now += 16
        assertEquals(1_000_016L, clock.millis(516))
        // A device clock that steps back starts again from the wall clock, never going back itself.
        now += 16
        assertEquals(now, clock.millis(3))
        // The device's 32-bit milliseconds wrap; the wall clock does not.
        assertEquals(now + 10, clock.millis(13))
        now += 1_000
        clock.millis(0xFFFFFFF0.toInt())
        now += 32
        assertEquals(now, clock.millis(0x10))
    }
}
