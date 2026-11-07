package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.vivenotes.graph.*
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.Outline
import kotlin.math.abs
import kotlin.math.hypot

/** A graph remains an ordinary Prime Object; only its selected interior handles own gestures. */
@Composable
internal fun GraphContent(
    graph: Outline.Graph,
    selected: Boolean,
    interactive: Boolean,
    paper: Color,
    ink: Color,
    accent: Color,
    pending: GraphDrag?,
    onMutate: (Outline.Graph, GraphDrag) -> Unit,
    onTrace: (Double) -> Unit,
    onArea: (GraphInterval) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val latestGraph by rememberUpdatedState(graph)
    val enabled by rememberUpdatedState(selected && interactive && pending == null)
    val mutate by rememberUpdatedState(onMutate)
    val trace by rememberUpdatedState(onTrace)
    val area by rememberUpdatedState(onArea)
    var pointPreview by remember(graph.id) { mutableStateOf<GraphDrag?>(null) }
    var tracePreview by remember(graph.id) { mutableStateOf<Double?>(null) }
    var areaPreview by remember(graph.id) { mutableStateOf<GraphInterval?>(null) }
    GraphDrawing(graph, paper, ink, accent, pointPreview ?: pending,
        tracePreview ?: graph.traceX, areaPreview ?: graph.area, selected && interactive,
        modifier.pointerInput(graph.id, density) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                if (!enabled) return@awaitEachGesture
                val start = latestGraph
                val function = GraphExpression.parse(start.expression) ?: return@awaitEachGesture
                val frame = GraphFrame(start, 0f, 0f, size.width.toFloat(), size.height.toFloat(), density)
                val window = frame.window
                val points = GraphAnalysis.pointsOfInterest(function::evaluate, window)
                val tracing = start.traceX?.let { x ->
                    val y = function.evaluate(x)
                    y.isFinite() && hypot(frame.canvasX(x) - down.position.x,
                        frame.canvasY(y) - down.position.y) <= GraphFrame.POINT_REACH_DP * density
                } == true
                val point = if (start.mutable && !tracing)
                    frame.pointNear(points, down.position.x, down.position.y) else null
                val bound = if (!tracing && point == null && frame.plot.contains(down.position)) start.area?.let {
                    listOf(true to it.from, false to it.to)
                        .filter { (_, x) -> x in window.xMin..window.xMax }
                        .minByOrNull { (_, x) -> abs(frame.canvasX(x) - down.position.x) }
                        ?.takeIf { (_, x) -> abs(frame.canvasX(x) - down.position.x) <= 14f * density }?.first
                } else null
                if (!tracing && point == null && bound == null) return@awaitEachGesture
                down.consume()
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // A pan/pinch takes over when a second pointer lands.
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        change.consume()
                        if (!change.pressed) {
                            if (dragging && latestGraph == start) {
                                pointPreview?.takeIf { it.changes }?.let { mutate(start, it) }
                                tracePreview?.takeIf { it != start.traceX }?.let { trace(it) }
                                areaPreview?.takeIf { it != start.area }?.let { area(it) }
                            }
                            break
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (!dragging) continue
                        if (point != null) {
                            pointPreview = GraphDrag.of(function, function.polynomial(), point,
                                frame.graphX(change.position.x), frame.graphY(change.position.y), window)
                        } else {
                            val x = frame.graphX(change.position.x).coerceIn(window.xMin, window.xMax)
                            val magnet = 12f * density / frame.plot.width * window.width
                            val snapped = GraphTangent.snap(x, points, magnet, window)
                            if (tracing) tracePreview = snapped
                            else start.area?.let { areaPreview = if (bound == true) it.copy(from = snapped) else it.copy(to = snapped) }
                        }
                    }
                } finally {
                    pointPreview = null; tracePreview = null; areaPreview = null
                }
            }
        })
}

@Composable
internal expect fun GraphDrawing(
    graph: Outline.Graph, paper: Color, ink: Color, accent: Color,
    drag: GraphDrag?, traceX: Double?, area: GraphInterval?, selected: Boolean, modifier: Modifier,
)
