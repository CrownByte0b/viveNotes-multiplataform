package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal const val INK_LAYER_TAG = "workspace-ink-layer"

/** Displays Android stroke inputs on Compose's Skia canvas, on both desktop targets. */
@Composable
internal fun InkLayer(page: InkPage?, canvasSize: DpSize, canvasInk: Color, visibleWindow: (() -> Rect)? = null) {
    if (page == null || page.strokes.isEmpty()) return
    val density = LocalDensity.current.density
    val rendered = remember(page, density) { renderInk(page, density) }
    Canvas(Modifier.documentExtent(canvasSize).testTag(INK_LAYER_TAG)) {
        val visible = visibleWindow?.invoke()
        rendered.forEach { stroke ->
            if (visible != null && !stroke.bounds.overlaps(visible)) return@forEach
            val source = stroke.source
            val automatic = source.colorFollowsTheme == true ||
                (source.colorFollowsTheme == null &&
                    (source.colorArgb == 0xFF000000.toInt() || source.colorArgb == 0xFFFFFFFF.toInt()))
            drawPath(stroke.path, if (automatic) canvasInk else Color(source.colorArgb))
        }
    }
}

internal data class RenderedInkStroke(val source: VisibleInkStroke, val path: Path) {
    val bounds: Rect = path.getBounds()
}

/** The farthest painted point, including moves; used to keep ink inside the scrollable page. */
internal fun InkPage.contentEdge(): Offset = renderInk(this, 1f).fold(Offset.Zero) { edge, stroke ->
    Offset(maxOf(edge.x, stroke.bounds.right), maxOf(edge.y, stroke.bounds.bottom))
}

/** Replays operations in Android's time/id order. Geometry here is a display approximation. */
internal fun renderInk(page: InkPage, pixelsPerDp: Float): List<RenderedInkStroke> {
    val rendered = page.strokes.map { row ->
        RenderedInkStroke(row, inkOutline(row.samples, row.sizeDp, row.brushFamily, pixelsPerDp))
    }.toMutableList()
    for (operation in page.operations.sortedWith(compareBy(InkPageOperation::createdAt, InkPageOperation::id))) {
        when (operation) {
            is InkPageOperation.Erase -> {
                if (operation.targetIds.isEmpty() || operation.samples.isEmpty()) continue
                val mask = inkOutline(operation.samples, operation.sizeDp, "eraser", pixelsPerDp)
                for (index in rendered.indices) {
                    val stroke = rendered[index]
                    if (stroke.source.id !in operation.targetIds) continue
                    val intersection = pathOperation(PathOperation.Intersect, stroke.path, mask) ?: continue
                    if (intersection.isEmpty) continue
                    rendered[index] = stroke.copy(path = if (operation.objectMode) Path() else
                        pathOperation(PathOperation.Difference, stroke.path, mask) ?: stroke.path)
                }
            }
            is InkPageOperation.Move -> {
                val transform = Matrix().apply {
                    this[0, 0] = operation.scaleX
                    this[1, 1] = operation.scaleY
                    this[3, 0] = (operation.scaleX * operation.dxDp +
                        operation.anchorX * (1f - operation.scaleX)) * pixelsPerDp
                    this[3, 1] = (operation.scaleY * operation.dyDp +
                        operation.anchorY * (1f - operation.scaleY)) * pixelsPerDp
                }
                for (index in rendered.indices) {
                    val stroke = rendered[index]
                    if (stroke.source.id !in operation.targetIds) continue
                    val moved = Path().apply { addPath(stroke.path); transform(transform) }
                    rendered[index] = stroke.copy(path = moved)
                }
            }
        }
    }
    return rendered.filterNot { it.path.isEmpty }
}

private fun pathOperation(operation: PathOperation, first: Path, second: Path): Path? =
    Path().takeIf { it.op(first, second, operation) }

