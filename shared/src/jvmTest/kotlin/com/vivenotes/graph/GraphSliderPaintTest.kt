package com.vivenotes.graph

import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.*

/**
 * The sliders' band as the shared painter draws it — on the page and in a PDF alike: under the plot,
 * one row per parameter, the active track in the curve's colour up to the handle, and play only on a
 * selected graph.
 */
@OptIn(ExperimentalTestApi::class)
class GraphSliderPaintTest {
    private val red = 0xFFE53935.toInt()

    /** A 320 × 332 box at the origin, one dp a pixel: the plot 0…260, then rows centred at 278 and 314. */
    private val graph = Outline.Graph("g", width = 320f, height = 260f + 72f, expression = "((\$0*(x^2))+\$1)",
        showEquation = false, xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0, colorArgb = red,
        parameters = listOf(GraphParameter("a", "a", 1.0), GraphParameter("omega", "ω", -10.0)))

    private fun render(graph: Outline.Graph, controls: Boolean = false, playing: Int? = null): Bitmap =
        Surface.makeRasterN32Premul(320, 332).use { surface ->
            GraphPainter().use { painter ->
                val frame = GraphFrame(graph, 0f, 0f, 320f, 332f, 1f)
                val function = graph.function()!!
                painter.draw(surface.canvas, frame, GraphColors(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), red, 0xFF3584E4.toInt()),
                    function::evaluate, null, emptyList(), null, sliderControls = controls, playing = playing)
            }
            Bitmap().also { bitmap -> surface.makeImageSnapshot().use { bitmap.allocN32Pixels(320, 332); it.readPixels(bitmap) } }
        }

    private fun Bitmap.isCurve(x: Int, y: Int): Boolean {
        val pixel = getColor(x, y)
        return (0..2).all { shift -> abs(((pixel ushr (shift * 8)) and 255) - ((red ushr (shift * 8)) and 255)) < 10 }
    }

    // The painter's paths are Compose's, whose graphics platform a Compose scene registers.
    @Test
    fun theBandSitsUnderThePlotWithItsTrackFilledUpToTheHandle() = runDesktopComposeUiTest(width = 10, height = 10) {
        val frame = GraphFrame(graph, 0f, 0f, 320f, 332f, 1f)
        assertEquals(260f, frame.plot.bottom)
        assertEquals(260f, frame.sliders!!.top)
        assertEquals(SliderHit(1, SliderPart.Track), frame.sliderAt(200f, 320f))
        assertEquals(SliderHit(0, SliderPart.Label), frame.sliderAt(40f, 270f))
        assertEquals(SliderHit(0, SliderPart.Play), frame.sliderAt(310f, 270f))

        val bitmap = render(graph)
        val handle = frame.sliderX(graph.parameters[0], 1.0).toInt()
        assertTrue((frame.trackLeft.toInt() + 4 until handle - 6).all { bitmap.isCurve(it, 278) }, "the active track")
        assertTrue((handle + 8 until frame.trackRight.toInt() - 8).none { bitmap.isCurve(it, 278) }, "the inactive track is faint")
        // ω at its minimum: no active track before the handle, all of it after.
        assertFalse(bitmap.isCurve(frame.trackLeft.toInt() + 20, 314))
    }

    @Test
    fun playIsOfferedOnlyOnASelectedGraphAndPauseWhileItSweeps() = runDesktopComposeUiTest(width = 10, height = 10) {
        val frame = GraphFrame(graph, 0f, 0f, 320f, 332f, 1f)
        val cx = (frame.box.right - GraphFrame.SLIDER_PLAY_DP / 2).toInt()
        fun inked(bitmap: Bitmap): Int = (cx - 6..cx + 6).sumOf { x -> (272..284).count { y -> (bitmap.getColor(x, y) and 0xFF) < 160 } }

        assertEquals(0, inked(render(graph)), "play drawn on an exported or unselected graph")
        val play = inked(render(graph, controls = true))
        assertTrue(play > 10, "no play button")
        // Pause is two bars where play is one triangle: more ink, and a gap down the middle.
        val pause = render(graph, controls = true, playing = 0)
        assertTrue((272..284).none { y -> (pause.getColor(cx, y) and 0xFF) < 160 }, "pause has no gap")
    }

    @Test
    fun aBoxTooSmallForAPlotDrawsNoBand() {
        assertNull(GraphFrame(graph, 0f, 0f, 320f, 140f, 1f).sliders)
        assertNull(GraphFrame(graph, 0f, 0f, 180f, 332f, 1f).sliders)
        assertNull(GraphFrame(graph.copy(parameters = emptyList()), 0f, 0f, 320f, 332f, 1f).sliders)
    }
}
