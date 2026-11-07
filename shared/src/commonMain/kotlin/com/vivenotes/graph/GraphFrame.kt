package com.vivenotes.graph

import androidx.compose.ui.geometry.Rect
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

    val plot = Rect(box.left, header?.bottom ?: box.top, box.right, box.bottom)

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
    }
}

/** What a graph is drawn in. Plain ARGB, so the canvas and a PDF can both supply them. */
data class GraphColors(
    val paper: Int,
    val text: Int,
    val curve: Int,
    val accent: Int,
)

