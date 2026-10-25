package com.vivenotes.pdf

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.vivenotes.model.*
import com.vivenotes.richtext.findAutoEquationCandidates
import com.vivenotes.ui.canvas.HeadingColor
import com.vivenotes.ui.canvas.bulletCentreY
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.compose.drawDisplayList
import io.ratex.RaTeXFontLoader
import androidx.compose.ui.graphics.toArgb
import io.ratex.measure
import multiplataform_vive.shared.generated.resources.Res
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode

/** Desktop replacement for Android's hydrated equation renderer, using the app's RaTeX engine. */
class PdfEquation private constructor(val latex: String, val display: DisplayList, private val size: Float) {
    private val measured = display.measure(size)
    val widthPx: Float get() = measured.widthPx
    val heightPx: Float get() = measured.heightPx
    val depthPx: Float get() = measured.depthPx
    private val faces = mutableMapOf<String, org.jetbrains.skia.Typeface>()
    fun close() { faces.values.forEach { it.close() }; faces.clear() }
    fun draw(canvas: Canvas) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas.asComposeCanvas(),
            Size(widthPx, heightPx + depthPx)) {
            drawDisplayList(display, this@PdfEquation.size) { glyph, px ->
                val face = faces.getOrPut(glyph.font) {
                    val path = "/composeResources/io.ratex.compose.resources/files/fonts/KaTeX_${glyph.font}.ttf"
                    val bytes = PdfEquation::class.java.getResourceAsStream(path)?.use { it.readBytes() }
                    bytes?.let { data -> org.jetbrains.skia.Data.makeFromBytes(data).use {
                        org.jetbrains.skia.FontMgr.default.makeFromData(it)
                    } } ?: requireNotNull(org.jetbrains.skia.FontMgr.default.matchFamilyStyleCharacter(
                        "sans-serif", org.jetbrains.skia.FontStyle.NORMAL, null, glyph.charCode))
                }
                org.jetbrains.skia.Font(face, px * glyph.scale.toFloat()).use { font ->
                    font.isSubpixel = true
                    font.isLinearMetrics = true
                    Paint().use { paint ->
                        paint.isAntiAlias = true
                        paint.color = glyph.color.composeColor.toArgb()
                        canvas.drawString(String(Character.toChars(glyph.charCode)),
                            (glyph.x * px).toFloat(), (glyph.y * px).toFloat(), font, paint)
                    }
                }
            }
        }
    }
    companion object {
        suspend fun create(latex: String, fontSizePx: Float, color: Int): PdfEquation {
            RaTeXFontLoader.ensureLoaded()
            return PdfEquation(latex, RaTeXEngine.parseBlocking(latex,
                displayMode = latex.startsWith("{\\displaystyle"), color = Color(color)), fontSizePx)
        }
    }
}

internal data class PdfParagraph(
    val block: Block,
    val layout: TextLayoutResult,
    val top: Float,
    val leading: Float,
    val ordinal: Int?,
    val equations: List<PdfEquation>,
)

