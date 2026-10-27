package com.vivenotes.desktop.touch

import androidx.compose.ui.input.pointer.PointerType
import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkInputEvent
import com.vivenotes.byteink.compose.InkPointerSample
import java.awt.Frame
import kotlin.test.*

class NativePointerFramesTest {
    @Test fun nativeHistoryPreservesPressureTypeTimeAndFinalPositionWithoutPredictions() {
        val window = Frame()
        try {
            val events = mutableListOf<TouchDispatch<String>>()
            val tracker = TouchPointerTracker<String>({ _, _, _ -> "canvas" })
            val source = NativePointerFrames(window, mouse = false, touch = false, emit = { events += tracker.accept(it) })
            fun sample(x: Float, time: Long, p: Float) = InkPointerSample(x, 10f, time, InputToolType.STYLUS, p)
            source.accept(InkInputEvent.Begin(sample(10f, 10, .1f), 7))
            source.accept(InkInputEvent.Batch(listOf(sample(11f, 11, .3f), sample(12f, 12, .8f)), listOf(sample(99f, 99, 1f)), 7))
            source.accept(InkInputEvent.Finish(sample(13f, 13, 0f), 7))
            val real = events.filterIsInstance<TouchDispatch.Event<String>>()
            assertEquals(listOf(10L, 11L, 12L, 13L, 13L), real.map { it.timeMillis })
            assertEquals(listOf(.1f, .3f, .8f, 0f, 0f), real.map { it.pointers.single().pressure })
            assertTrue(real.all { it.pointers.single().type == PointerType.Stylus })
            assertEquals(13.0, real.last().pointers.single().x)
            assertFalse(real.last().pointers.single().pressed)
        } finally { window.dispose() }
    }

    @Test fun linuxKeepsItsExistingTouchPathAndWindowsAcceptsMultipleTouchContacts() {
        val window = Frame()
        try {
            for (touch in listOf(false, true)) {
                val frames = mutableListOf<TouchFrame>()
                val source = NativePointerFrames(window, mouse = false, touch = touch, emit = { frames += it })
                val sample = InkPointerSample(10f, 10f, 10, InputToolType.TOUCH)
                source.accept(InkInputEvent.Begin(sample, 1)); source.accept(InkInputEvent.Begin(sample.copy(x = 50f), 2))
                assertEquals(if (touch) 2 else 0, frames.size)
                if (touch) {
                    val tracker = TouchPointerTracker<String>({ _, _, _ -> "canvas" })
                    val events = frames.flatMap(tracker::accept).filterIsInstance<TouchDispatch.Event<String>>()
                    assertEquals(2, events.last().pointers.size)
                    assertTrue(events.last().pointers.all { it.type == PointerType.Touch })
                }
            }
        } finally { window.dispose() }
    }

    @Test fun nativeCancellationIsScopedToItsWindowAndLateSamplesCannotFinishAStroke() {
        val window = Frame()
        val other = Frame()
        try {
            val events = mutableListOf<TouchDispatch<String>>()
            val tracker = TouchPointerTracker<String>({ w, _, _ -> if (w === window) "main" else "other" })
            val source = NativePointerFrames(window, mouse = false, touch = false, emit = { events += tracker.accept(it) })
            tracker.accept(TouchFrame(1, listOf(TouchChange(1, TouchPhase.Down, other, 1.0, 1.0))))
            val sample = InkPointerSample(10f, 10f, 10, InputToolType.STYLUS)
            source.accept(InkInputEvent.Begin(sample, 7))
            source.accept(InkInputEvent.CancelPointer(7))
            source.accept(InkInputEvent.Finish(sample, 7))
            assertEquals(listOf("main"), events.filterIsInstance<TouchDispatch.Cancel<String>>().map { it.target })
            assertTrue(tracker.active, "the other window still has its finger")
            assertEquals(1, events.filterIsInstance<TouchDispatch.Event<String>>().size)
        } finally { window.dispose(); other.dispose() }
    }
}
