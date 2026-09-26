package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
}
