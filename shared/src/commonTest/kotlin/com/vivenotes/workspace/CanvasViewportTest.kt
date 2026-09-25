package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasViewportTest {
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

    @Test
    fun wheelZoomClampsAndReportsPercent() {
        var viewport = CanvasViewport()
        repeat(20) { viewport = viewport.wheel(-1000f, 0f, 0f) }
        assertEquals(400, viewport.percent)
        repeat(20) { viewport = viewport.wheel(1000f, 0f, 0f) }
        assertEquals(25, viewport.percent)
        assertEquals(viewport, viewport.wheel(Float.NaN, 20f, 20f))
    }
}
