package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.richtext.RichTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the editor string lays out, measured with the text engine the canvas uses. */
@OptIn(ExperimentalTestApi::class)
class RichTextLayoutTest {

    /** Regression: every block was followed by two blank lines. */
    @Test
    fun eachBlockTakesOnlyItsOwnLines() = runDesktopComposeUiTest {
        val buffer = RichTextBuffer(listOf(
            Block.of("Title", BlockType.Heading2), Block.of("item", BlockType.Bullet),
            Block.of("step", BlockType.Numbered, indent = 1), Block.of(""), Block.of("end"),
        ))
        var lines = -1
        var text = ""
        setContent {
            val annotated = buffer.asAnnotatedString(RichTextColors(Color.Green, Color.Blue, Color.Gray))
            text = annotated.text
            // Laid out as the text box shows it: separators drawn by [BlockSeparators], at the fixed
            // width of a box that fills its width (shrunk to its intrinsic width, Compose leaves
            // indents out and wraps indented words).
            val shown = BlockSeparators.filter(annotated)
            assertEquals(annotated.length, shown.text.length, "offsets must map to themselves")
            lines = rememberTextMeasurer().measure(shown.text, constraints = Constraints.fixedWidth(700)).lineCount
        }
        waitForIdle()
        assertEquals(buffer.text, text, "the editor string must stay the document's text")
        assertEquals(buffer.blocks.size, lines)
    }

    @Test
    fun fontColourAndHighlightMarksBecomeVisibleTextStyles() = runDesktopComposeUiTest {
        val red = 0xFFE53935.toInt()
        val yellow = 0x66FFEB3B
        val buffer = RichTextBuffer(listOf(Block(id = "b", runs = listOf(
            Run("Review", setOf(Mark.TextColor(red), Mark.Highlight(yellow))), Run(" later"),
        ))))
        setContent {
            val annotated = buffer.asAnnotatedString(RichTextColors(Color.Green, Color.Blue, Color.Gray))
            val marked = annotated.spanStyles.single { it.start == 0 && it.end == 6 }
            assertEquals(Color(red), marked.item.color)
            assertEquals(Color(yellow), marked.item.background)
            val plain = annotated.spanStyles.single { it.start == 6 && it.end == 12 }
            assertEquals(Color.Unspecified, plain.item.color)
            assertEquals(Color.Unspecified, plain.item.background)
        }
        waitForIdle()
    }

    /**
     * Headings 1–3 are blue unless their text has a colour, and are Android's multiple of their
     * text's own size: a size mark — which every newly typed run has — no longer flattens them.
     */
    @Test
    fun headingsAreBlueAndLargerThanTheirTextsOwnSize() = runDesktopComposeUiTest {
        val red = 0xFFE53935.toInt()
        val buffer = RichTextBuffer(listOf(
            Block(id = "h", type = BlockType.Heading1, runs = listOf(Run("Big", setOf(Mark.FontSize(24))), Run(" plain"),
                Run(" red", setOf(Mark.TextColor(red))), Run(" link", setOf(Mark.Link("https://example.com"))))),
            Block(id = "h3", type = BlockType.Heading3, runs = listOf(Run("Small", setOf(Mark.FontSize(15))))),
            Block(id = "p", runs = listOf(Run("body", setOf(Mark.FontSize(24))))),
        ))
        val colors = RichTextColors(Color.Green, Color.Blue, Color.Gray)
        setContent {
            val annotated = buffer.asAnnotatedString(colors)
            fun style(start: Int, end: Int) = annotated.spanStyles.filter { it.start <= start && it.end >= end }
            fun effectiveColor(start: Int, end: Int) = style(start, end).lastOrNull { it.item.color != Color.Unspecified }?.item?.color
            fun effectiveSize(start: Int, end: Int) = style(start, end).last { it.item.fontSize != TextUnit.Unspecified }.item.fontSize
            assertEquals(HeadingColor, effectiveColor(0, 3))
            assertEquals((24 * 1.55f).sp, effectiveSize(0, 3))
            assertEquals((BODY_TEXT_SP * 1.55f).sp, effectiveSize(3, 9), "unsized text is the body size, scaled")
            assertEquals(HeadingColor, effectiveColor(3, 9))
            assertEquals(Color(red), effectiveColor(9, 13), "a colour given to the text stays")
            assertEquals(Color.Blue, effectiveColor(13, 18), "a link keeps the link colour")
            assertEquals((15 * 1.12f).sp, effectiveSize(19, 24))
            val body = annotated.text.indexOf("body")
            assertEquals(null, effectiveColor(body, body + 4))
            assertEquals(24.sp, effectiveSize(body, body + 4))
        }
        waitForIdle()
    }

