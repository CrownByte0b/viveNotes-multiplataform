package com.vivenotes.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import kotlin.math.hypot

/**
 * Where a graph's parts sit inside its box, and how its units map onto the canvas.
 *
 * [unit] is canvas units per dp: the display density on the page, 1 in a PDF, whose canvas is
 * already in dp. Every size below is in dp times [unit], so a tick label is the same size on paper
 * as on the screen.
 */
class GraphFrame(
    val graph: Outline.Graph,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    val unit: Float,
    val window: GraphWindow = graph.window(),
) {
    val box = Rect(left, top, left + width, top + height)

    /** The band the equation is drawn in, or null when it is hidden or the box is too short for it. */
    val header: Rect? = if (graph.showEquation && height >= HEADER_DP * unit * 2.5f) {
        Rect(box.left, box.top, box.right, box.top + HEADER_DP * unit)
    } else {
        null
    }

    /**
     * The sliders' band at the foot of the box, one [SLIDER_ROW_DP] row per parameter; null when
     * there are none, or when the box is too small to keep a plot above them.
     */
    val sliders: Rect? = graph.parameters.size.takeIf { it > 0 }?.let { rows ->
        val band = SLIDER_ROW_DP * unit * rows
        val above = header?.bottom ?: box.top
        val fits = box.bottom - band - above >= MIN_PLOT_DP * unit && width >= MIN_SLIDER_WIDTH_DP * unit
        if (fits) Rect(box.left, box.bottom - band, box.right, box.bottom) else null
    }

    val plot = Rect(box.left, header?.bottom ?: box.top, box.right, sliders?.top ?: box.bottom)

    /** Row [index] of the band. */
    fun sliderRow(index: Int): Rect? = sliders?.let { band ->
        val top = band.top + index * SLIDER_ROW_DP * unit
        Rect(band.left, top, band.right, top + SLIDER_ROW_DP * unit)
    }

    /** Where every row's track starts and ends: the same for all, so their handles line up. */
    val trackLeft: Float get() = box.left + (SLIDER_PAD_DP + SLIDER_LABEL_DP + SLIDER_GAP_DP) * unit
    val trackRight: Float get() = box.right - (SLIDER_PLAY_DP + SLIDER_GAP_DP / 2) * unit

    /** The handle's x for [value] on [parameter]'s range. */
    fun sliderX(parameter: GraphParameter, value: Double): Float {
        if (!parameter.isUsable) return trackLeft
        val fraction = ((value - parameter.min) / (parameter.max - parameter.min)).coerceIn(0.0, 1.0)
        return (trackLeft + fraction * (trackRight - trackLeft)).toFloat()
    }

    /** The value under a canvas x on [parameter]'s track, unsnapped. */
    fun sliderValue(parameter: GraphParameter, canvasX: Float): Double {
        val fraction = ((canvasX - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        return parameter.min + fraction * (parameter.max - parameter.min)
    }

    /** Which row, and which part of it, a canvas position falls on; null outside the band. */
    fun sliderAt(x: Float, y: Float): SliderHit? {
        val band = sliders ?: return null
        if (!band.contains(Offset(x, y))) return null
        val index = ((y - band.top) / (SLIDER_ROW_DP * unit)).toInt().coerceIn(0, graph.parameters.lastIndex)
        val part = when {
            x >= trackRight + SLIDER_GAP_DP / 2 * unit -> SliderPart.Play
            x >= trackLeft - SLIDER_GAP_DP * unit -> SliderPart.Track
            else -> SliderPart.Label
        }
        return SliderHit(index, part)
    }

    fun canvasX(x: Double): Float = (plot.left + (x - window.xMin) / window.width * plot.width).toFloat()
    fun canvasY(y: Double): Float = (plot.bottom - (y - window.yMin) / window.height * plot.height).toFloat()
    fun graphX(canvasX: Float): Double = window.xMin + (canvasX - plot.left) / plot.width * window.width
    fun graphY(canvasY: Float): Double = window.yMin + (plot.bottom - canvasY) / plot.height * window.height

    /** The point of interest within [reachDp] of a canvas position, nearest first. */
    fun pointNear(points: List<GraphPoint>, x: Float, y: Float, reachDp: Float = POINT_REACH_DP): GraphPoint? =
        points
            .filter { it.x in window.xMin..window.xMax && it.y in window.yMin..window.yMax }
            .minByOrNull { hypot(canvasX(it.x) - x, canvasY(it.y) - y) }
            ?.takeIf { hypot(canvasX(it.x) - x, canvasY(it.y) - y) <= reachDp * unit }

    companion object {
        const val HEADER_DP = 36f

        /** How far from a point a finger may land and still take it — a touch target, not the dot. */
        const val POINT_REACH_DP = 22f

        /** One slider: tall enough to take a finger, short enough that five leave a plot. */
        const val SLIDER_ROW_DP = 36f

        /** The plot the band always leaves above it; below this the sliders are not drawn. */
        const val MIN_PLOT_DP = 80f

        /** The name and value, `ω = −2.5`, ahead of the track. */
        const val SLIDER_LABEL_DP = 84f
        const val SLIDER_PAD_DP = 12f
        const val SLIDER_GAP_DP = 10f

        /** Play at the end of the row: its touch target. */
        const val SLIDER_PLAY_DP = 36f

        /** Label, a usable track and play: narrower than this the band is not drawn. */
        const val MIN_SLIDER_WIDTH_DP = 200f
    }
}

/** A position in the sliders' band: row [index], and whether it is the name, the track or play. */
data class SliderHit(val index: Int, val part: SliderPart)

enum class SliderPart { Label, Track, Play }

/** What a graph is drawn in. Plain ARGB, so the canvas and a PDF can both supply them. */
data class GraphColors(
    val paper: Int,
    val text: Int,
    val curve: Int,
    val accent: Int,
)

