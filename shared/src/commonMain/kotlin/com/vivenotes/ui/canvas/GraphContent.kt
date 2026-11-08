package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.vivenotes.graph.*
import com.vivenotes.localization.Strings
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.GraphParameter
import com.vivenotes.model.Outline
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A graph remains an ordinary Prime Object; only its selected interior handles own gestures.
 *
 * A selected graph's sliders take a pointer the same way, mutate or not, locked or not: a drag
 * previews every frame and [onParameter] writes the value on the release; play sweeps one until it
 * is paused or the graph is let go, and writes where it stopped. Each row is an accessible slider
 * too.
 */
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
    /** A slider released, paused, or set by assistive technology, at a value not yet snapped. */
    onParameter: (index: Int, value: Double) -> Unit = { _, _ -> },
    /** A slider's name clicked: the Sliders dialog, for its range and an exact value. */
    onEditSliders: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val latestGraph by rememberUpdatedState(graph)
    val held = selected && interactive
    val enabled by rememberUpdatedState(held && pending == null)
    val mutate by rememberUpdatedState(onMutate)
    val trace by rememberUpdatedState(onTrace)
    val area by rememberUpdatedState(onArea)
    val parameter by rememberUpdatedState(onParameter)
    val editSliders by rememberUpdatedState(onEditSliders)
    var pointPreview by remember(graph.id) { mutableStateOf<GraphDrag?>(null) }
    var tracePreview by remember(graph.id) { mutableStateOf<Double?>(null) }
    var areaPreview by remember(graph.id) { mutableStateOf<GraphInterval?>(null) }
    var sliderDrag by remember(graph.id) { mutableStateOf<SliderValue?>(null) }
    var playing by remember(graph.id) { mutableStateOf<SliderPlay?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    /** Writes where play has got to and stops it. */
    fun stopPlaying() {
        val play = playing ?: return
        playing = null
        parameter(play.index, play.value)
    }

    fun togglePlay(index: Int) {
        val now = playing
        stopPlaying()
        if (now?.index != index) {
            latestGraph.parameters.getOrNull(index)?.let { playing = SliderPlay(index, it.value, 1) }
        }
    }

    // Play: one slider at a time, swept a frame at a time. Nothing is written until it stops.
    val playIndex = playing?.index
    LaunchedEffect(playIndex) {
        if (playIndex == null) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val play = playing ?: break
            val slider = latestGraph.parameters.getOrNull(play.index)
            if (slider == null) {
                playing = null
                break
            }
            val (value, direction) = GraphSliders.sweep(slider, play.value, play.direction, now - last)
            playing = play.copy(value = value, direction = direction)
            last = now
        }
    }
    // Letting the graph go — selecting something else, or nothing — pauses it where it is.
    LaunchedEffect(held, playIndex) {
        if (!held && playing != null) stopPlaying()
    }

    // The sliders as they stand this frame: stored, else played, else under the pointer.
    val live = graph.parameters
        .withValue(playing?.let { it.index to it.value })
        .withValue(sliderDrag?.let { it.index to it.value })

    Box(modifier.onSizeChanged { boxSize = it }) {
        GraphDrawing(graph, paper, ink, accent, pointPreview ?: pending,
            tracePreview ?: graph.traceX, areaPreview ?: graph.area, held,
            live, playing?.index, sliderDrag?.index,
            Modifier.fillMaxSize().pointerInput(graph.id, density) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    if (!enabled) return@awaitEachGesture
                    val start = latestGraph
                    val frame = GraphFrame(start, 0f, 0f, size.width.toFloat(), size.height.toFloat(), density)

                    // A slider on a selected graph — locked or not, mutate or not, like the tracer.
                    val hit = frame.sliderAt(down.position.x, down.position.y)
                    if (hit != null) {
                        down.consume()
                        val slider = start.parameters[hit.index]
                        if (hit.part == SliderPart.Track) {
                            // Material's: the handle jumps to the pointer, then follows it.
                            if (playing?.index == hit.index) playing = null
                            sliderDrag = SliderValue(hit.index, slider.snapped(frame.sliderValue(slider, down.position.x)))
                        }
                        var moved = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                // A pan/pinch takes over when a second pointer lands.
                                if (event.changes.count { it.pressed } > 1) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                change.consume()
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (!change.pressed) {
                                    when (hit.part) {
                                        SliderPart.Track -> sliderDrag?.let { drag ->
                                            if (latestGraph == start && !GraphSliders.same(drag.value, slider.value)) {
                                                parameter(hit.index, drag.value)
                                            }
                                        }
                                        SliderPart.Play -> if (!moved) togglePlay(hit.index)
                                        SliderPart.Label -> if (!moved) editSliders()
                                    }
                                    break
                                }
                                if (hit.part == SliderPart.Track) {
                                    sliderDrag = SliderValue(hit.index, slider.snapped(frame.sliderValue(slider, change.position.x)))
                                }
                            }
                        } finally {
                            sliderDrag = null
                        }
                        return@awaitEachGesture
                    }

                    val function = start.function() ?: return@awaitEachGesture
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
                                pointPreview = GraphDrag.of(function::evaluate, function.polynomial(), point,
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
        if (held && boxSize != IntSize.Zero) {
            GraphSliderSemantics(
                graph = graph,
                frame = GraphFrame(graph, 0f, 0f, boxSize.width.toFloat(), boxSize.height.toFloat(), density),
                playingIndex = playing?.index,
                onSet = { index, value -> parameter(index, value) },
                onPlay = ::togglePlay,
                onEdit = { editSliders() },
            )
        }
    }
}

