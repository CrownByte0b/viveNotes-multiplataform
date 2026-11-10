// The desktop's stand-in for what viveNotes Android's fileimport/docx/WordLayout.kt takes from
// android.text: a SpannableStringBuilder of runs and replacement spans, `Layout.getDesiredWidth`, and
// StaticLayout with WordLineHeight. Skia's paragraph shapes, breaks and justifies the lines.
package com.vivenotes.fileimport.docx

import com.vivenotes.pdf.PdfEquation
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.BaselineMode
import org.jetbrains.skia.paragraph.DecorationLineStyle
import org.jetbrains.skia.paragraph.DecorationStyle
import org.jetbrains.skia.paragraph.Direction
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.LineMetrics
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.PlaceholderAlignment
import org.jetbrains.skia.paragraph.PlaceholderStyle
import org.jetbrains.skia.paragraph.TextIndent
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A run's look: Android's `WordRunSpan`, a MetricAffectingSpan. One style instead of a stack of
 * platform ones, because sizes here are fractional.
 *
 * Word's character scale (`w:w`) is the one part of it Skia's paragraph has no equivalent for: the
 * glyphs keep their own width, as Android's would with no `textScaleX`.
 */
internal class WordRunSpan(
    private val face: WordFace,
    private val size: Float,
    private val style: WordRunStyle,
    private val color: Int,
) {
    private val shrunk: Float = if (style.script == WordScript.Baseline) size else size * SCRIPT_SCALE

    /** Applied to measuring and drawing alike, as both of Android's `update…State` calls do. */
    fun textStyle(): TextStyle = TextStyle().apply {
        setFontFamilies(arrayOf(face.family))
        setFontStyle(face.typeface.fontStyle)
        setFontSize(shrunk)
        setColor(this@WordRunSpan.color)
        // Android's letter spacing is in ems of the shrunk size, which comes to this many units.
        letterSpacing = if (style.spacingTwips != 0) style.spacingTwips / 2f else 0f
        baselineShift = when (style.script) {
            WordScript.Baseline -> 0f
            WordScript.Super -> -(size * SUPER_SHIFT).roundToInt().toFloat()
            WordScript.Sub -> (size * SUB_SHIFT).roundToInt().toFloat()
        }
        if (style.underline || style.strike) {
            decorationStyle = DecorationStyle(
                style.underline, false, style.strike, false, this@WordRunSpan.color, DecorationLineStyle.SOLID, 1f,
            )
        }
        // A property on the paint: `setColor` here would be the text style's, colouring the letters.
        style.highlightArgb?.let { argb -> background = Paint().also { it.color = argb } }
        fontEdging = FontEdging.ANTI_ALIAS
        subpixel = true
        fontHinting = FontHinting.NONE
    }

    /** The font a tab's leader is drawn in. */
    fun font(): Font = Font(face.typeface, shrunk).apply {
        isSubpixel = true
        isLinearMetrics = true
        hinting = FontHinting.NONE
    }

    val inkArgb: Int get() = color

    private companion object {
        const val SCRIPT_SCALE = 0.65f
        const val SUPER_SHIFT = 0.33f
        const val SUB_SHIFT = 0.14f
    }
}

/** Something drawn in a character's place: Android's ReplacementSpans. */
internal sealed class WordInlineSpan

/** A tab, sized once the tab stops are known. */
internal class TabSpan(val color: Int) : WordInlineSpan() {
    var width: Int = 0
    var leader: Char? = null
}

internal class InlineBoxSpan(val box: LaidBox) : WordInlineSpan()

internal class EquationBoxSpan(val equation: PdfEquation) : WordInlineSpan()

/**
 * Android's SpannableStringBuilder, as a paragraph is built in: text with a run's look, and one
 * character for each thing drawn in its place, so that indices count exactly as Android's do.
 */
internal class WordSpannable(private val fonts: WordFonts) {
    private sealed class Piece(val start: Int) {
        abstract val length: Int
        val end: Int get() = start + length
    }

    private class TextPiece(start: Int, val text: String, val run: WordRunSpan) : Piece(start) {
        override val length: Int get() = text.length
    }

    private class ObjectPiece(start: Int, val span: WordInlineSpan, val run: WordRunSpan?) : Piece(start) {
        override val length: Int get() = 1
    }

    private val pieces = ArrayList<Piece>()

    var length: Int = 0
        private set

    fun isEmpty(): Boolean = length == 0

    operator fun get(index: Int): Char {
        val piece = pieces.firstOrNull { index >= it.start && index < it.end } ?: throw IndexOutOfBoundsException("$index")
        return when (piece) {
            is TextPiece -> piece.text[index - piece.start]
            is ObjectPiece -> OBJECT_CHARACTER
        }
    }

    fun append(value: String, run: WordRunSpan) {
        if (value.isEmpty()) return
        pieces += TextPiece(length, value, run)
        length += value.length
    }

    /** A thing drawn in a character's place; [run], when there is one, gives it a line's height. */
    fun appendObject(span: WordInlineSpan, run: WordRunSpan? = null) {
        pieces += ObjectPiece(length, span, run)
        length += 1
    }

