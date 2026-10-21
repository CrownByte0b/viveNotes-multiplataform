package com.vivenotes.ui.canvas

import com.vivenotes.workspace.InkSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.automaticColorOr
import com.vivenotes.data.InkEdit
import com.vivenotes.data.EraserMode
import com.vivenotes.ink.DesktopPartialEraseGesture
import com.vivenotes.ink.DesktopInkSegment
import com.vivenotes.ink.DesktopErasePreparer
import com.vivenotes.ink.PartialEraseRequest
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.desktopEdit
import com.vivenotes.ink.byteInkTool
import com.vivenotes.ink.withDesktopEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.newId
import com.vivenotes.workspace.InkTool
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.delay
import com.vivenotes.model.ink.RulerPoint
import com.vivenotes.model.ink.Ruler
import com.vivenotes.model.ink.StraightenHold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

private class InkSegmentSceneData(val scene: InkScene, val byRow: Map<String, List<InkSceneStroke>>,
    val byProjection: Map<com.vivenotes.byteink.kit.InkProjectionKey, InkSceneStroke>)

@Composable
internal actual fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
    eraserMode: EraserMode,
    settings: InkSettings,
    selection: com.vivenotes.model.ink.InkSelection?,
    selectionOffset: androidx.compose.ui.geometry.Offset,
    penIndex: Int,
    ruler: com.vivenotes.model.ink.Ruler?,
    onStraighten: (com.vivenotes.model.ink.StraightLine, com.vivenotes.workspace.InkPenSettings) -> Unit,
) {
    if (pageId.isEmpty()) return
    val snapshot = page ?: remember(pageId) { InkPage(pageId, emptyList()) }
    val native = remember(snapshot) { snapshot.desktopGeometry() }
    if (tool == null && native.isEmpty) return
    val density = LocalDensity.current.density
    val diagnostics = LocalInkLayerDiagnostics.current
    val transform = remember(density) { ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f) }
    val renderer = remember(pageId) { InkPathRenderer() }
    val rasterCache = remember(pageId) { SegmentedInkRasterCache() }
    val additionsCache = remember(pageId) { InkSceneRasterCache() }
    val controller = remember(pageId) { InkAuthoringController() }
    var frameRevision by remember(controller) { mutableLongStateOf(0L) }
    DisposableEffect(controller) { onDispose {
        controller.close()
        rasterCache.close()
        additionsCache.close()
        diagnostics?.rasterReleased()
        renderer.clearCache()
    } }
    val pen = remember(tool, canvasInk, settings, penIndex) { settings.byteInkTool(tool, canvasInk.toArgb(), penIndex) }
    val currentPen by rememberUpdatedState(pen)
    val currentSettings by rememberUpdatedState(settings)
    val currentRuler by rememberUpdatedState(ruler)
    val currentStraighten by rememberUpdatedState(onStraighten)
    var holdCandidate by remember(controller) { mutableStateOf<HoldCandidate?>(null) }
    var holdRevision by remember(controller) { mutableIntStateOf(0) }
    LaunchedEffect(holdCandidate, holdRevision) {
        val candidate = holdCandidate ?: return@LaunchedEffect
        delay(1000)
        if (holdCandidate !== candidate || !controller.isDrawing) return@LaunchedEffect
        val line = candidate.hold.line() ?: return@LaunchedEffect
        candidate.hold.spend()
        controller.cancel()
        frameRevision++
        candidate.commit(line)
    }
    val currentPage by rememberUpdatedState(remember(snapshot, native) {
        if (snapshot.geometry === native) snapshot else snapshot.copy(geometry = native)
    })
    val currentNative by rememberUpdatedState(native)
    val currentCallback by rememberUpdatedState(onEdit)
    var erasing by remember(pageId, tool) { mutableStateOf(emptySet<String>()) }
    var eraserPoint by remember(pageId, tool) { mutableStateOf<Offset?>(null) }
    var eraseRequest by remember(pageId, tool, eraserMode, density, zoom, titleFloorDp) { mutableStateOf<PartialEraseRequest?>(null) }
    var erasePreview by remember(pageId, tool, eraserMode, density, zoom, titleFloorDp, snapshot) { mutableStateOf<InkPage?>(null) }
    val erasePreparer = remember(pageId) { DesktopErasePreparer() }
    LaunchedEffect(pageId, tool, eraserMode, density, zoom, titleFloorDp, snapshot) {
        snapshotFlow { eraseRequest }.collectLatest { request ->
            if (request == null) { erasePreview = null; return@collectLatest }
            withFrameNanos { }
            val result = erasePreparer.prepare(request)
            if (eraseRequest === request && currentPage === request.page) erasePreview = result?.second
        }
    }
    val displayed = erasePreview?.desktopGeometry() ?: native
    val moving = (selection as? com.vivenotes.ink.DesktopInkSelection)?.takeIf {
        selectionOffset != Offset.Zero && it.page.geometry === native
    }
    fun sceneFor(projections: List<com.vivenotes.byteink.kit.PageStroke>, base: Boolean): InkScene {
        if (projections.isNotEmpty()) diagnostics?.sceneBuilt(base)
        return InkScene(projections.map {
        InkSceneStroke(it.stroke, it.strokeToPageTransform(),
            automaticColorOr(it.stroke.brush.colorIntArgb, it.colorFollowsTheme, canvasInk.toArgb()))
        })
    }
    fun rowsFor(scene: InkScene, projections: List<com.vivenotes.byteink.kit.PageStroke>) =
        buildMap<String, MutableList<InkSceneStroke>> {
            projections.forEachIndexed { index, projection ->
                getOrPut(projection.id) { mutableListOf() }.add(scene.strokes[index])
            }
        }
    fun exclusions(rows: Set<String>, entries: Map<String, List<InkSceneStroke>>,
                   byProjection: Map<com.vivenotes.byteink.kit.InkProjectionKey, InkSceneStroke>): Set<InkSceneStroke> =
        if (rows.isEmpty() && displayed.excludedProjections.isEmpty() && moving == null) emptySet() else Collections.unmodifiableSet(
            Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>()).apply {
                rows.forEach { addAll(entries[it].orEmpty()) }
                displayed.excludedProjections.forEach { key -> byProjection[key]?.let { add(it) } }
                moving?.held?.projections?.forEach { key -> byProjection[key]?.let { add(it) } }
            })
    fun sceneData(segment: DesktopInkSegment, base: Boolean): InkSegmentSceneData {
        val scene = sceneFor(segment.projections, base)
        return InkSegmentSceneData(scene, rowsFor(scene, segment.projections), segment.projections.mapIndexed { index, projection ->
            projection.projectionKey to scene.strokes[index]
        }.toMap())
    }
    val stableSceneData = remember(pageId, canvasInk) { IdentityHashMap<DesktopInkSegment, InkSegmentSceneData>() }
    val stableScenes = remember(displayed.base, displayed.retainedSegments, canvasInk) {
        val segments = listOf(displayed.base) + displayed.retainedSegments
        val kept = Collections.newSetFromMap(IdentityHashMap<DesktopInkSegment, Boolean>()).apply { addAll(segments) }
        stableSceneData.keys.retainAll(kept)
        segments.map { segment -> stableSceneData.getOrPut(segment) { sceneData(segment, segment === displayed.base) } }
    }
    val stableLayers = remember(stableScenes, displayed.excludedRows, displayed.excludedProjections, erasing, moving) {
        val rows = if (erasing.isEmpty()) displayed.excludedRows else displayed.excludedRows + erasing
        stableScenes.map { data -> InkSceneLayer(data.scene, exclusions(rows, data.byRow, data.byProjection)) }
    }
    val additionsData = remember(displayed.additions, canvasInk) { sceneData(displayed.additions, false) }
    val additionsLayer = remember(additionsData, displayed.excludedRows, displayed.excludedProjections, erasing, moving) {
        val rows = if (erasing.isEmpty()) displayed.excludedRows else displayed.excludedRows + erasing
        InkSceneLayer(additionsData.scene, exclusions(rows, additionsData.byRow, additionsData.byProjection))
    }
    val movingStrokes = remember(moving) {
        moving?.page?.desktopGeometry()?.selectedProjections(moving.held.projections).orEmpty()
    }
    fun cancelGesture() {
        if (controller.isDrawing) {
            controller.cancel()
            frameRevision++
        }
    }
    val timing = remember(controller) { InkGestureTiming() }
    LaunchedEffect(controller) {
        snapshotFlow { controller.isDrawing to controller.hasPendingInputs }.collect {
            while (controller.isUpdateNeeded()) {
                withFrameNanos {
                    if (controller.advance(timing.uptime())) {
                        diagnostics?.shapeAdvanced()
                        frameRevision++
                    }
                }
            }
        }
    }
    val input = if (tool == null) Modifier else if (tool == InkTool.Eraser)
        Modifier.pointerInput(pageId, tool, penIndex, eraserMode, settings.eraser.sizeDp, density, zoom, titleFloorDp) {
            coroutineScope {
                var anchor = currentPage
                var projected = anchor
                var generation = 0L
                fun refresh(): Boolean {
                    if (currentPage === anchor || currentPage === projected) {
                        anchor = currentPage
                        return true
                    }
                    anchor = currentPage
                    projected = anchor
                    generation++
                    return false
                }
                val completed = Channel<Triple<PartialEraseRequest, Long, (InkEdit, InkPage) -> Unit>>(Channel.UNLIMITED)
                val worker = launch {
                    for ((request, capturedGeneration, callback) in completed) {
                        refresh()
                        if (capturedGeneration != generation) continue
                        val rebased = request.onPage(projected)
                        val result = erasePreparer.prepare(rebased)
                        refresh()
                        if (capturedGeneration != generation) continue
                        result?.let { (edit, updated) ->
                            projected = updated
                            if (eraseRequest?.id != request.id) eraseRequest = eraseRequest?.onPage(updated)
                            callback(edit, updated)
                        }
                        if (eraseRequest?.id == request.id) { eraseRequest = null; erasePreview = null }
                    }
                }
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if ((down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) ||
                            down.position.y < titleFloorDp * density) {
                            waitForPointersUp()
                            return@awaitEachGesture
                        }
                        refresh()
                        val capturedGeneration = generation
                        val gesture = DesktopPartialEraseGesture(projected, density, settings.eraser.sizeDp, mode = eraserMode)
                        val callback = currentCallback
                        fun observe(sample: InkPointerSample, position: Offset) {
                            gesture.observe(sample)
                            eraserPoint = position
                        }
                        diagnostics?.inputObserved()
                        observe(down.inkSample(includePressure = false), down.position)
                        eraseRequest = gesture.request().onPage(projected)
                        down.consume()
                        var finished = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || !refresh() || generation != capturedGeneration) break
                            diagnostics?.inputObserved(1 + change.historical.size)
                            change.historical.forEach { historical ->
                                observe(change.inkSample(historical.position, historical.uptimeMillis, false), historical.position)
                            }
                            observe(change.inkSample(includePressure = false), change.position)
                            eraseRequest = gesture.request().onPage(projected)
                            finished = change.changedToUpIgnoreConsumed() ||
                                (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
                            change.consume()
                            if (finished) break
                        }
                        waitForPointersUp()
                        if (finished) {
                            val request = gesture.request().onPage(projected)
                            eraseRequest = request
                            completed.trySend(Triple(request, capturedGeneration, callback))
                        } else { eraseRequest = null; erasePreview = null }
                        eraserPoint = null
                    }
                } finally {
                    completed.close()
                    worker.cancel()
                    eraseRequest = null
                    erasePreview = null
                    eraserPoint = null
                }
            }
        }
    else Modifier.pointerInput(pageId, tool, penIndex, density, zoom, titleFloorDp) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                if (down.position.y < titleFloorDp * density) return@awaitEachGesture
                diagnostics?.inputObserved()
                val capturedTool = currentPen
                val capturedPreset = currentSettings.pen(penIndex)
                val callback = currentCallback
                val straightCallback = currentStraighten
                val capturedRuler = currentRuler?.takeIf {
                    it.engages(RulerPoint(down.position.x / density, down.position.y / density), Ruler.SNAP_TOLERANCE_DP)
                }
                val side = capturedRuler?.sideOf(RulerPoint(down.position.x / density, down.position.y / density))
                fun sample(change: PointerInputChange, point: Offset = change.position, time: Long = change.uptimeMillis,
                           pressure: Boolean = true): InkPointerSample {
                    val mapped = if (capturedRuler != null && side != null) {
                        val p = capturedRuler.snap(RulerPoint(point.x / density, point.y / density), side)
                        Offset(p.x.coerceAtLeast(0f) * density, p.y.coerceAtLeast(titleFloorDp) * density)
                    } else point
                    return change.inkSample(mapped, time, pressure)
                }
                val hold = if (tool == InkTool.Pen && capturedPreset.holdForStraightLine && capturedRuler == null)
                    StraightenHold(viewConfiguration.touchSlop / density) else null
                fun observeHold(point: Offset) {
                    if (hold?.observe(point.x / density, point.y / density) == true) holdRevision++
                }
                holdCandidate = hold?.let { HoldCandidate(it) { line -> straightCallback(line, capturedPreset) } }
                observeHold(down.position)
                controller.begin(capturedTool.brush, sample(down), transform)
                diagnostics?.shapeAdvanced()
                frameRevision++
                timing.begin(down.uptimeMillis)
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                        val finished = change.changedToUpIgnoreConsumed() ||
                            (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
                        if (hold?.spent != true) {
                            diagnostics?.inputObserved(1 + change.historical.size)
                            change.historical.forEach { historical ->
                                controller.append(sample(change, historical.position, historical.uptimeMillis, false))
                                observeHold(historical.position)
                            }
                            if (finished) {
                                holdCandidate = null
                                diagnostics?.shapeAdvanced()
                                controller.finish(sample(change))?.let { stroke ->
                                    val edit = capturedTool.complete(stroke, newId(), pageId, 0, System.currentTimeMillis()).desktopEdit()
                                    callback(edit, currentPage.withDesktopEdit(edit))
                                }
                                frameRevision++
                            } else {
                                controller.append(sample(change))
                                observeHold(change.position)
                            }
                        }
                        change.consume()
                        if (finished) break
                    }
                } finally { holdCandidate = null; cancelGesture() }
            }
        } finally { holdCandidate = null; cancelGesture() }
    }
    // Extent reports arbitrarily large document sizes while measuring the child unbounded.
    // Draw and input belong outside it so their coordinate space is the reported page, not that
    // child's zero-sized layout. This also avoids packing huge paired fixed constraints.
    Canvas(Modifier.testTag(INK_LAYER_TAG).then(input).drawBehind {
        val pathsBeforeBackground = renderer.pathBuildCount
        val viewport = visibleWindow?.invoke() ?: Rect(0f, 0f, size.width, size.height)
        var finishedDrawn = 0
        fun background(cache: InkSceneRasterCache, layer: InkScene, excluded: Set<InkSceneStroke>) {
            if (layer.strokes.isEmpty()) { cache.clearCache(); return }
            val before = cache.rasterBuildCount
            val drawn = cache.draw(drawContext.canvas, layer, renderer, viewport, transform, zoom, excluded)
            if (cache.rasterBuildCount != before) finishedDrawn += drawn
        }
        if (additionsLayer.scene.strokes.isEmpty()) additionsCache.clearCache()
        val beforeStable = rasterCache.rasterBuildCount
        val stableDrawn = rasterCache.draw(drawContext.canvas, stableLayers, renderer, viewport, transform, zoom)
        if (rasterCache.rasterBuildCount != beforeStable) finishedDrawn += stableDrawn
        background(additionsCache, additionsLayer.scene, additionsLayer.excluded)
        movingStrokes.forEach { projection ->
            drawInk(renderer, projection.stroke,
                ImmutableAffineTransform(projection.scaleX * density, 0f, (projection.offsetX + selectionOffset.x) * density,
                    0f, projection.scaleY * density, (projection.offsetY + selectionOffset.y) * density),
                automaticColorOr(projection.stroke.brush.colorIntArgb, projection.colorFollowsTheme, canvasInk.toArgb()))
        }
        val finishedPathBuilds = renderer.pathBuildCount - pathsBeforeBackground
        @Suppress("UNUSED_VARIABLE") val revision = frameRevision
        controller.liveStroke?.let { drawInk(renderer, it, controller.strokeToView) }
        eraserPoint?.let { drawCircle(canvasInk.copy(alpha = 0.5f), settings.eraser.sizeDp / 2f * density, it,
            style = Stroke(width = density)) }
        diagnostics?.drawn(renderer.pathBuildCount, rasterCache.rasterBuildCount + additionsCache.rasterBuildCount,
            finishedDrawn, finishedPathBuilds, rasterCache.retainedPixelBytes + additionsCache.retainedPixelBytes)
    }.documentExtent(canvasSize)) { }
}

