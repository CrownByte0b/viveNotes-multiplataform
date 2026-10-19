package com.vivenotes.workspace

import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Copy keeps formatting beside the plain text; Paste restores it, Paste without formatting never. */
class TextClipboardTest {

    /** "Review limits…" with "Review" bold and its paragraph a heading. */
    private val formatted = WorkspaceState.demo().focusBody()
        .selectText(TextSelection(0, 6)).toggleSelectedMark(Mark.Bold)
        .setSelectedBlockType(BlockType.Heading1)
        .selectText(TextSelection(0, 6))

    @Test
    fun copyingKeepsTheSelectionsFormattingBesideItsText() {
        val copied = formatted.copySelectedText()

        val clip = copied.textClipboard!!
        assertEquals("Review", clip.text)
        assertEquals(setOf(Mark.Bold), clip.blocks.single().runs.single().marks)
        assertNull(formatted.selectText(TextSelection(3)).copySelectedText().textClipboard,
            "nothing is kept when nothing is selected")
    }

    @Test
    fun pasteBringsBackTheFormattingOfTextCopiedHere() {
        val copied = formatted.copySelectedText()
        val end = copied.richText!!.text.length

        val pasted = copied.selectText(TextSelection(end)).pasteText("Review", keepFormatting = true)

        val runs = pasted.richText!!.blocks.last().runs
        assertEquals("Review", runs.last().text)
        assertEquals(setOf(Mark.Bold), runs.last().marks)
    }

    @Test
    fun pasteWithoutFormattingInsertsPlainText() {
        val copied = formatted.copySelectedText()
        val end = copied.richText!!.text.length

        val pasted = copied.selectText(TextSelection(end)).pasteText("Review", keepFormatting = false)

        val runs = pasted.richText!!.blocks.last().runs
        assertEquals("Review", runs.last().text.takeLast(6))
        assertEquals(emptySet(), runs.last().marks)
    }

    @Test
    fun textCopiedSinceFromElsewhereIsPastedAsIs() {
        val copied = formatted.copySelectedText()
        val end = copied.richText!!.text.length

        val pasted = copied.selectText(TextSelection(end)).pasteText("From another app", keepFormatting = true)

        val last = pasted.richText!!.blocks.last().runs.last()
        assertEquals("From another app", last.text.takeLast(16))
        assertEquals(emptySet(), last.marks)
    }

    @Test
    fun plainTextTakesTheFormattingAtTheCaretAsTypingWould() {
        val pasted = formatted.selectText(TextSelection(0)).pasteText("New ", keepFormatting = false)

        val first = pasted.richText!!.blocks.first()
        assertEquals(BlockType.Heading1, first.type)
        assertEquals("New Review", first.runs.first().text)
        assertEquals(setOf(Mark.Bold), first.runs.first().marks)
    }

    @Test
    fun windowsLineEndingsBecomeParagraphs() {
        val pasted = WorkspaceState.demo().focusBody().selectText(TextSelection(0))
            .pasteText("one\r\ntwo\rthree\n", keepFormatting = false)

        assertEquals(listOf("one", "two", "three"), pasted.richText!!.blocks.take(3).map { it.text })
    }

    @Test
    fun nothingIsPastedWithoutATextBoxBeingEdited() {
        val idle = WorkspaceState.demo()
        assertSame(idle, idle.pasteText("text", keepFormatting = true))
    }

    private val webPage = "<h2>Steps</h2><ul><li>Open <b>it</b></li><li>Close it</li></ul>"

    /** A browser's copy: the HTML says what the plain text cannot, in the font and size at the caret. */
    @Test
    fun textAnotherAppCopiedWithItsHtmlKeepsItsHeadingsListsAndMarks() {
        val caretMarks = setOf(Mark.FontFamily("serif"), Mark.FontSize(24))
        val box = WorkspaceState.demo().focusBody().selectText(TextSelection(0)).replaceSelectedText("\n")
            .selectText(TextSelection(0)).let { it.copy(typingMarks = caretMarks) }

        val pasted = box.pasteText("Steps\nOpen it\nClose it", keepFormatting = true, clipboardHtml = webPage)

        val blocks = pasted.richText!!.blocks
        assertEquals(listOf("Steps", "", "Open it", "Close it"), blocks.take(4).map { it.text },
            "the page's space between the heading and the list is a blank line")
        assertEquals(listOf(BlockType.Heading2, BlockType.Paragraph, BlockType.Bullet, BlockType.Bullet),
            blocks.take(4).map { it.type })
        assertEquals(caretMarks + Mark.Bold, blocks[2].runs.last().marks)
        assertEquals(caretMarks, blocks[0].runs.single().marks)
        assertEquals(TextSelection("Steps\n\nOpen it\nClose it".length), pasted.editorSelection)
    }

    @Test
    fun theHtmlIsLeftOutForPlainPastesAndTextCopiedHere() {
        val box = WorkspaceState.demo().focusBody().selectText(TextSelection(0))

        val plain = box.pasteText("Steps", keepFormatting = false, clipboardHtml = webPage)
        assertEquals(BlockType.Paragraph, plain.richText!!.blocks.first().type)
        assertEquals("Steps", plain.richText!!.blocks.first().text.take(5))

        val copied = formatted.copySelectedText()
        val end = copied.richText!!.text.length
        val ours = copied.selectText(TextSelection(end)).pasteText("Review", keepFormatting = true, clipboardHtml = webPage)
        assertEquals(setOf(Mark.Bold), ours.richText!!.blocks.last().runs.last().marks, "the workspace's own copy wins")

        val empty = box.pasteText("only text", keepFormatting = true, clipboardHtml = "<img src=\"x.png\">")
        assertEquals("only text", empty.richText!!.blocks.first().text.take(9), "HTML without text leaves the text")
    }
}
