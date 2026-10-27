package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import kotlin.test.*

class CanvasDoubleTapTest {
    private fun gesture() = CanvasDoubleTap(40, 300, 10f)
    private fun CanvasDoubleTap.tap(time: Long, type: PointerType = PointerType.Touch, acceptStylus: Boolean = false): Boolean {
        begin(type, time, Offset(100f, 100f), acceptStylus)
        return end(time + 10, Offset(100f, 100f))
    }

    @Test fun fingersAndLassoStylusCanDoubleTapButBrushDotsMouseAndEraserCannot() {
        for (type in listOf(PointerType.Touch, PointerType.Stylus, PointerType.Mouse, PointerType.Eraser)) {
            for (lasso in listOf(false, true)) {
                val g = gesture()
                assertFalse(g.tap(100, type, lasso))
                assertEquals(type == PointerType.Touch || type == PointerType.Stylus && lasso, g.tap(200, type, lasso))
            }
        }
    }

    @Test fun holdingDraggingPinchingAndCancellationBreakTheTapPair() {
        for (interrupt in listOf<(CanvasDoubleTap) -> Unit>(
            { it.begin(PointerType.Touch, 150, Offset(100f, 100f)); it.end(600, Offset(100f, 100f)) },
            { it.begin(PointerType.Touch, 150, Offset(100f, 100f)); it.move(Offset(130f, 100f)); it.end(160, Offset(100f, 100f)) },
            { it.cancel() },
        )) {
            val g = gesture()
            assertFalse(g.tap(100))
            interrupt(g)
            assertFalse(g.tap(650))
        }
    }

    @Test fun platformTimingDistanceAndPairResetAreRespected() {
        val g = gesture()
        assertFalse(g.tap(100))
        assertFalse(g.tap(120), "too soon")
        assertTrue(g.tap(200))
        assertFalse(g.tap(260), "the successful pair was spent")
        g.begin(PointerType.Touch, 350, Offset(200f, 100f))
        assertFalse(g.end(360, Offset(200f, 100f)), "too far from the first tap")
        assertFalse(g.tap(900), "too late")
    }

    @Test fun theDesktopCanvasCanAlsoKeepItsMouseDoubleClickPasteShortcut() {
        val g = gesture()
        g.begin(PointerType.Mouse, 100, Offset.Zero, acceptMouse = true)
        assertFalse(g.end(110, Offset.Zero))
        g.begin(PointerType.Mouse, 200, Offset.Zero, acceptMouse = true)
        assertTrue(g.end(210, Offset.Zero))
    }
}
