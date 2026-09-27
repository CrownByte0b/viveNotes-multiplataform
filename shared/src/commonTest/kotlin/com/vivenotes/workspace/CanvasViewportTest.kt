package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasViewportTest {
    @Test
    fun middleAutoscrollContinuesAtDistanceBasedSpeedAndStopsAtBounds() {
        val start = CanvasViewport(scrollX = 120f, scrollY = 80f)
        val afterFrame = start.autoScrollBy(36f, 26f, 0.1f, 200f, 140f)
        assertEquals(CanvasViewport(scrollX = 132f, scrollY = 86f), afterFrame)
        assertEquals(CanvasViewport(scrollX = 144f, scrollY = 92f),
            afterFrame.autoScrollBy(36f, 26f, 0.1f, 200f, 140f))
        assertEquals(start, start.autoScrollBy(16f, -15f, 1f, 200f, 140f))
        assertEquals(CanvasViewport(scrollX = 0f, scrollY = 0f),
            start.autoScrollBy(-300f, -300f, 1f, 200f, 140f))
        assertEquals(CanvasViewport(scrollX = 200f, scrollY = 140f),
            start.autoScrollBy(300f, 300f, 1f, 200f, 140f))
        assertEquals(start, start.autoScrollBy(Float.NaN, 10f, 1f, 200f, 140f))
        assertEquals(start, start.autoScrollBy(100f, 10f, Float.NaN, 200f, 140f))
    }

    @Test
    fun wheelZoomKeepsPagePointUnderCursor() {
        val before = CanvasViewport(1f, 120f, 80f)
        val after = before.wheel(-2f, 300f, 200f)

        assertTrue(after.zoom > before.zoom)
        assertEquals((before.scrollX + 300f) / before.zoom,
            (after.scrollX + 300f) / after.zoom, 0.001f)
        assertEquals((before.scrollY + 200f) / before.zoom,
            (after.scrollY + 200f) / after.zoom, 0.001f)
    }

    /** The wheel reaches the View tab's whole range, Android's 5% overview included. */
    @Test
    fun wheelZoomClampsAndReportsPercent() {
        var viewport = CanvasViewport()
        repeat(20) { viewport = viewport.wheel(-1000f, 0f, 0f) }
        assertEquals(400, viewport.percent)
        repeat(40) { viewport = viewport.wheel(1000f, 0f, 0f) }
        assertEquals(5, viewport.percent)
        assertEquals(viewport, viewport.wheel(Float.NaN, 20f, 20f))
    }

    @Test
    fun ribbonZoomKeepsTheAnchoredPagePointStill() {
        val before = CanvasViewport(1f, 300f, 200f)
        val after = before.zoomTo(2f, 400f, 250f)

        assertEquals(2f, after.zoom)
        assertEquals((before.scrollX + 400f) / before.zoom, (after.scrollX + 400f) / after.zoom, 0.001f)
        assertEquals((before.scrollY + 250f) / before.zoom, (after.scrollY + 250f) / after.zoom, 0.001f)
    }

    @Test
    fun ribbonZoomClampsToTheViewRangeAndNeverScrollsBeforeTheOrigin() {
        assertEquals(ViewSettings.MAX_ZOOM, CanvasViewport().zoomTo(10f, 0f, 0f).zoom)
        assertEquals(ViewSettings.MIN_ZOOM, CanvasViewport().zoomTo(0.001f, 0f, 0f).zoom)
        val zoomedOut = CanvasViewport(1f, 0f, 0f).zoomTo(0.5f, 400f, 300f)
        assertEquals(0f, zoomedOut.scrollX)
        assertEquals(0f, zoomedOut.scrollY)
        assertEquals(CanvasViewport(), CanvasViewport().zoomTo(Float.NaN, 0f, 0f))
    }
}
