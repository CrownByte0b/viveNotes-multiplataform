package com.vivenotes.ai

import com.vivenotes.localization.Strings
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.math.MathEngine
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.contours
import com.vivenotes.ui.ribbon.draw.DrawMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DesktopDrawMath(private val models: AiModelStore, override val engine: MathEngine,
    private val log: DebugLog = DebugLog(),
) : DrawMath, AutoCloseable {
    private val recognition = OnnxInkRecognitionEngine(models, log = log)
    override suspend fun readFormula(ink: InkPage, lines: List<Outline.Shape>): String {
        check(models.state.value.formulaReady) { Strings.install_a_formula_model_in_settings_models }
        val image = withContext(Dispatchers.Default) {
            renderDrawInk(ink.desktopGeometry().projections, models.state.value.formulaEngine.stemPx, lines)
        }
        return image.use {
            recognition.recognizeFormula(it).latex.also { log.event("draw") { "ink formula recognized" } }
        }
    }
    override suspend fun holdFormulaModel(): Nothing = recognition.keepFormulaModelLoaded()
    override fun close() = recognition.close()
}

/**
 * [lines] are Android's rules (`renderInkSelection`): the line and arrow shapes selected with the ink,
 * inked like a stroke (black, at the stem width, solid) and inside the crop. Other kinds are ignored.
 */
internal fun renderDrawInk(strokes: List<PageStroke>, stemPx: Float, lines: List<Outline.Shape> = emptyList()): Image {
    val boxes = strokes.mapNotNull { it.pageBounds }
    require(boxes.isNotEmpty()) { Strings.recognition_requires_ink }
    val rules = lines.filter { it.kind.hasEnds && it.segments.isNotEmpty() }
    val left = minOf(boxes.minOf { it.left }, rules.minOfOrNull { it.x } ?: Float.POSITIVE_INFINITY)
    val top = minOf(boxes.minOf { it.top }, rules.minOfOrNull { it.y } ?: Float.POSITIVE_INFINITY)
    val right = maxOf(boxes.maxOf { it.right }, rules.maxOfOrNull { it.x + it.width } ?: Float.NEGATIVE_INFINITY)
    val bottom = maxOf(boxes.maxOf { it.bottom }, rules.maxOfOrNull { it.y + it.height } ?: Float.NEGATIVE_INFINITY)
    val widthDp = (right - left).coerceAtLeast(1f)
    val heightDp = (bottom - top).coerceAtLeast(1f)
    val longest = max(widthDp, heightDp)
    val stem = recognitionStemSize(longest, stemPx)
    val scale = min(8f, max(2f, 1024f / longest))
    val padding = 12f * scale
    val width = ceil(widthDp * scale + padding * 2).toInt().coerceIn(1, 2048)
    val height = ceil(heightDp * scale + padding * 2).toInt().coerceIn(1, 2048)
    val renderer = InkPathRenderer()
    try {
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            val canvas = surface.canvas.asComposeCanvas()
            for (projection in strokes) {
                val strokeScale = ((abs(projection.scaleX) + abs(projection.scaleY)) / 2f).coerceAtLeast(.001f)
                val clipped = (0 until projection.stroke.shape.getRenderGroupCount()).any {
                    InkMeshes.outlines(projection.stroke.shape, it).isEmpty()
                }
                // Rebuilding a cut mesh from its original inputs would restore erased ink.
                val stroke = if (clipped) projection.stroke else projection.stroke.copy(projection.stroke.brush.copyWithColorIntArgb(
                    colorIntArgb = 0xff000000.toInt(), size = (stem / strokeScale).coerceAtLeast(.05f),
                    epsilon = .1f / (scale * strokeScale)))
                renderer.draw(canvas, stroke, ImmutableAffineTransform(scale * projection.scaleX, 0f,
                    padding + (projection.offsetX - left) * scale, 0f, scale * projection.scaleY,
                    padding + (projection.offsetY - top) * scale), colorArgb = 0xff000000.toInt())
            }
            if (rules.isNotEmpty()) Paint().use { paint ->
                paint.isAntiAlias = true
                paint.color = 0xff000000.toInt()
                paint.mode = PaintMode.STROKE
                paint.strokeWidth = stem
                paint.strokeCap = PaintStrokeCap.ROUND
                paint.strokeJoin = PaintStrokeJoin.ROUND
                val checkpoint = surface.canvas.save()
                surface.canvas.translate(padding - left * scale, padding - top * scale)
                surface.canvas.scale(scale, scale)
                rules.forEach { shape ->
                    shape.segments.contours().forEach { contour -> contour.asPath().use { surface.canvas.drawPath(it, paint) } }
                }
                surface.canvas.restoreToCount(checkpoint)
            }
            surface.makeImageSnapshot()
        }
    } finally { renderer.clearCache() }
}

/** Android's `ShapeContour.asPath` for rules: one open path, straight throughout on these kinds. */
private fun ShapeContour.asPath(): Path = PathBuilder().use { builder -> builder.apply {
    val points = polyline()
    if (points.size < 4) return@apply
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
}.detach() }
