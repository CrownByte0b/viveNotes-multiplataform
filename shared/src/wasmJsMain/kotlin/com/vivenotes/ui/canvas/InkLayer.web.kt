package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import com.vivenotes.byteink.compose.drawInk
import com.vivenotes.byteink.compose.rememberInkMeshRenderer
import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.byteink.portable.InkStyle
import com.vivenotes.byteink.portable.InkTransform
import com.vivenotes.byteink.wasm.InkLiveStroke
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.automaticColorOr
import com.vivenotes.ink.WebErasePreview
import com.vivenotes.ink.WebInk
import com.vivenotes.ink.WebInkPages
import com.vivenotes.ink.WebInkSelection
import com.vivenotes.ink.inkToolStyle
import com.vivenotes.ink.toEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.newId
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool
import kotlin.time.Clock
import com.vivenotes.byteink.portable.InkSample as EngineSample
import com.vivenotes.byteink.portable.InkTool as EngineTool
import com.vivenotes.byteink.wasm.InkSample as LiveSample
import com.vivenotes.byteink.wasm.InkToolType as LiveTool

/**
 * The browser preview's ink: ByteInk's engine compiled to Wasm, drawn by its Compose renderer. Pen,
 * highlighter and eraser are authored here; selections come from [com.vivenotes.ink.WebInkGeometry].
 * The ruler, hold-to-straighten and the laser are desktop-only for now (see memory/web-ink.md).
 */
@Composable
internal actual fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
    eraserMode: EraserMode,
    settings: InkSettings,
    selection: InkSelection?,
    selectionOffset: Offset,
    penIndex: Int,
    ruler: com.vivenotes.model.ink.Ruler?,
    onStraighten: (com.vivenotes.model.ink.StraightLine, com.vivenotes.workspace.InkPenSettings) -> Unit,
) {
    if (pageId.isEmpty()) return
    LaunchedEffect(Unit) { WebInk.load() }
    val pages = WebInk.pages.collectAsState().value ?: return
    WebInkLayer(pages, page, canvasSize, canvasInk, pageId, tool, titleFloorDp, onEdit, settings, penIndex,
        eraserMode, selection, selectionOffset)
}

private fun PointerType.engineTool(): EngineTool = when (this) {
    PointerType.Mouse -> EngineTool.MOUSE
    PointerType.Touch -> EngineTool.TOUCH
    PointerType.Stylus, PointerType.Eraser -> EngineTool.STYLUS
    else -> EngineTool.UNKNOWN
}

/** Samples of one gesture in page units; the engine refuses an input repeating the last position and time. */
private class GestureSamples(private val start: Long, private val tool: EngineTool, private val density: Float) {
    val samples = ArrayList<EngineSample>()
    fun add(position: Offset, uptime: Long, pressure: Float? = null): EngineSample? {
        val last = samples.lastOrNull()
        val sample = EngineSample(position.x / density, position.y / density,
            maxOf(last?.elapsedTimeMillis ?: 0L, uptime - start), tool, pressure)
        if (last != null && last.x == sample.x && last.y == sample.y && last.elapsedTimeMillis == sample.elapsedTimeMillis) return null
        samples += sample
        return sample
    }
}

/**
 * Follows [down]'s pointer to its lift, feeding [observe] every position, history first; `current`
 * marks each event's own position. True when it lifted; false when it was taken away, consumed, or
 * joined by another finger.
 */
private suspend fun AwaitPointerEventScope.followInkGesture(
    down: PointerInputChange,
    observe: (change: PointerInputChange, position: Offset, uptime: Long, current: Boolean) -> Unit,
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || change.isConsumed ||
            (down.type == PointerType.Touch && event.changes.count { it.pressed && it.type == PointerType.Touch } > 1)) return false
        val finished = change.changedToUpIgnoreConsumed() ||
            (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
        change.historical.forEach { observe(change, it.position, it.uptimeMillis, false) }
        observe(change, change.position, change.uptimeMillis, true)
        change.consume()
        if (finished) return true
    }
}