    /** Android's `Layout.getDesiredWidth`: how wide [start] to [end] is set on one line. */
    fun desiredWidth(start: Int, end: Int): Float {
        if (end <= start) return 0f
        return build(start, end, ParagraphStyle().withRastering()).use { built ->
            built.paragraph.layout(Float.POSITIVE_INFINITY).maxIntrinsicWidth
        }
    }

    /** One thing drawn in a character's place, at [index] in this text. */
    class Placed(val index: Int, val span: WordInlineSpan, val run: WordRunSpan?)

    class Built(val paragraph: Paragraph, val placeholders: List<Placed>) : AutoCloseable {
        override fun close() = paragraph.close()
    }

    /** [start] to [end] as one Skia paragraph, every object a placeholder of its own size. */
    fun build(start: Int, end: Int, style: ParagraphStyle): Built {
        val placed = ArrayList<Placed>()
        val paragraph = ParagraphBuilder(style, fonts.collection).use { builder ->
            var text = false
            for (piece in pieces) {
                if (piece.end <= start || piece.start >= end) continue
                when (piece) {
                    is TextPiece -> {
                        val from = max(start, piece.start) - piece.start
                        val to = min(end, piece.end) - piece.start
                        builder.pushStyle(piece.run.textStyle())
                        builder.addText(piece.text.substring(from, to))
                        builder.popStyle()
                        text = true
                    }
                    is ObjectPiece -> {
                        builder.pushStyle(piece.run?.textStyle() ?: FILLER_STYLE)
                        builder.addPlaceholder(placeholderOf(piece.span, piece.run))
                        builder.popStyle()
                        placed += Placed(piece.start - start, piece.span, piece.run)
                    }
                }
            }
            // Skia gives a paragraph of placeholders alone no lines to measure. Android's has its one
            // line of objects; a character too small to have a height of its own gives it that line.
            if (!text) {
                builder.pushStyle(FILLER_STYLE)
                builder.addText(ZERO_WIDTH_SPACE)
                builder.popStyle()
            }
            builder.build()
        }
        return Built(paragraph, placed)
    }

    private fun placeholderOf(span: WordInlineSpan, run: WordRunSpan?): PlaceholderStyle = when (span) {
        // Its height is its run's, which is all an object character with a run's span measures.
        is TabSpan -> {
            val metrics = run?.font()?.use { it.metrics }
            val ascent = metrics?.let { -it.ascent + it.leading / 2f } ?: 0f
            val descent = metrics?.let { it.descent + it.leading / 2f } ?: 0f
            PlaceholderStyle(span.width.toFloat(), ascent + descent, PlaceholderAlignment.BASELINE, BaselineMode.ALPHABETIC, ascent)
        }
        // Standing on the baseline, as `InlineBoxSpan` sets ascent to its height and descent to nothing.
        is InlineBoxSpan -> PlaceholderStyle(
            span.box.width.toFloat(), span.box.height.toFloat(), PlaceholderAlignment.BASELINE, BaselineMode.ALPHABETIC,
            span.box.height.toFloat(),
        )
        is EquationBoxSpan -> {
            val ascent = ceil(span.equation.heightPx)
            val descent = ceil(span.equation.depthPx)
            PlaceholderStyle(
                ceil(span.equation.widthPx).coerceAtLeast(1f), ascent + descent, PlaceholderAlignment.BASELINE,
                BaselineMode.ALPHABETIC, ascent,
            )
        }
    }

    internal companion object {
        const val OBJECT_CHARACTER = '￼'
        private const val ZERO_WIDTH_SPACE = "​"

        /** The look of what is not a run: an object's own character, and the filler above. */
        private val FILLER_STYLE: TextStyle = TextStyle().setFontSize(0.01f).setColor(0)
    }
}

/**
 * What the paragraph's lines are set with: no hinting and fractional advances, as Android's paint
 * flags give. Each run's [TextStyle] carries the same, from [WordRunSpan.textStyle].
 */
internal fun ParagraphStyle.withRastering(): ParagraphStyle = apply {
    disableHinting()
    isApplyRoundingHackEnabled = false
}

/**
 * Word's line spacing, applied to every line including the last, which platform spacing skips:
 * Android's `WordLineHeight.chooseHeight`, given each line's natural height.
 */
internal class WordLineHeight(style: WordParagraphStyle, private val lineFactor: Float) {
    private val rule = style.lineRule
    private val value = if (style.lineRule == WordLineRule.Auto) style.lineValue / 240f else style.lineValue / 2f

    /** How much taller than [natural] the line is set. Word puts it above the text, where a line's height shows. */
    fun extra(natural: Float, hasBox: Boolean): Int {
        val textHeight = if (hasBox) natural else natural * lineFactor
        val target = when (rule) {
            WordLineRule.Auto -> textHeight * value
            WordLineRule.AtLeast -> max(textHeight, value)
            WordLineRule.Exact -> value
        }
        return (target - natural).roundToInt()
    }
}