    /** Regression: the caret of an empty last list item stood at the box's edge, left of its bullet. */
    @Test
    fun anEmptyLastParagraphMeasuresItsCaretFromItsIndent() = runDesktopComposeUiTest {
        val colors = RichTextColors(Color.Green, Color.Blue, Color.Gray)
        val listed = RichTextBuffer(listOf(Block.of("item", BlockType.Bullet), Block(id = "e", type = BlockType.Bullet)))
        val lone = RichTextBuffer(listOf(Block(id = "e", type = BlockType.Todo, checked = false)))
        val ended = RichTextBuffer(listOf(Block.of("item", BlockType.Bullet)))
        setContent {
            val measurer = rememberTextMeasurer()
            val indent = with(androidx.compose.ui.platform.LocalDensity.current) { LIST_GAP_SP.sp.toPx() }
            listOf(listed, lone).forEach { buffer ->
                val annotated = buffer.asAnnotatedString(colors)
                val shown = BlockSeparators.filter(annotated)
                assertEquals(annotated.length + 1, shown.text.length, "an empty last block gets a character to measure")
                val mapping = shown.offsetMapping
                (0..annotated.length).forEach { assertEquals(it, mapping.originalToTransformed(it)) }
                assertEquals(annotated.length, mapping.transformedToOriginal(shown.text.length))
                val layout = measurer.measure(shown.text, constraints = Constraints.fixedWidth(700))
                assertEquals(indent, layout.getCursorRect(buffer.text.length).left, 0.5f, "caret of ${buffer.text}")
                assertEquals(buffer.blocks.size, layout.lineCount)
            }
            val unchanged = ended.asAnnotatedString(colors)
            assertEquals(unchanged.text.length, BlockSeparators.filter(unchanged).text.length, "nothing is added after text")
        }
        waitForIdle()
    }

    /** Clicks, Up, Down and End by the editor's layout: always on the line aimed at. */
    @Test
    fun caretMovesStayOnTheirLines() = runDesktopComposeUiTest {
        val first = "a much longer first line"
        val buffer = RichTextBuffer(listOf(Block.of(first), Block(id = "e"), Block.of("ab"), Block.of("xyz")))
        val text = buffer.text
        val empty = first.length + 1
        setContent {
            val shown = BlockSeparators.filter(buffer.asAnnotatedString(RichTextColors(Color.Green, Color.Blue, Color.Gray)))
            val layout = rememberTextMeasurer().measure(shown.text, constraints = Constraints.fixedWidth(700))
            fun middle(line: Int) = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            assertEquals(first.length, visibleLineEnd(layout, text, 0))
            assertEquals(empty, visibleLineEnd(layout, text, 1))
            assertEquals(text.length, visibleLineEnd(layout, text, 3))
            assertEquals(empty + 1, layout.getOffsetForPosition(Offset(300f, middle(1))),
                "the layout itself puts a click on the empty line onto the next")
            assertEquals(empty, caretAt(layout, text, Offset(300f, middle(1))))
            assertEquals(empty, caretAt(layout, text, Offset(2f, middle(1))))
            assertEquals(first.length, caretAt(layout, text, Offset(650f, middle(0))))
            val x = layout.getCursorRect(first.length).left
            assertEquals(empty, caretOnLine(layout, text, first.length, x, 1))
            assertEquals(empty + 3, caretOnLine(layout, text, empty, x, 1), "past a shorter line's end is its end")
            assertEquals(text.length, caretOnLine(layout, text, empty + 3, x, 1))
            assertEquals(empty, caretOnLine(layout, text, empty + 3, x, -1))
            assertEquals(first.length, caretOnLine(layout, text, empty, x, -1))
            assertEquals(0, caretOnLine(layout, text, 3, x, -1))
            assertEquals(text.length, caretOnLine(layout, text, text.length, x, 1))
        }
        waitForIdle()
    }
}
