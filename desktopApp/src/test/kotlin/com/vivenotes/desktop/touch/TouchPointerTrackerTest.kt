package com.vivenotes.desktop.touch

import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pointer sequence a Compose scene is given for touch, which is Android's. */
class TouchPointerTrackerTest {
    // A window is only an identity here; nothing is shown.
    private val window = Frame()
    private val other = Frame()

    private fun tracker(resolve: (Double, Double) -> String? = { _, _ -> "scene" }) =
        TouchPointerTracker<String>({ _, x, y -> resolve(x, y) }, nextPointerId = 1)

    private fun frame(vararg changes: TouchChange) = TouchFrame(100, changes.toList())
    private fun down(id: Long, x: Double, y: Double, on: Frame = window) = TouchChange(id, TouchPhase.Down, on, x, y)
    private fun move(id: Long, x: Double, y: Double) = TouchChange(id, TouchPhase.Move, window, x, y)
    private fun up(id: Long) = TouchChange(id, TouchPhase.Up, null, 0.0, 0.0)

    private fun TouchDispatch<String>.summary(): String = when (this) {
        is TouchDispatch.Cancel -> "cancel $target"
        is TouchDispatch.Event -> "$type " + pointers.joinToString { "${it.pointerId}@${it.x.toInt()},${it.y.toInt()}" +
            if (it.pressed) "" else " up" }
    }

    @Test
    fun aTapIsAPressAndARelease() {
        val touch = tracker()
        assertEquals(listOf("Press 1@10,20"), touch.accept(frame(down(7, 10.0, 20.0))).map { it.summary() })
        assertEquals(listOf("Move 1@15,20"), touch.accept(frame(move(7, 15.0, 20.0))).map { it.summary() })
        assertEquals(listOf("Release 1@15,20 up"), touch.accept(frame(up(7))).map { it.summary() })
        assertTrue(!touch.active)
        // The next touch is a pointer of its own.
        assertEquals(listOf("Press 2@1,1"), touch.accept(frame(down(7, 1.0, 1.0))).map { it.summary() })
    }

    @Test
    fun aSecondFingerJoinsTheFirstAndEveryEventListsBoth() {
        val touch = tracker()
        touch.accept(frame(down(1, 10.0, 10.0)))
        // A frame that moves the first finger and lands a second: the move comes first, without it.
        assertEquals(listOf("Move 1@12,10", "Press 1@12,10, 2@50,10"),
            touch.accept(frame(move(1, 12.0, 10.0), down(2, 50.0, 10.0))).map { it.summary() })
        assertEquals(listOf("Move 1@8,10, 2@54,10"),
            touch.accept(frame(move(1, 8.0, 10.0), move(2, 54.0, 10.0))).map { it.summary() })
        assertEquals(listOf("Release 1@8,10 up, 2@54,10"), touch.accept(frame(up(1))).map { it.summary() })
        assertEquals(listOf("Move 2@60,10"), touch.accept(frame(move(2, 60.0, 10.0))).map { it.summary() })
    }

    @Test
    fun aFingerStaysWithTheSceneItLandedOnAndASecondJoinsIt() {
        val touch = tracker { x, _ -> if (x < 100) "left" else "right" }
        val pressed = touch.accept(frame(down(1, 10.0, 10.0)))
        assertEquals("left", pressed.single().target)
        // Landing on the right half, but the gesture already belongs to the left scene.
        assertEquals("left", touch.accept(frame(down(2, 150.0, 10.0))).single().target)
        assertEquals("left", touch.accept(frame(move(1, 150.0, 10.0))).single().target)
    }

    @Test
    fun aFingerLandingOnNothingIsIgnoredUntilItLifts() {
        val touch = tracker { x, _ -> if (x < 100) "scene" else null }
        assertEquals(emptyList(), touch.accept(frame(down(1, 150.0, 10.0))))
        assertEquals(emptyList(), touch.accept(frame(move(1, 20.0, 10.0))))
        assertEquals(emptyList(), touch.accept(frame(up(1))))
        assertEquals(listOf("Press 1@20,10"), touch.accept(frame(down(2, 20.0, 10.0))).map { it.summary() })
    }

    @Test
    fun separateWindowsGetSeparateScenes() {
        val touch = TouchPointerTracker<String>({ w, _, _ -> if (w === window) "main" else "other" }, 1)
        assertEquals("main", touch.accept(frame(down(1, 1.0, 1.0))).single().target)
        assertEquals("other", touch.accept(frame(down(2, 1.0, 1.0, on = other))).single().target)
    }

    @Test
    fun aCancelStopsEverySceneThatHadATouchAndForgetsThem() {
        val touch = tracker { x, _ -> if (x < 100) "left" else "right" }
        touch.accept(frame(down(1, 10.0, 10.0)))
        touch.accept(frame(down(2, 150.0, 10.0, on = other)))
        assertEquals(listOf("cancel left", "cancel right"),
            touch.accept(TouchFrame(5, emptyList(), cancelled = true)).map { it.summary() })
        assertEquals(emptyList(), touch.accept(frame(up(1))))
        assertTrue(!touch.active)
    }

    @Test
    fun framesWithoutAFrameEventSplitWhenAContactRepeats() {
        val frames = mutableListOf<TouchFrame>()
        val builder = TouchFrameBuilder(frames::add)
        builder.add(move(1, 1.0, 1.0), 10)
        builder.add(move(2, 2.0, 2.0), 11)
        builder.add(move(1, 3.0, 3.0), 12)
        builder.flush()
        builder.flush()
        assertEquals(listOf(listOf(1L, 2L), listOf(1L)), frames.map { frame -> frame.changes.map { it.id } })
        assertEquals(listOf(11L, 12L), frames.map { it.timeMillis })
    }
}