/**
 * Android's StaticLayout as the import uses it: simple line breaking, no hyphenation, inter-word
 * justification, a first-line and a rest-line indent, and Word's line height on every line.
 *
 * Skia sets the lines at their natural height; each is then moved down by what [lineHeight] adds
 * above it and everything before it, which is what Android's span does through the line's metrics.
 * Breaking does not depend on heights, so the lines are Skia's own.
 */
internal class WordStaticLayout(
    text: WordSpannable,
    width: Int,
    align: WordAlign,
    rightToLeft: Boolean,
    firstIndent: Int,
    restIndent: Int,
    lineHeight: WordLineHeight,
) {
    private class Line(val metrics: LineMetrics, val top: Float, val naturalTop: Float, val height: Int, val shift: Float)

    private val built: WordSpannable.Built = text.build(
        0,
        text.length,
        ParagraphStyle().withRastering().apply {
            alignment = when (align) {
                WordAlign.Center -> Alignment.CENTER
                WordAlign.Right -> Alignment.RIGHT
                WordAlign.Left -> Alignment.LEFT
                WordAlign.Justify -> Alignment.JUSTIFY
            }
            direction = if (rightToLeft) Direction.RTL else Direction.LTR
            textIndent = TextIndent(firstIndent.toFloat(), restIndent.toFloat())
        },
    )
    // A tenth of a point over: a line a tab fills to the stop at the margin is exactly full, and a
    // rounding's worth past that would put its last word on a line of its own.
    private val paragraph: Paragraph = built.paragraph.layout(width + FIT_SLACK)
    private val boxes: List<Rect> = paragraph.rectsForPlaceholders.map { it.rect }
    private val lines: List<Line>
    private val drawnWidth = width.toFloat()

    init {
        val laid = ArrayList<Line>()
        var top = 0f
        for (metrics in paragraph.lineMetrics) {
            // Whole units, as Android's metrics are integers: "exactly" is then exactly that.
            val natural = metrics.height.roundToInt()
            val naturalTop = (metrics.baseline - (metrics.height - metrics.descent)).toFloat()
            val hasBox = built.placeholders.any { it.span is InlineBoxSpan && it.index in metrics.startIndex until max(metrics.endIndex, metrics.startIndex + 1) }
            val extra = lineHeight.extra(natural.toFloat(), hasBox)
            val height = natural + extra
            // Where its baseline moves to: below everything above it, and below its own added space.
            laid += Line(metrics, top, naturalTop, height, top + extra - naturalTop)
            top += height
        }
        lines = laid
    }

    val height: Int = lines.sumOf { it.height }.takeIf { lines.isNotEmpty() } ?: ceil(paragraph.height).toInt()

    val lineCount: Int get() = lines.size

    /** The bottom of line [line], where the next one starts. */
    fun getLineBottom(line: Int): Int = lines.take(line + 1).sumOf { it.height }

    private fun lineOf(index: Int): Line? =
        lines.firstOrNull { index >= it.metrics.startIndex && index < it.metrics.endIndex } ?: lines.lastOrNull()

    fun draw(canvas: Canvas) {
        if (lines.all { it.shift == 0f }) {
            paragraph.paint(canvas, 0f, 0f)
        } else {
            // Each line from its own band of the natural layout, moved to where its height puts it.
            for (line in lines) {
                val count = canvas.save()
                canvas.translate(0f, line.shift)
                canvas.clipRect(Rect.makeLTRB(-CLIP_MARGIN, line.naturalTop, drawnWidth + CLIP_MARGIN, line.naturalTop + line.metrics.height.toFloat()))
                paragraph.paint(canvas, 0f, 0f)
                canvas.restoreToCount(count)
            }
        }
        built.placeholders.forEachIndexed { index, placed ->
            val box = boxes.getOrNull(index) ?: return@forEachIndexed
            val shift = lineOf(placed.index)?.shift ?: 0f
            drawSpan(canvas, placed, box, shift)
        }
    }

    private fun drawSpan(canvas: Canvas, placed: WordSpannable.Placed, box: Rect, shift: Float) {
        when (val span = placed.span) {
            is InlineBoxSpan -> {
                val count = canvas.save()
                canvas.translate(box.left, box.bottom - span.box.height + shift)
                span.box.draw(canvas)
                canvas.restoreToCount(count)
            }
            is EquationBoxSpan -> {
                val baseline = box.top + ceil(span.equation.heightPx)
                val count = canvas.save()
                canvas.translate(box.left, baseline - span.equation.heightPx + shift)
                span.equation.draw(canvas)
                canvas.restoreToCount(count)
            }
            is TabSpan -> {
                val fill = span.leader?.toString() ?: return
                val run = placed.run ?: return
                run.font().use { font ->
                    val ascent = -font.metrics.ascent + font.metrics.leading / 2f
                    val baseline = box.top + ascent + shift
                    val step = font.measureTextWidth(fill)
                    if (step <= 0f) return
                    Paint().use { paint ->
                        paint.isAntiAlias = true
                        paint.color = span.color
                        var at = box.left + step / 2f
                        while (at + step <= box.left + span.width) {
                            canvas.drawString(fill, at, baseline, font, paint)
                            at += step
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val CLIP_MARGIN = 20_000f

        /** One layout unit, a tenth of a point. */
        const val FIT_SLACK = 1f
    }
}