/** StaticLayout's desktop seam. The measured layouts are used unchanged for preview and PDF. */
class PdfTextLayout internal constructor(
    internal val paragraphs: List<PdfParagraph>,
    private val colors: PdfCanvasColors,
    val width: Float,
    val height: Float,
    private val measurer: TextMeasurer,
) {
    val text: String get() = paragraphs.joinToString("\n") { it.layout.layoutInput.text.text }
    val equations: List<PdfEquation> get() = paragraphs.flatMap { it.equations }
    private val paragraphTops = paragraphs.map { it.top }.toFloatArray()
    private val paragraphBottoms = paragraphs.map { it.top + it.layout.size.height }.toFloatArray()
    internal val paintOutset: Float = maxOf(4f, paragraphs.maxOfOrNull { paragraph ->
        paragraph.layout.layoutInput.text.spanStyles.maxOfOrNull { it.item.fontSize.value.takeIf(Float::isFinite) ?: 0f } ?: 15f
    }?.times(2f) ?: 0f)

    fun draw(canvas: Canvas) = drawVisible(canvas, null)

    internal fun drawVisible(canvas: Canvas, visible: PdfBounds?, work: PdfWorkCounts? = null) {
        val first = if (visible == null) 0 else paragraphBottoms.firstAtLeast(visible.top - paintOutset)
        val end = if (visible == null) paragraphs.size else paragraphTops.firstAtLeast(visible.bottom + paintOutset)
        Paint().use { paint ->
            paint.isAntiAlias = true
            for (paragraphIndex in first until end) {
                val paragraph = paragraphs[paragraphIndex]
                work?.let { it.paragraphsDrawn++ }
                val (block, layout, top, leading, ordinal, equations) = paragraph
                val checkpoint = canvas.save()
                canvas.translate(leading, top)
                layout.multiParagraph.paint(canvas.asComposeCanvas())
                equations.forEachIndexed { index, equation ->
                    layout.placeholderRects.getOrNull(index)?.let { rect ->
                        val saved = canvas.save()
                        canvas.translate(rect.left, rect.top)
                        equation.draw(canvas)
                        canvas.restoreToCount(saved)
                    }
                }
                canvas.restoreToCount(checkpoint)
                paint.mode = PaintMode.FILL
                paint.color = colors.accentArgb
                val indent = block.indent * PageMeasurer.INDENT_STEP_DP
                val gap = PageMeasurer.LIST_GAP_DP
                val baseline = top + layout.firstBaseline
                when (block.type) {
                    BlockType.Bullet -> canvas.drawCircle(indent + gap * .4f,
                        top + bulletCentreY(0f, layout.getLineBottom(0), layout.firstBaseline, block.shownLineSpacing),
                        PageMeasurer.BULLET_RADIUS_DP, paint)
                    BlockType.Numbered -> {
                        val label = measurer.measure("$ordinal.", TextStyle(fontSize = 15.sp,
                            color = Color(colors.textArgb)))
                        val saved = canvas.save()
                        canvas.translate(indent + gap - label.size.width - gap / 6f, baseline - label.firstBaseline)
                        label.multiParagraph.paint(canvas.asComposeCanvas())
                        canvas.restoreToCount(saved)
                    }
                    BlockType.Todo -> {
                        val side = 10.5f
                        val left = indent + gap * .25f
                        val y = baseline - side * .85f
                        paint.mode = PaintMode.STROKE
                        paint.strokeWidth = side * .09f
                        canvas.drawRRect(org.jetbrains.skia.RRect.makeXYWH(left, y, side, side, side * .15f), paint)
                        if (block.checked == true) {
                            paint.strokeWidth = side * .14f
                            canvas.drawLine(left + side * .22f, y + side * .52f,
                                left + side * .42f, y + side * .74f, paint)
                            canvas.drawLine(left + side * .42f, y + side * .74f,
                                left + side * .80f, y + side * .26f, paint)
                        }
                    }
                    BlockType.Quote -> canvas.drawRect(indent, top, indent + 2f, top + layout.size.height, paint)
                    else -> Unit
                }
            }
        }
    }
}

/** SpannableCodec's export styles mapped to Compose's desktop paragraph engine. */
internal class PdfTextMeasurer {
    init {
        // PDF measurement also runs before a Compose host exists, including native Wayland startup.
        @OptIn(InternalComposeUiApi::class)
        registerSkikoComposeImplementation()
    }

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    private var fonts: Map<String, FontFamily>? = null

    private suspend fun bundledFonts(): Map<String, FontFamily> = fonts ?: listOf("inter", "lora", "jetbrains_mono")
        .associateWith { name -> FontFamily(androidx.compose.ui.text.platform.Font(
            identity = "pdf-$name", data = Res.readBytes("font/$name.ttf"))) }.also { fonts = it }

