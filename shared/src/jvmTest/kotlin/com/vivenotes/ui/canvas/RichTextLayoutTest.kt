package com.vivenotes.ui.canvas

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.richtext.RichTextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * Double spacing is twice the line single spacing shows, whatever the font and size. Fonts' own
     * lines differ — 1.2 to 1.4 of their size — below about 17 sp the text box's own font sets the
     * line rather than the text's, and Compose resolves an em line height against the box's size.
     */
    @Test
    fun lineSpacingMultipliesTheLineSingleSpacingShows() = runDesktopComposeUiTest {
        val colors = RichTextColors(Color.Green, Color.Blue, Color.Gray)
        val words = "Spaced lines leave room between every wrapped line of a paragraph. ".repeat(6)
        val families = listOf("sans-serif", "serif", "monospace", "inter", "lora", "jetbrains_mono")
        val spacings = listOf(1f, 1.15f, 1.5f, 2f, 3f)
        fun buffer(family: String, size: Int, spacing: Float) = RichTextBuffer(listOf(Block(id = "b", lineSpacing = spacing,
            runs = listOf(Run(words, setOf(Mark.FontFamily(family), Mark.FontSize(size)))))))
        val gaps = mutableMapOf<Triple<String, Int, Float>, List<Float>>()
        var plainStyles = emptyList<ParagraphStyle>()
        // A dense screen, so Skia's rounding of each line to whole pixels is small beside the line.
        setContent { CompositionLocalProvider(LocalDensity provides Density(4f)) {
            val measurer = rememberTextMeasurer()
            for (family in families) for (size in listOf(15, 30)) for (spacing in spacings) {
                val annotated = buffer(family, size, spacing).asAnnotatedString(colors)
                if (spacing == 1f) plainStyles += annotated.paragraphStyles.map { it.item }
                // As the text box lays it out: under its own 15 sp text.
                val layout = measurer.measure(BlockSeparators.filter(annotated).text, TextStyle(fontSize = 15.sp),
                    constraints = Constraints.fixedWidth(1600))
                gaps[Triple(family, size, spacing)] = (1 until layout.lineCount).map { layout.getLineBaseline(it) - layout.getLineBaseline(it - 1) }
            }
        } }
        waitForIdle()
        assertTrue(plainStyles.all { it.lineHeight == TextUnit.Unspecified && it.lineHeightStyle == null },
            "an ordinarily spaced paragraph lays out as it always has")
        for (family in families) for (size in listOf(15, 30)) {
            val single = gaps.getValue(Triple(family, size, 1f))
            assertTrue(single.size >= 3, "$family at $size sp wraps onto several lines")
            for (spacing in spacings.drop(1)) gaps.getValue(Triple(family, size, spacing)).forEach { gap ->
                // Skia rounds lines to whole pixels and sets a spaced line 2-3% over the height asked.
                val expected = single.first() * spacing
                assertEquals(expected, gap, 2f + expected * 0.03f, "$family at $size sp, spacing $spacing")
            }
        }
    }

    /**
     * Every line has its share of the extra height, so the gap between two spaced paragraphs is a
     * line's; and a bullet stays as high beside its text as single spacing puts it.
     */
    @Test
    fun spacedParagraphsKeepOneRhythmAndTheirBulletBesideTheText() = runDesktopComposeUiTest {
        val colors = RichTextColors(Color.Green, Color.Blue, Color.Gray)
        val words = "Two paragraphs, each long enough to wrap, spaced two lines apart. ".repeat(3)
        fun buffer(spacing: Float) = RichTextBuffer(listOf(Block(id = "a", type = BlockType.Bullet, lineSpacing = spacing,
            runs = listOf(Run(words))), Block(id = "b", lineSpacing = spacing, runs = listOf(Run(words)))))
        var gaps = emptyList<Float>()
        val bulletAboveBaseline = mutableMapOf<Float, Float>()
        setContent {
            val measurer = rememberTextMeasurer()
            for (spacing in listOf(1f, 2f, 3f)) {
                val layout = measurer.measure(BlockSeparators.filter(buffer(spacing).asAnnotatedString(colors)).text,
                    TextStyle(fontSize = 15.sp), constraints = Constraints.fixedWidth(300))
                if (spacing == 2f) gaps = (1 until layout.lineCount).map { layout.getLineBaseline(it) - layout.getLineBaseline(it - 1) }
                val baseline = layout.getLineBaseline(0)
                bulletAboveBaseline[spacing] = baseline -
                    bulletCentreY(layout.getLineTop(0), layout.getLineBottom(0), baseline, spacing)
            }
        }
        waitForIdle()
        assertTrue(gaps.size >= 5, "both paragraphs wrap: $gaps")
        gaps.forEach { assertEquals(gaps.first(), it, 1f, "gaps $gaps") }
        val single = bulletAboveBaseline.getValue(1f)
        assertTrue(single > 3f, "a bullet sits at the middle of the text, above its baseline: $single")
        listOf(2f, 3f).forEach { assertEquals(single, bulletAboveBaseline.getValue(it), 1f, "bullet at $it") }
    }
}