@Composable
internal fun WebInkLayer(
    pages: WebInkPages, page: InkPage?, canvasSize: DpSize, canvasInk: Color, pageId: String, tool: InkTool?,
    titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit, settings: InkSettings, penIndex: Int,
    eraserMode: EraserMode = EraserMode.Normal, selection: InkSelection? = null, selectionOffset: Offset = Offset.Zero,
) {
    val snapshot = page ?: remember(pageId) { InkPage(pageId, emptyList()) }
    val rows = remember(pages, snapshot) { pages.rows(snapshot) }
    val drawing = tool == InkTool.Pen || tool == InkTool.Highlighter
    val erasing = tool == InkTool.Eraser
    if (!drawing && !erasing && rows.strokes.isEmpty()) return
    val ink = canvasInk.toArgb()
    // Automatic colours follow the page's ink, as desktop's scenes draw them. Unchanged rows reuse
    // the replay the last edit already made.
    val drawn = remember(rows, ink) {
        val strokes = rows.strokes.map { it.copy(colorArgb = automaticColorOr(it.colorArgb, it.colorFollowsTheme, ink)) }
        if (strokes == rows.strokes) rows else rows.copy(strokes = strokes)
    }
    val density = LocalDensity.current.density
    val renderer = rememberInkMeshRenderer()
    val style = remember(tool, ink, settings, penIndex) { settings.inkToolStyle(tool, ink, penIndex) }
    val eraserSize = settings.normalized().eraser.sizeDp
    val currentStyle by rememberUpdatedState(style)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentEdit by rememberUpdatedState(onEdit)
    var live by remember(pageId) { mutableStateOf<InkLiveStroke?>(null) }
    var erasePreview by remember(pageId) { mutableStateOf<WebErasePreview?>(null) }
    var eraserPoint by remember(pageId) { mutableStateOf<Offset?>(null) }
    var revision by remember(pageId) { mutableLongStateOf(0L) }
    fun showErase(next: WebErasePreview?) {
        val old = erasePreview
        erasePreview = next
        old?.cut?.values?.forEach { renderer.release(pages.engine.nativeStroke(it)); it.close() }
    }
    DisposableEffect(renderer) { onDispose { showErase(null); renderer.clearCache() } }

    val input = when {
        drawing -> Modifier.pointerInput(pageId, tool, penIndex, density, titleFloorDp) {
            awaitEachInkGesture {
                val down = awaitInkDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachInkGesture
                if (down.position.y < titleFloorDp * density) return@awaitEachInkGesture
                val captured = currentStyle
                val inputTool = down.type.engineTool()
                // Pressure is all-or-nothing within a stroke: a pen keeps its last measured value
                // through history samples, which carry positions only.
                var pressure = down.measuredPenPressure()
                val measured = pressure != null
                val gesture = GestureSamples(down.uptimeMillis, inputTool, density)
                val stroke = pages.engine.wasm.startStroke(pages.engine.wasm.catalogBrush(captured.familyId,
                    captured.stabilization, captured.colorArgb, captured.sizeDp, InkBrushCatalog.EPSILON))
                fun add(position: Offset, uptime: Long) {
                    val sample = gesture.add(position, uptime, pressure.takeIf { measured }) ?: return
                    stroke.enqueue(listOf(LiveSample(sample.x, sample.y, sample.elapsedTimeMillis.toDouble(),
                        LiveTool.entries[inputTool.ordinal], sample.pressure)))
                }
                live = stroke
                try {
                    add(down.position, down.uptimeMillis)
                    stroke.update(0.0)
                    revision++
                    down.consume()
                    val finished = followInkGesture(down) { change, position, uptime, current ->
                        if (current) change.measuredPenPressure()?.let { pressure = it }
                        add(position, uptime)
                        if (current) {
                            stroke.update(gesture.samples.last().elapsedTimeMillis.toDouble())
                            revision++
                        }
                    }
                    if (finished) {
                        val row = pages.engine.createStroke(InkStyle(captured.familyId, captured.stabilization,
                            captured.colorArgb, captured.sizeDp), gesture.samples).use { finishedStroke ->
                            pages.kit.encodeStroke(finishedStroke, newId(), pageId, 0, captured.familyId,
                                captured.stabilization, captured.colorFollowsTheme, Clock.System.now().toEpochMilliseconds())
                        }
                        val edit = InkEdit.AddStroke(row.toEntity())
                        currentEdit(edit, pages.withEdits(currentSnapshot, listOf(edit)))
                    }
                } finally {
                    live = null
                    renderer.release(stroke)
                    stroke.close()
                    revision++
                }
            }
        }
        erasing -> Modifier.pointerInput(pageId, tool, eraserMode, eraserSize, density, titleFloorDp) {
            awaitEachInkGesture {
                val down = awaitInkDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachInkGesture
                if (down.position.y < titleFloorDp * density) return@awaitEachInkGesture
                val gesture = GestureSamples(down.uptimeMillis, down.type.engineTool(), density)
                val shown = pages.rows(currentSnapshot)
                var previewedAt = Long.MIN_VALUE
                // Modeling the cut costs a mask and one subtraction per touched piece: at most every
                // other frame, and once more on the lift.
                fun preview(uptime: Long, force: Boolean = false) {
                    if (!force && uptime - previewedAt < 32L) return
                    previewedAt = uptime
                    showErase(pages.erasePreview(shown, gesture.samples, eraserSize, eraserMode))
                }
                try {
                    gesture.add(down.position, down.uptimeMillis)
                    eraserPoint = down.position
                    preview(down.uptimeMillis, force = true)
                    down.consume()
                    val finished = followInkGesture(down) { _, position, uptime, current ->
                        gesture.add(position, uptime)
                        if (current) {
                            eraserPoint = position
                            preview(uptime)
                        }
                    }
                    if (finished) pages.erase(currentSnapshot, gesture.samples, eraserSize, eraserMode,
                        Clock.System.now().toEpochMilliseconds())?.let { (edit, erased) -> currentEdit(edit, erased) }
                } finally {
                    showErase(null)
                    eraserPoint = null
                }
            }
        }
        else -> Modifier
    }
    // A selection being dragged is drawn at its offset, over the rest, until the move lands.
    val moving = (selection as? WebInkSelection)?.takeIf { selectionOffset != Offset.Zero && pages.rows(it.page) === rows }
    // As on desktop, drawing and input sit outside the extent, in the reported page's space.
    Canvas(Modifier.testTag(INK_LAYER_TAG).then(input).drawBehind {
        revision
        fun pixels(transform: InkTransform, offset: Offset = Offset.Zero) = InkTransform(transform.scaleX * density,
            transform.scaleY * density, (transform.offsetX + offset.x) * density, (transform.offsetY + offset.y) * density)
        val held = moving?.held?.projections.orEmpty()
        val preview = erasePreview
        val pieces = pages.pieces(drawn)
        for (piece in pieces) {
            if (piece.key in held) continue
            val transform = pixels(piece.projection.transform)
            if (preview != null && piece.key in preview.hidden) {
                preview.cut[piece.key]?.let { drawInk(renderer, pages.engine.nativeStroke(it), transform) }
            } else drawInk(renderer, pages.engine.nativeStroke(piece.projection.stroke), transform)
        }
        for (piece in pieces) if (piece.key in held)
            drawInk(renderer, pages.engine.nativeStroke(piece.projection.stroke), pixels(piece.projection.transform, selectionOffset))
        live?.let { drawInk(renderer, it, InkTransform(density, density)) }
        eraserPoint?.let { drawCircle(canvasInk.copy(alpha = 0.5f), eraserSize / 2f * density, it, style = Stroke(width = density)) }
    }.documentExtent(canvasSize)) { }
}
