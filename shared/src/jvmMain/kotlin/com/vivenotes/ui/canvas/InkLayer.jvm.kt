package com.vivenotes.ui.canvas

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
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.automaticColorOr
import com.vivenotes.data.InkEdit
import com.vivenotes.data.EraserMode
import com.vivenotes.ink.DesktopPartialEraseGesture
import com.vivenotes.ink.PartialEraseRequest
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.desktopEdit
import com.vivenotes.ink.withDesktopEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.newId
import com.vivenotes.workspace.InkTool
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

@Composable
internal actual fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
    eraserMode: EraserMode,
) {
    if (pageId.isEmpty()) return
    val snapshot = page ?: remember(pageId) { InkPage(pageId, emptyList()) }
    val native = remember(snapshot) { snapshot.desktopGeometry() }
    if (tool == null && native.isEmpty) return
    val density = LocalDensity.current.density
    val diagnostics = LocalInkLayerDiagnostics.current
    val transform = remember(density) { ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f) }
    val renderer = remember(pageId) { InkPathRenderer() }
    val rasterCache = remember(pageId) { InkSceneRasterCache() }
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
    val pen = remember(tool, canvasInk) { when (tool) {
        InkTool.Highlighter -> ViveInkTool(ViveBrushes.HIGHLIGHTER, colorArgb = 0x80ffe000.toInt(), sizeDp = 18f)
        else -> ViveInkTool(colorArgb = canvasInk.toArgb(), colorFollowsTheme = true)
    } }
    val currentPen by rememberUpdatedState(pen)
    val currentPage by rememberUpdatedState(remember(snapshot, native) {
        if (snapshot.geometry === native) snapshot else snapshot.copy(geometry = native)
    })
    val currentNative by rememberUpdatedState(native)
    val currentCallback by rememberUpdatedState(onEdit)
    var erasing by remember(pageId, tool) { mutableStateOf(emptySet<String>()) }
    var eraserPoint by remember(pageId, tool) { mutableStateOf<Offset?>(null) }
    var eraseRequest by remember(pageId, tool, eraserMode, density, zoom, titleFloorDp) { mutableStateOf<PartialEraseRequest?>(null) }
    var erasePreview by remember(pageId, tool, eraserMode, density, zoom, titleFloorDp, snapshot) { mutableStateOf<InkPage?>(null) }
    LaunchedEffect(pageId, tool, eraserMode, density, zoom, titleFloorDp, snapshot) {
        snapshotFlow { eraseRequest }.collectLatest { request ->
            if (request == null) { erasePreview = null; return@collectLatest }
            withFrameNanos { }
            val result = withContext(Dispatchers.Default) { request.prepare() }
            if (eraseRequest === request && currentPage === request.page) erasePreview = result?.second
        }
    }
    val displayed = erasePreview?.desktopGeometry() ?: native
    fun sceneFor(projections: List<com.vivenotes.byteink.kit.PageStroke>, base: Boolean): InkScene {
        if (projections.isNotEmpty()) diagnostics?.sceneBuilt(base)
        return InkScene(projections.map {
        InkSceneStroke(it.stroke, it.strokeToPageTransform(),
            automaticColorOr(it.stroke.brush.colorIntArgb, it.colorFollowsTheme, canvasInk.toArgb()))
        })
    }
    val scene = remember(displayed.base, canvasInk) { sceneFor(displayed.base.projections, true) }
    val additionsScene = remember(displayed.additions, canvasInk) { sceneFor(displayed.additions.projections, false) }
    fun rowsFor(scene: InkScene, projections: List<com.vivenotes.byteink.kit.PageStroke>) =
        buildMap<String, MutableList<InkSceneStroke>> {
            projections.forEachIndexed { index, projection ->
                getOrPut(projection.id) { mutableListOf() }.add(scene.strokes[index])
            }
        }
    val sceneStrokesByRow = remember(scene) { rowsFor(scene, displayed.base.projections) }
    val additionsStrokesByRow = remember(additionsScene) { rowsFor(additionsScene, displayed.additions.projections) }
    fun exclusions(rows: Set<String>, entries: Map<String, List<InkSceneStroke>>): Set<InkSceneStroke> =
        if (rows.isEmpty()) emptySet() else Collections.unmodifiableSet(
            Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>()).apply {
                rows.forEach { addAll(entries[it].orEmpty()) }
            })
    val excludedStrokes = remember(scene, displayed.excludedRows, erasing) {
        exclusions(displayed.excludedRows + erasing, sceneStrokesByRow)
    }
    val excludedAdditions = remember(additionsScene, erasing) { exclusions(erasing, additionsStrokesByRow) }
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
    val input = if (tool == null) Modifier else if (tool == InkTool.Eraser && eraserMode == EraserMode.Normal)
        Modifier.pointerInput(pageId, tool, eraserMode, density, zoom, titleFloorDp) {
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
                        val result = withContext(Dispatchers.Default) { rebased.prepare() }
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
                        val gesture = DesktopPartialEraseGesture(projected, density)
                        val callback = currentCallback
                        fun observe(sample: InkPointerSample, position: Offset) {
                            if (gesture.observe(sample)) eraseRequest = gesture.request().onPage(projected)
                            eraserPoint = position
                        }
                        diagnostics?.inputObserved()
                        observe(down.inkSample(includePressure = false), down.position)
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
    else Modifier.pointerInput(pageId, tool, eraserMode, density, zoom, titleFloorDp) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                if (down.position.y < titleFloorDp * density) return@awaitEachGesture
                diagnostics?.inputObserved()
                val capturedTool = currentPen
                val callback = currentCallback
                val index = currentNative.index
                var previous = InkPoint(down.position.x / density, down.position.y / density)
                val targets = mutableSetOf<String>()
                fun observeEraser(position: Offset) {
                    val next = InkPoint(position.x / density, position.y / density)
                    val previousTargetCount = targets.size
                    index.crossing(previous, next, 24f).forEach { targets += it.id }
                    index.at(next, 12f).forEach { targets += it.id }
                    previous = next
                    if (targets.size != previousTargetCount) erasing = targets.toSet()
                    eraserPoint = position
                }
                if (tool == InkTool.Eraser) observeEraser(down.position) else {
                    controller.begin(capturedTool.brush, down.inkSample(), transform)
                    diagnostics?.shapeAdvanced()
                    frameRevision++
                    timing.begin(down.uptimeMillis)
                }
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) break
                        diagnostics?.inputObserved(1 + change.historical.size)
                        change.historical.forEach { historical ->
                            if (tool == InkTool.Eraser) observeEraser(historical.position)
                            else controller.append(change.inkSample(historical.position, historical.uptimeMillis, false))
                        }
                        val finished = change.changedToUpIgnoreConsumed() ||
                            (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
                        if (tool == InkTool.Eraser) {
                            observeEraser(change.position)
                            if (finished && targets.isNotEmpty()) {
                                val edit = InkEdit.EraseStrokes(targets.toSet())
                                callback(edit, currentPage.withDesktopEdit(edit))
                            }
                        } else if (finished) {
                            diagnostics?.shapeAdvanced()
                            controller.finish(change.inkSample())?.let { stroke ->
                                val edit = capturedTool.complete(stroke, newId(), pageId, 0, System.currentTimeMillis()).desktopEdit()
                                callback(edit, currentPage.withDesktopEdit(edit))
                            }
                            frameRevision++
                        } else {
                            controller.append(change.inkSample())
                        }
                        change.consume()
                        if (finished) break
                    }
                } finally {
                    cancelGesture()
                    erasing = emptySet()
                    eraserPoint = null
                }
            }
        } finally {
            cancelGesture()
            erasing = emptySet()
            eraserPoint = null
        }
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
        background(rasterCache, scene, excludedStrokes)
        background(additionsCache, additionsScene, excludedAdditions)
        val finishedPathBuilds = renderer.pathBuildCount - pathsBeforeBackground
        @Suppress("UNUSED_VARIABLE") val revision = frameRevision
        controller.liveStroke?.let { drawInk(renderer, it, controller.strokeToView) }
        eraserPoint?.let { drawCircle(canvasInk.copy(alpha = 0.5f), 12f * density, it,
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