    suspend fun layout(blocks: List<Block>, widthDp: Float, colors: PdfCanvasColors): PdfTextLayout {
        val fonts = bundledFonts()
        var top = 0f
        var number = 0
        val paragraphs = blocks.ifEmpty { listOf(Block(id = "pdf-empty")) }.map { block ->
            number = if (block.type == BlockType.Numbered) number + 1 else 0
            val heading = when (block.type) {
                BlockType.Heading1 -> 1.55f
                BlockType.Heading2 -> 1.30f
                BlockType.Heading3 -> 1.12f
                else -> 1f
            }
            val leading = block.indent * PageMeasurer.INDENT_STEP_DP + when (block.type) {
                BlockType.Bullet, BlockType.Numbered, BlockType.Todo -> PageMeasurer.LIST_GAP_DP
                BlockType.Quote -> 4f
                else -> 0f
            }
            val source = buildAnnotatedString {
                block.runs.forEach { run ->
                    val marks = run.marks
                    val script = Mark.Subscript in marks || Mark.Superscript in marks
                    val fontSize = (marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp ?: 15) *
                        heading * if (script) .75f else 1f
                    val linked = marks.any { it is Mark.Link }
                    val decoration = listOfNotNull(TextDecoration.Underline.takeIf { Mark.Underline in marks || linked },
                        TextDecoration.LineThrough.takeIf { Mark.Strikethrough in marks })
                    pushStyle(SpanStyle(
                        // Headings in the canvas's heading blue, unless their text has a colour.
                        color = if (linked) Color(colors.accentArgb) else marks.filterIsInstance<Mark.TextColor>()
                            .firstOrNull()?.let { Color(it.argb) } ?: if (heading > 1) HeadingColor else Color(colors.textArgb),
                        background = marks.filterIsInstance<Mark.Highlight>().firstOrNull()?.let { Color(it.argb) }
                            ?: if (block.type == BlockType.Code) Color(colors.codeBackgroundArgb) else Color.Unspecified,
                        fontSize = fontSize.sp,
                        fontWeight = if (Mark.Bold in marks || heading > 1) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (Mark.Italic in marks) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.let {
                            fonts[it.name.lowercase()] ?: when (it.name.lowercase()) {
                                "serif" -> FontFamily.Serif
                                "monospace" -> FontFamily.Monospace
                                "cursive" -> FontFamily.Cursive
                                else -> FontFamily.SansSerif
                            }
                        } ?: if (block.type == BlockType.Code) FontFamily.Monospace else FontFamily.SansSerif,
                        baselineShift = when {
                            Mark.Subscript in marks -> BaselineShift.Subscript
                            Mark.Superscript in marks -> BaselineShift.Superscript
                            else -> BaselineShift.None
                        },
                        textDecoration = decoration.takeIf { it.isNotEmpty() }?.let(TextDecoration::combine),
                    ))
                    val equation = marks.filterIsInstance<Mark.Equation>().firstOrNull()
                    if (equation != null) pushStringAnnotation("equation", equation.latex)
                    append(equation?.latex ?: run.text)
                    if (equation != null) pop()
                    pop()
                }
            }
            data class Replacement(val start: Int, val end: Int, val equation: PdfEquation)
            val explicit = source.getStringAnnotations("equation", 0, source.length).map { annotation ->
                Triple(annotation.start, annotation.end, annotation.item)
            }
            val auto = findAutoEquationCandidates(source.text).map { Triple(it.start, it.end, it.latex) }
            val replacements = mutableListOf<Replacement>()
            for ((start, end, latex) in (explicit + auto).sortedBy { it.first }) {
                if (replacements.lastOrNull()?.end?.let { it > start } == true) continue
                val size = source.spanStyles.lastOrNull { it.start <= start && it.end > start }?.item?.fontSize?.value ?: 15f
                try {
                    replacements += Replacement(start, end, PdfEquation.create(latex, size, colors.textArgb))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { }
            }
            val placeholders = mutableListOf<AnnotatedString.Range<Placeholder>>()
            val projected = buildAnnotatedString {
                var offset = 0
                for (replacement in replacements) {
                    append(source.subSequence(offset, replacement.start))
                    val start = length
                    append("\uFFFC")
                    placeholders += AnnotatedString.Range(Placeholder(
                        replacement.equation.widthPx.coerceAtLeast(1f).sp,
                        (replacement.equation.heightPx + replacement.equation.depthPx).coerceAtLeast(1f).sp,
                        PlaceholderVerticalAlign.TextCenter), start, length)
                    offset = replacement.end
                }
                append(source.subSequence(offset, source.length))
            }
            val width = (widthDp - leading).toInt().coerceAtLeast(1)
            val lineFontSize = projected.spanStyles.maxOfOrNull { it.item.fontSize.value } ?: (15f * heading)
            val layout = measurer.measure(projected, TextStyle(color = Color(colors.textArgb), fontSize = (15f * heading).sp,
                lineHeight = (lineFontSize * PageMeasurer.LINE_SPACING_MULTIPLIER * block.shownLineSpacing).sp,
                lineHeightStyle = LineHeightStyle.Default.copy(trim = LineHeightStyle.Trim.None,
                    mode = LineHeightStyle.Mode.Minimum),
                textAlign = when (block.align) { Align.Start -> TextAlign.Start; Align.Center -> TextAlign.Center; Align.End -> TextAlign.End }),
                placeholders = placeholders, constraints = Constraints(maxWidth = width))
            PdfParagraph(block, layout, top, leading, number.takeIf { it > 0 }, replacements.map { it.equation })
                .also { top += layout.size.height }
        }
        return PdfTextLayout(paragraphs, colors, widthDp, top, measurer)
    }
}
