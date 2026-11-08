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
import androidx.compose.ui.graphics.toArgb
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
import com.vivenotes.data.InkEdit
import com.vivenotes.data.automaticColorOr
import com.vivenotes.ink.WebInk
import com.vivenotes.ink.WebInkPages
import com.vivenotes.ink.inkToolStyle
import com.vivenotes.ink.toEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.newId
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool
import kotlin.time.Clock
import com.vivenotes.byteink.portable.InkSample as EngineSample
import com.vivenotes.byteink.portable.InkTool as EngineTool
import com.vivenotes.byteink.wasm.InkSample as LiveSample
import com.vivenotes.byteink.wasm.InkToolType as LiveTool

/**
 * The browser preview's ink: ByteInk's engine compiled to Wasm, drawn by its Compose renderer.
 * Pen and highlighter strokes are authored here; erasing, selection, the ruler and the laser are
 * desktop-only for now (see memory/web-ink.md).
 */
@Composable
internal actual fun PlatformInkLayer(
    page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)?,
    pageId: String, tool: InkTool?, titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit,
    zoom: Float,
    eraserMode: com.vivenotes.data.EraserMode,
    settings: InkSettings,
    selection: com.vivenotes.model.ink.InkSelection?,
    selectionOffset: Offset,
    penIndex: Int,
    ruler: com.vivenotes.model.ink.Ruler?,
    onStraighten: (com.vivenotes.model.ink.StraightLine, com.vivenotes.workspace.InkPenSettings) -> Unit,
) {
    if (pageId.isEmpty()) return
    LaunchedEffect(Unit) { WebInk.load() }
    val pages = WebInk.pages.collectAsState().value ?: return
    WebInkLayer(pages, page, canvasSize, canvasInk, pageId, tool, titleFloorDp, onEdit, settings, penIndex)
}

@Composable
internal fun WebInkLayer(
    pages: WebInkPages, page: InkPage?, canvasSize: DpSize, canvasInk: Color, pageId: String, tool: InkTool?,
    titleFloorDp: Float, onEdit: (InkEdit, InkPage) -> Unit, settings: InkSettings, penIndex: Int,
) {
    val snapshot = page ?: remember(pageId) { InkPage(pageId, emptyList()) }
    val rows = remember(pages, snapshot) { pages.rows(snapshot) }
    val authoring = tool == InkTool.Pen || tool == InkTool.Highlighter
    if (!authoring && rows.strokes.isEmpty()) return
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
    val currentStyle by rememberUpdatedState(style)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentEdit by rememberUpdatedState(onEdit)
    var live by remember(pageId) { mutableStateOf<InkLiveStroke?>(null) }
    var revision by remember(pageId) { mutableLongStateOf(0L) }
    DisposableEffect(renderer) { onDispose { renderer.clearCache() } }

    val input = if (!authoring) Modifier else Modifier.pointerInput(pageId, tool, penIndex, density, titleFloorDp) {
        awaitEachInkGesture {
            val down = awaitInkDown()
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachInkGesture
            if (down.position.y < titleFloorDp * density) return@awaitEachInkGesture
            val captured = currentStyle
            val inputTool = when (down.type) {
                PointerType.Mouse -> EngineTool.MOUSE
                PointerType.Touch -> EngineTool.TOUCH
                PointerType.Stylus, PointerType.Eraser -> EngineTool.STYLUS
                else -> EngineTool.UNKNOWN
            }
            // Pressure is all-or-nothing within a stroke: a pen keeps its last measured value
            // through history samples, which carry positions only.
            var pressure = down.measuredPenPressure()
            val measured = pressure != null
            val start = down.uptimeMillis
            val samples = ArrayList<EngineSample>()
            val stroke = pages.engine.wasm.startStroke(pages.engine.wasm.catalogBrush(captured.familyId,
                captured.stabilization, captured.colorArgb, captured.sizeDp, InkBrushCatalog.EPSILON))
            fun add(position: Offset, uptime: Long) {
                val last = samples.lastOrNull()
                val sample = EngineSample(position.x / density, position.y / density,
                    maxOf(last?.elapsedTimeMillis ?: 0L, uptime - start), inputTool, pressure.takeIf { measured })
                // The engine refuses an input repeating the previous position and time.
                if (last != null && last.x == sample.x && last.y == sample.y && last.elapsedTimeMillis == sample.elapsedTimeMillis) return
                samples += sample
                stroke.enqueue(listOf(LiveSample(sample.x, sample.y, sample.elapsedTimeMillis.toDouble(),
                    LiveTool.entries[inputTool.ordinal], sample.pressure)))
            }
            live = stroke
            try {
                add(down.position, down.uptimeMillis)
                stroke.update(0.0)
                revision++
                down.consume()
                var finished = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed ||
                        (down.type == PointerType.Touch && event.changes.count { it.pressed && it.type == PointerType.Touch } > 1)) break
                    finished = change.changedToUpIgnoreConsumed() ||
                        (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)
                    change.historical.forEach { add(it.position, it.uptimeMillis) }
                    change.measuredPenPressure()?.let { pressure = it }
                    add(change.position, change.uptimeMillis)
                    stroke.update(samples.last().elapsedTimeMillis.toDouble())
                    revision++
                    change.consume()
                    if (finished) break
                }
                if (finished) {
                    val row = pages.engine.createStroke(InkStyle(captured.familyId, captured.stabilization,
                        captured.colorArgb, captured.sizeDp), samples).use { finishedStroke ->
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
    // As on desktop, drawing and input sit outside the extent, in the reported page's space.
    Canvas(Modifier.testTag(INK_LAYER_TAG).then(input).drawBehind {
        revision
        fun pixels(transform: InkTransform) = InkTransform(transform.scaleX * density, transform.scaleY * density,
            transform.offsetX * density, transform.offsetY * density)
        for (piece in pages.replay(drawn).strokes) drawInk(renderer, pages.engine.nativeStroke(piece.stroke), pixels(piece.transform))
        live?.let { drawInk(renderer, it, InkTransform(density, density)) }
    }.documentExtent(canvasSize)) { }
}