private class InkGestureTiming {
    private var eventUptime = 0L
    private var startNanos = 0L
    fun begin(uptime: Long) { eventUptime = uptime; startNanos = System.nanoTime() }
    fun uptime(): Long = eventUptime + (System.nanoTime() - startNanos) / 1_000_000L
}

private suspend fun AwaitPointerEventScope.waitForPointersUp() {
    while (currentEvent.changes.any { it.pressed }) awaitPointerEvent(PointerEventPass.Final)
}

private fun PointerInputChange.inkSample(
    point: Offset = position, uptime: Long = uptimeMillis, includePressure: Boolean = true,
): InkPointerSample {
    val inputTool = when (type) {
        PointerType.Mouse -> InputToolType.MOUSE
        PointerType.Touch -> InputToolType.TOUCH
        PointerType.Stylus, PointerType.Eraser -> InputToolType.STYLUS
        else -> InputToolType.UNKNOWN
    }
    val measuredPressure = pressure.takeIf { includePressure && inputTool == InputToolType.STYLUS &&
        it.isFinite() && it in 0f..1f }
    return InkPointerSample(point.x, point.y, uptime, inputTool, measuredPressure)
}

private class HoldCandidate(val hold: StraightenHold, val commit: (com.vivenotes.model.ink.StraightLine) -> Unit)
