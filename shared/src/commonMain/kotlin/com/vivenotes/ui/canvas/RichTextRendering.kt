package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.richtext.RichTextBuffer
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.inter
import multiplataform_vive.shared.generated.resources.jetbrains_mono
import multiplataform_vive.shared.generated.resources.lora
import org.jetbrains.compose.resources.Font

/**
 * How a text box draws its blocks: Android's `SpannableCodec.applyDerived` for Compose.
 *
 * Paragraph traits never add characters. Android draws list markers, quote stripes and to-do boxes
 * from `LeadingMarginSpan`s in the margin a paragraph reserves; here the paragraph reserves the
 * same margin with a [TextIndent] and [drawBlockDecorations] paints into it from the text layout.
 * Editor offsets therefore stay the document's offsets, which selection, IME and undo depend on.
 */

/** Android `EditorStyle` metrics — 48 px on the reference tablet's 2x screen — as desktop units. */
internal const val INDENT_STEP_SP = 24f
internal const val LIST_GAP_SP = 24f
internal const val QUOTE_GAP_SP = 14f
internal const val BODY_TEXT_SP = 15f

/** Colours for what a block's formatting adds to its text. */
internal data class RichTextColors(val accent: Color, val link: Color, val codeBackground: Color)

internal val BlockType.isList: Boolean
    get() = this == BlockType.Bullet || this == BlockType.Numbered || this == BlockType.Todo

/** The margin a block reserves at its start: its indent, plus room for a marker or stripe. */
internal fun Block.leadingSp(): Float = INDENT_STEP_SP * indent + when {
    type.isList -> LIST_GAP_SP
    type == BlockType.Quote -> QUOTE_GAP_SP
    else -> 0f
}

@Composable
internal fun RichTextBuffer.asAnnotatedString(colors: RichTextColors): AnnotatedString {
    val inter = FontFamily(Font(Res.font.inter))
    val lora = FontFamily(Font(Res.font.lora))
    val jetbrainsMono = FontFamily(Font(Res.font.jetbrains_mono))
    return buildAnnotatedString {
        blocks.forEachIndexed { blockIndex, block ->
            val leading = block.leadingSp().sp
            pushStyle(ParagraphStyle(
                textAlign = when (block.align) {
                    Align.Start -> TextAlign.Start
                    Align.Center -> TextAlign.Center
                    Align.End -> TextAlign.End
                },
                textIndent = if (block.leadingSp() > 0f) TextIndent(leading, leading) else null,
            ))
            pushStyle(SpanStyle(
                fontWeight = when (block.type) {
                    BlockType.Heading1, BlockType.Heading2, BlockType.Heading3 -> FontWeight.Bold
                    else -> null
                },
                fontSize = when (block.type) {
                    BlockType.Heading1 -> 30.sp
                    BlockType.Heading2 -> 24.sp
                    BlockType.Heading3 -> 20.sp
                    else -> TextUnit.Unspecified
                },
                fontFamily = if (block.type == BlockType.Code) FontFamily.Monospace else null,
                background = if (block.type == BlockType.Code) colors.codeBackground else Color.Unspecified,
            ))
            block.runs.forEach { run ->
                val marks = run.marks
                val selectedSize = marks.filterIsInstance<Mark.FontSize>().firstOrNull()?.sp
                val script = Mark.Subscript in marks || Mark.Superscript in marks
                val linked = marks.any { it is Mark.Link }
                val decorations = listOfNotNull(
                    TextDecoration.Underline.takeIf { Mark.Underline in marks || linked },
                    TextDecoration.LineThrough.takeIf { Mark.Strikethrough in marks },
                )
                pushStyle(SpanStyle(
                    fontWeight = if (Mark.Bold in marks) FontWeight.Bold else null,
                    fontStyle = if (Mark.Italic in marks) FontStyle.Italic else null,
                    fontFamily = marks.filterIsInstance<Mark.FontFamily>().firstOrNull()?.let {
                        when (it.name.lowercase()) {
                            "serif" -> FontFamily.Serif
                            "monospace" -> FontFamily.Monospace
                            "inter" -> inter
                            "lora" -> lora
                            "jetbrains_mono" -> jetbrainsMono
                            "cursive" -> FontFamily.Cursive
                            else -> FontFamily.SansSerif
                        }
                    },
                    fontSize = when {
                        script -> ((selectedSize ?: BODY_TEXT_SP.toInt()) * 0.8f).sp
                        selectedSize != null -> selectedSize.sp
                        else -> TextUnit.Unspecified
                    },
                    baselineShift = when {
                        Mark.Subscript in marks -> BaselineShift.Subscript
                        Mark.Superscript in marks -> BaselineShift.Superscript
                        else -> null
                    },
                    // A link looks like one whatever colour its text was given, as Android's URLSpan does.
                    color = if (linked) colors.link else marks.filterIsInstance<Mark.TextColor>().firstOrNull()
                        ?.let { Color(it.argb) } ?: Color.Unspecified,
                    background = marks.filterIsInstance<Mark.Highlight>().firstOrNull()
                        ?.let { Color(it.argb) } ?: Color.Unspecified,
                    textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
                ))
                append(run.editorText)
                pop()
            }
            pop()
            // The separator belongs to the block it ends. Between two paragraph styles it would be a
            // paragraph of its own, laid out as two blank lines; see [BlockSeparators].
            if (blockIndex < blocks.lastIndex) append('\n')
            pop()
        }
    }
}

