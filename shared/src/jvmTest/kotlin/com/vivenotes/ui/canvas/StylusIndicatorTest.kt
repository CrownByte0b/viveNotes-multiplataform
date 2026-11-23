package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.workspace.InkTool
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The circle under a stylus on the page (ViveNotes issue #27), after Android's eraser indicator. */
class StylusIndicatorTest {
    @Test
    fun theCircleIsTheArmedDrawToolsWidth() {
        val base = WorkspaceState.demo()
        val settings = base.inkSettings.withPen(1, base.inkSettings.pen(1).copy(sizeDp = 4f))
            .copy(highlighter = base.inkSettings.highlighter.copy(sizeDp = 24f), eraser = base.inkSettings.eraser.copy(sizeDp = 40f))
        val state = base.copy(inkSettings = settings)
        assertEquals(4f, StylusIndicator.diameterDp(state.selectInkTool(InkTool.Pen, penIndex = 1)), "the active pen's preset")
        assertEquals(settings.pen.sizeDp, StylusIndicator.diameterDp(state.selectInkTool(InkTool.Pen, penIndex = 0)))
        assertEquals(24f, StylusIndicator.diameterDp(state.selectInkTool(InkTool.Highlighter)))
        assertEquals(40f, StylusIndicator.diameterDp(state.selectInkTool(InkTool.Eraser)))
        assertNull(StylusIndicator.diameterDp(state.selectInkTool(InkTool.Laser)), "the laser has its own dot")
        assertNull(StylusIndicator.diameterDp(state), "no Draw tool, no circle")
    }

    private fun pointer(type: PointerType?, phase: CanvasPointer.Phase, at: Offset? = Offset(30f, 40f)) =
        CanvasPointer().apply { position = at; this.type = type; this.phase = phase }

    @Test
    fun onlyAStylusHasTheCircleAndTheEraserKeepsItWhileErasing() {
        val at = Offset(30f, 40f)
        val pen = StylusHover()
        // The mouse is the mouse until the platform says a pen moves it.
        assertNull(pointer(PointerType.Mouse, CanvasPointer.Phase.Hover).stylusIndicatorAt(pen, eraser = false))
        assertNull(pointer(PointerType.Mouse, CanvasPointer.Phase.Hover).stylusIndicatorAt(null, eraser = false))
        pen.active = true
        assertEquals(at, pointer(PointerType.Mouse, CanvasPointer.Phase.Hover).stylusIndicatorAt(pen, eraser = false))
        assertEquals(at, pointer(PointerType.Mouse, CanvasPointer.Phase.Hover).stylusHoverAt(pen))
        // A platform that reports stylus hover as a stylus needs no help.
        assertEquals(at, pointer(PointerType.Stylus, CanvasPointer.Phase.Hover).stylusIndicatorAt(null, eraser = false))
        assertEquals(at, pointer(PointerType.Eraser, CanvasPointer.Phase.Hover).stylusIndicatorAt(null, eraser = false))
        // Touching: a pen's ink is its own feedback; the eraser shows what it erases, as on Android.
        assertNull(pointer(PointerType.Stylus, CanvasPointer.Phase.Contact).stylusIndicatorAt(pen, eraser = false))
        assertEquals(at, pointer(PointerType.Stylus, CanvasPointer.Phase.Contact).stylusIndicatorAt(pen, eraser = true))
        assertNull(pointer(PointerType.Stylus, CanvasPointer.Phase.Contact).stylusHoverAt(pen), "touching is not hovering")
        assertNull(pointer(PointerType.Mouse, CanvasPointer.Phase.Contact).stylusIndicatorAt(pen, eraser = true), "a mouse drag")
        assertNull(pointer(PointerType.Touch, CanvasPointer.Phase.Contact).stylusIndicatorAt(pen, eraser = true), "a finger")
        // Gone: lifted or away from the canvas.
        assertNull(pointer(PointerType.Stylus, CanvasPointer.Phase.Away).stylusIndicatorAt(pen, eraser = true))
        assertNull(pointer(null, CanvasPointer.Phase.Hover, at = null).stylusIndicatorAt(pen, eraser = true))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun itIsDrawnLikeAndroidsEraserIndicatorAtLeastAVisibleDotWide() = runDesktopComposeUiTest(width = 200, height = 100) {
        val diameter = mutableStateOf(80f)
        setContent {
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Color(0xFF808080))
                drawStylusIndicator(Offset(50f, 50f), diameter.value)
            })
        }
        // Relative luminance is linear: this mid grey is about 0.22.
        val background = Color(0xFF808080).luminance()
        val line = density.density
        fun row(): List<Float> {
            val pixels = onRoot().captureToImage().toPixelMap()
            return (0 until 50).map { dx -> pixels[50 + dx, 50].luminance() }
        }
        fun dark(row: List<Float>) = row.indices.filter { row[it] < background / 2f }
        fun light(row: List<Float>) = row.indices.filter { row[it] > background + .2f }

        val wide = row()
        assertTrue(wide[0] > background, "a translucent white disc lightens the page under it")
        val edge = dark(wide)
        assertTrue(edge.isNotEmpty() && edge.all { it.toFloat() in 40f - line - 1f..40f }, "a dark ring at the 80 px circle's edge: $edge")
        assertTrue(light(wide).any { it < edge.first() && it >= edge.first() - line - 1f }, "a light ring just inside it")
        assertTrue(wide.drop(41).all { abs(it - background) < .01f }, "nothing outside the circle")

        // A thin pen is narrower than the smallest circle, which still shows.
        diameter.value = 1.5f
        waitForIdle()
        val thin = row()
        val radius = StylusIndicator.MinimumDiameter.value * line / 2f
        val ring = dark(thin)
        assertTrue(ring.isNotEmpty() && ring.all { it.toFloat() in radius - line - 1f..radius }, "the smallest circle's edge: $ring")
        assertTrue(thin.drop(radius.toInt() + 2).all { abs(it - background) < .01f })
    }
}
