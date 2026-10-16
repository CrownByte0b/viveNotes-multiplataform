package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.*

class SelectionToolkitPlacementTest {
    @Test
    fun aToolkitFitsAboveTheSelectionOrBelowItWhenThereIsNoRoomAbove() {
        val viewport = IntSize(500, 400)
        val toolkit = IntSize(126, 46)
        assertEquals(IntOffset(100, 168), selectionToolkitPosition(Rect(100f, 220f, 200f, 280f), viewport, toolkit, 6f))
        assertEquals(IntOffset(100, 26), selectionToolkitPosition(Rect(100f, -20f, 200f, 20f), viewport, toolkit, 6f))
    }

    @Test
    fun clippingAndOffscreenSelectionsNeverPutTheToolkitOutsideTheViewport() {
        val viewport = IntSize(500, 400)
        val toolkit = IntSize(126, 46)
        for (bounds in listOf(Rect(-100f, -100f, 800f, 600f), Rect(1000f, 1000f, 1100f, 1100f),
            Rect(-200f, -200f, -100f, -100f), Rect(480f, 380f, 500f, 400f))) {
            val position = selectionToolkitPosition(bounds, viewport, toolkit, 6f)
            assertTrue(position.x >= 6 && position.x + toolkit.width <= viewport.width - 6)
            assertTrue(position.y >= 6 && position.y + toolkit.height <= viewport.height - 6)
        }
    }
}
