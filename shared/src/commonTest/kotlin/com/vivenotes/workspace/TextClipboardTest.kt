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
}