/**
 * Each slider row of [graph] as an adjustable slider for assistive technology, with play as a button
 * beside it. Semantics only, so every pointer still reaches the gesture handler beneath.
 */
@Composable
private fun GraphSliderSemantics(
    graph: Outline.Graph,
    frame: GraphFrame,
    playingIndex: Int?,
    onSet: (index: Int, value: Double) -> Unit,
    onPlay: (index: Int) -> Unit,
    onEdit: () -> Unit,
) {
    if (frame.sliders == null) return
    val density = LocalDensity.current
    val editLabel = Strings.graph_sliders_edit
    val playLabel = Strings.graph_slider_play
    val pauseLabel = Strings.graph_slider_pause
    graph.parameters.forEachIndexed { index, parameter ->
        val row = frame.sliderRow(index) ?: return@forEachIndexed
        val trackEnd = frame.trackRight + GraphFrame.SLIDER_GAP_DP / 2 * frame.unit
        with(density) {
            Box(
                Modifier
                    .offset { IntOffset(row.left.roundToInt(), row.top.roundToInt()) }
                    .size((trackEnd - row.left).toDp(), row.height.toDp())
                    .testTag(GraphSliderTags.slider(index))
                    // Each its own node: the object around the graph is clickable, and a clickable
                    // merges everything inside it into one unless a child is a boundary itself.
                    .semantics(mergeDescendants = true) {
                        contentDescription = parameter.label
                        stateDescription = GraphSliders.number(parameter.value, parameter.step)
                        if (parameter.isUsable) {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                parameter.value.toFloat(),
                                parameter.min.toFloat()..parameter.max.toFloat(),
                            )
                        }
                        setProgress { target ->
                            onSet(index, target.toDouble())
                            true
                        }
                        customActions = listOf(CustomAccessibilityAction(editLabel) { onEdit(); true })
                    },
            )
            Box(
                Modifier
                    .offset { IntOffset(trackEnd.roundToInt(), row.top.roundToInt()) }
                    .size((row.right - trackEnd).toDp(), row.height.toDp())
                    .testTag(GraphSliderTags.play(index))
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "${if (playingIndex == index) pauseLabel else playLabel} ${parameter.label}"
                        onClick {
                            onPlay(index)
                            true
                        }
                    },
            )
        }
    }
}

internal object GraphSliderTags {
    fun slider(index: Int) = "graph-slider-$index"
    fun play(index: Int) = "graph-slider-play-$index"
}

/** A slider's value in flight — under the pointer — before it is written. */
private data class SliderValue(val index: Int, val value: Double)

/** Play sweeping one slider: where it has got to, and which way it is going. */
private data class SliderPlay(val index: Int, val value: Double, val direction: Int)

/** The list with one slider at a value in flight, given as its index and the value. */
private fun List<GraphParameter>.withValue(change: Pair<Int, Double>?): List<GraphParameter> {
    if (change == null) return this
    val (index, value) = change
    val parameter = getOrNull(index) ?: return this
    return toMutableList().also { it[index] = parameter.copy(value = value) }
}

@Composable
internal expect fun GraphDrawing(
    graph: Outline.Graph, paper: Color, ink: Color, accent: Color,
    drag: GraphDrag?, traceX: Double?, area: GraphInterval?, selected: Boolean,
    parameters: List<GraphParameter>, playing: Int?, pressedSlider: Int?, modifier: Modifier,
)