/** A single filled ribbon keeps translucent highlighter crossings at one opacity. */
internal fun inkOutline(samples: List<InkSample>, sizeDp: Float, family: String, pixelsPerDp: Float): Path {
    if (samples.isEmpty() || sizeDp <= 0f || !sizeDp.isFinite()) return Path()
    if (family == "dashed-line") return dashedOutline(samples, sizeDp, pixelsPerDp)
    val points = samples.filter { it.x.isFinite() && it.y.isFinite() }
        .fold(mutableListOf<InkSample>()) { acc, sample ->
            if (acc.lastOrNull()?.let { it.x == sample.x && it.y == sample.y } != true) acc += sample
            acc
        }
    if (points.isEmpty()) return Path()
    val half = sizeDp * pixelsPerDp / 2f
    if (points.size == 1) return Path().apply {
        val p = points[0]
        addOval(Rect(p.x * pixelsPerDp - half, p.y * pixelsPerDp - half,
            p.x * pixelsPerDp + half, p.y * pixelsPerDp + half))
    }
    val left = ArrayList<Offset>(points.size)
    val right = ArrayList<Offset>(points.size)
    for (index in points.indices) {
        val before = points[(index - 1).coerceAtLeast(0)]
        val after = points[(index + 1).coerceAtMost(points.lastIndex)]
        val length = hypot(after.x - before.x, after.y - before.y).coerceAtLeast(0.001f)
        val nx = -(after.y - before.y) / length
        val ny = (after.x - before.x) / length
        val p = points[index]
        val pressure = if (family == "pressure-pen" || family.startsWith("calligraphy-v1-p"))
            p.pressure?.coerceIn(0f, 1f)?.let { 0.3f + it * 0.7f } ?: 1f else 1f
        val nib = if (family.startsWith("calligraphy-v1-p")) {
            val along = (nx + ny) * 0.70710677f
            val across = (nx - ny) * 0.70710677f
            sqrt(along * along + 0.09f * across * across)
        } else 1f
        val radius = half * pressure * nib
        val center = Offset(p.x * pixelsPerDp, p.y * pixelsPerDp)
        left += center + Offset(nx * radius, ny * radius)
        right += center - Offset(nx * radius, ny * radius)
    }
    return Path().apply {
        moveTo(left[0].x, left[0].y)
        for (index in 1..left.lastIndex) lineTo(left[index].x, left[index].y)
        if (family != "highlighter") roundCap(this, points.last(), right.last(), left.last(), pixelsPerDp)
        for (index in right.indices.reversed()) lineTo(right[index].x, right[index].y)
        if (family != "highlighter") roundCap(this, points.first(), left.first(), right.first(), pixelsPerDp)
        close()
    }
}

private fun roundCap(path: Path, sample: InkSample, from: Offset, to: Offset, scale: Float) {
    val cx = sample.x * scale
    val cy = sample.y * scale
    val radius = hypot(from.x - cx, from.y - cy)
    val start = atan2(from.y - cy, from.x - cx)
    for (step in 1..8) {
        val angle = start + PI.toFloat() * step / 8f
        path.lineTo(cx + cos(angle) * radius, cy + sin(angle) * radius)
    }
    path.lineTo(to.x, to.y)
}

private fun dashedOutline(samples: List<InkSample>, sizeDp: Float, scale: Float): Path {
    val result = Path()
    if (samples.size == 1) return inkOutline(samples, sizeDp, "marker", scale)
    val dash = (sizeDp * 2f).coerceAtLeast(2f)
    val gap = dash
    var distance = 0f
    var piece = mutableListOf<InkSample>()
    for (index in 0 until samples.lastIndex) {
        val a = samples[index]
        val b = samples[index + 1]
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length <= 0f) continue
        var along = 0f
        while (along < length) {
            val phase = distance % (dash + gap)
            val drawing = phase < dash
            val advance = minOf(length - along, (if (drawing) dash else dash + gap) - phase)
            if (advance <= 0f) break
            fun sample(at: Float) = InkSample(a.x + (b.x - a.x) * at / length,
                a.y + (b.y - a.y) * at / length)
            if (drawing) {
                if (piece.isEmpty()) piece += sample(along)
                piece += sample(along + advance)
            } else if (piece.isNotEmpty()) {
                result.addPath(inkOutline(piece, sizeDp, "marker", scale))
                piece = mutableListOf()
            }
            along += advance
            distance += advance
        }
    }
    if (piece.isNotEmpty()) result.addPath(inkOutline(piece, sizeDp, "marker", scale))
    return result
}