/**
 * Draws each block separator as a zero-width space, for a text field showing [asAnnotatedString].
 *
 * Skia lays a paragraph that ends in a newline out with an empty line after it, so a newline at
 * the end of every block's paragraph style put a blank line under every block. Paragraph styles
 * already start a new line, so the separator needs no line break of its own. The swap is one
 * character for one, so offsets map to themselves: the field edits, selects, copies and reports
 * the real newlines, and a click past a line's end still lands before its separator.
 */
internal object BlockSeparators : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        AnnotatedString(text.text.replace('\n', ZERO_WIDTH_SPACE), text.spanStyles, text.paragraphStyles),
        OffsetMapping.Identity,
    )

    private const val ZERO_WIDTH_SPACE = '\u200B'
}

/** Where each block's text sits in the editor string: blocks are joined by one newline. */
private inline fun List<Block>.forEachWithRange(action: (Block, start: Int, end: Int) -> Unit) {
    var start = 0
    forEach { block ->
        val end = start + block.editorText.length
        action(block, start, end)
        start = end + 1
    }
}

/**
 * Each numbered paragraph's ordinal, null for the rest. Numbering restarts whenever a run of
 * numbered paragraphs is interrupted, and ignores indent, as Android's `applyDerived` does.
 */
internal fun listOrdinals(blocks: List<Block>): List<Int?> {
    var ordinal = 0
    var previousWasNumbered = false
    return blocks.map { block ->
        val numbered = block.type == BlockType.Numbered
        ordinal = if (!numbered) 0 else if (previousWasNumbered) ordinal + 1 else 1
        previousWasNumbered = numbered
        ordinal.takeIf { numbered }
    }
}

private fun Block.markerSp(): Float =
    runs.firstOrNull()?.marks?.filterIsInstance<Mark.FontSize>()?.firstOrNull()?.sp?.toFloat() ?: BODY_TEXT_SP

/** Bullets, "1." ordinals, to-do boxes and quote stripes, in the margin [Block.leadingSp] reserved. */
internal fun DrawScope.drawBlockDecorations(
    layout: TextLayoutResult,
    blocks: List<Block>,
    colors: RichTextColors,
    ink: Color,
    measurer: TextMeasurer,
) {
    val ordinals = listOrdinals(blocks)
    var index = 0
    blocks.forEachWithRange { block, start, end ->
        val ordinal = ordinals[index++]
        if (!block.type.isList && block.type != BlockType.Quote) return@forEachWithRange
        if (start > layout.layoutInput.text.length) return@forEachWithRange
        val line = layout.getLineForOffset(start)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val baseline = layout.getLineBaseline(line)
        val left = (INDENT_STEP_SP * block.indent).sp.toPx()
        val gap = LIST_GAP_SP.sp.toPx()
        val fontPx = block.markerSp().sp.toPx()
        when (block.type) {
            BlockType.Bullet -> drawCircle(colors.accent, radius = 3.dp.toPx(),
                center = Offset(left + gap * 0.4f, (top + bottom) / 2f))
            BlockType.Numbered -> {
                val label = measurer.measure("$ordinal.", TextStyle(color = ink, fontSize = block.markerSp().sp))
                drawText(label, topLeft = Offset(left + gap - label.size.width - gap / 6f,
                    baseline - label.firstBaseline))
            }
            BlockType.Todo -> {
                val size = fontPx * 0.7f
                val boxLeft = left + gap * 0.25f
                val boxTop = baseline - size * 0.85f
                drawRoundRect(colors.accent, Offset(boxLeft, boxTop), Size(size, size),
                    CornerRadius(size * 0.15f), style = Stroke(width = maxOf(1f, size * 0.09f)))
                if (block.checked == true) {
                    val stroke = maxOf(1.5f, size * 0.14f)
                    val elbow = Offset(boxLeft + size * 0.42f, boxTop + size * 0.74f)
                    drawLine(colors.accent, Offset(boxLeft + size * 0.22f, boxTop + size * 0.52f), elbow,
                        stroke, StrokeCap.Round)
                    drawLine(colors.accent, elbow, Offset(boxLeft + size * 0.80f, boxTop + size * 0.26f),
                        stroke, StrokeCap.Round)
                }
            }
            BlockType.Quote -> {
                val last = layout.getLineForOffset(end.coerceAtMost(layout.layoutInput.text.length))
                drawRect(colors.accent, Offset(left + 1.dp.toPx(), top),
                    Size(3.dp.toPx(), layout.getLineBottom(last) - top))
            }
            else -> Unit
        }
    }
}

/** The to-do whose box is under [point], in the text layout's coordinates. */
internal fun todoAt(layout: TextLayoutResult, blocks: List<Block>, point: Offset, density: Density): String? =
    with(density) {
        blocks.forEachWithRange { block, start, _ ->
            if (block.type != BlockType.Todo || start > layout.layoutInput.text.length) return@forEachWithRange
            val line = layout.getLineForOffset(start)
            val left = (INDENT_STEP_SP * block.indent).sp.toPx()
            if (point.x in left..(left + LIST_GAP_SP.sp.toPx()) &&
                point.y in layout.getLineTop(line)..layout.getLineBottom(line)
            ) return block.id
        }
        null
    }

/** The address of the link drawn under [point], if the pointer is on a linked character. */
internal fun RichTextBuffer.linkAtPoint(layout: TextLayoutResult, point: Offset): String? {
    val offset = layout.getOffsetForPosition(point)
    return listOf(offset, offset - 1).firstNotNullOfOrNull { candidate ->
        candidate.takeIf { it in 0 until minOf(text.length, layout.layoutInput.text.length) }
            ?.takeIf { layout.getBoundingBox(it).contains(point) }
            ?.let(::linkUrlAt)
    }
}
