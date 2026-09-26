package com.vivenotes.richtext

import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.model.TOGGLEABLE_MARKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextBufferTest {
    private fun editor(vararg blocks: Block) = RichTextBuffer(blocks.toList())

    @Test
    fun everyAndroidToggleMarkAppliesAndCanBeRemovedFromSelection() {
        TOGGLEABLE_MARKS.forEach { mark ->
            val original = editor(Block(id = "b", runs = listOf(Run("hello world"))))
                .select(TextSelection(0, 5))
            val marked = original.toggleMark(mark)
            assertEquals("hello world", marked.text)
            assertTrue(mark in marked.blocks.single().runs.first().marks, "$mark was not applied")
            assertTrue(mark in marked.activeMarks, "$mark was not reported to the ribbon")
            val cleared = marked.toggleMark(mark)
            assertFalse(mark in cleared.blocks.single().runs.flatMap { it.marks })
        }
    }

    @Test
    fun caretToggleControlsTheNextTypedTextAndScriptMarksExcludeEachOther() {
        val original = editor(Block(id = "b", runs = listOf(Run("abc"))))
            .select(TextSelection(3))
        val typed = original.toggleMark(Mark.Subscript)
            .toggleMark(Mark.Superscript)
            .replace("XY")
        assertEquals("abcXY", typed.text)
        assertEquals(setOf(Mark.Superscript), typed.blocks.single().runs.last().marks)
        assertFalse(Mark.Subscript in typed.blocks.single().runs.last().marks)
    }

    @Test
    fun selectionFormattingSplitsAndRejoinsMaximalRuns() {
        val original = editor(Block(id = "b", runs = listOf(Run("hello world"))))
            .select(TextSelection(3, 8))
        val marked = original.toggleMark(Mark.Bold)
        assertEquals(listOf("hel", "lo wo", "rld"), marked.blocks.single().runs.map { it.text })
        assertEquals(setOf(Mark.Bold), marked.blocks.single().runs[1].marks)
        val restored = marked.toggleMark(Mark.Bold)
        assertEquals(listOf(Run("hello world")), restored.blocks.single().runs)
    }

    @Test
    fun valuedMarksReplaceOnlyTheirOwnKind() {
        val original = editor(Block(id = "b", runs = listOf(Run("hello", setOf(Mark.Bold)))))
            .select(TextSelection(0, 5))
        val recolored = original.setMark(Mark.TextColor(0xFF0000)).setMark(Mark.TextColor(0x00FF00))
        assertEquals(setOf(Mark.Bold, Mark.TextColor(0x00FF00)), recolored.blocks.single().runs.single().marks)
        assertEquals(setOf(Mark.Bold), recolored.clearMark(Mark.TextColor(0)).blocks.single().runs.single().marks)
    }

    @Test
    fun clearFormattingKeepsSemanticLinkAndEquationMarks() {
        val marks = setOf(Mark.Bold, Mark.Link("https://example.com"), Mark.Equation("x^2"))
        val buffer = editor(Block(id = "b", runs = listOf(Run("x", marks))))
            .select(TextSelection(0, 1))
            .clearFormatting()
        // An equation is atomic: a text-format command must not corrupt its payload.
        assertEquals(marks, buffer.blocks.single().runs.single().marks)
        val link = editor(Block(id = "b", runs = listOf(Run("link", marks - Mark.Equation("x^2")))))
            .select(TextSelection(0, 4)).clearFormatting()
        assertEquals(setOf(Mark.Link("https://example.com")), link.blocks.single().runs.single().marks)
    }

    @Test
    fun editingAcrossParagraphsKeepsUnchangedBlockIdsAndStyles() {
        val first = Block(id = "first", type = BlockType.Heading1, runs = listOf(Run("Hello")))
        val second = Block(id = "second", type = BlockType.Bullet, runs = listOf(Run("world")))
        val third = Block(id = "third", runs = listOf(Run("Later")))
        val edited = editor(first, second, third)
            .select(TextSelection(3, 9))
            .replace("p\nq")
        assertEquals("Help\nqld\nLater", edited.text)
        assertEquals(listOf("first", "second", "third"), edited.blocks.map { it.id })
        assertEquals(BlockType.Heading1, edited.blocks[0].type)
        assertEquals(BlockType.Bullet, edited.blocks[1].type)
    }

    @Test
    fun platformTextChangePreservesFormattingOutsideEditedRange() {
        val original = editor(Block(id = "b", runs = listOf(
            Run("hello", setOf(Mark.Bold)), Run(" world", setOf(Mark.Italic)),
        ))).select(TextSelection(5))
        val changed = original.acceptTextChange("hello! world", TextSelection(6))
        assertEquals("hello! world", changed.text)
        assertEquals(setOf(Mark.Bold), changed.blocks.single().runs.first().marks)
        assertEquals(setOf(Mark.Italic), changed.blocks.single().runs.last().marks)
    }

    @Test
    fun repeatedCharactersUseTheCaretAsTheEditAnchor() {
        val original = editor(Block(id = "b", runs = listOf(
            Run("a", setOf(Mark.Bold)), Run("aaa"),
        ))).select(TextSelection(1))
            .toggleMark(Mark.Italic)
        val changed = original.acceptTextChange("aaaaa", TextSelection(2))
        assertEquals(listOf("a", "a", "aaa"), changed.blocks.single().runs.map { it.text })
        assertEquals(setOf(Mark.Bold, Mark.Italic), changed.blocks.single().runs[1].marks)
    }

    @Test
    fun backspaceAmongRepeatedCharactersRemovesTheRunBeforeTheCaret() {
        val original = editor(Block(id = "b", runs = listOf(
            Run("a", setOf(Mark.Bold)), Run("aaa"),
        ))).select(TextSelection(1))
        val changed = original.acceptTextChange("aaa", TextSelection(0))
        assertEquals(listOf(Run("aaa")), changed.blocks.single().runs)
    }

    @Test
    fun redundantPlatformSelectionCallbackDoesNotDisarmTheTypingMark() {
        val armed = editor(Block(id = "b", runs = listOf(Run("abc"))))
            .select(TextSelection(3)).toggleMark(Mark.Bold)
        val callback = armed.acceptTextChange("abc", TextSelection(3))
        assertEquals(setOf(Mark.Bold), callback.typingMarks)
    }

    @Test
    fun blockCommandsApplyToSelectedParagraphs() {
        val original = editor(Block.of("one"), Block.of("two"), Block.of("three"))
            .select(TextSelection(1, 6))
        val changed = original.setBlockType(BlockType.Numbered).indent(2).setAlign(Align.Center)
        assertEquals(listOf(BlockType.Numbered, BlockType.Numbered, BlockType.Paragraph), changed.blocks.map { it.type })
        assertEquals(listOf(2, 2, 0), changed.blocks.map { it.indent })
        assertEquals(listOf(Align.Center, Align.Center, Align.Start), changed.blocks.map { it.align })
    }

    @Test
    fun choosingTheSameListAgainReturnsToAParagraphAndOnlyATodoCarriesATick() {
        val original = editor(Block.of("one"), Block.of("two")).select(TextSelection(0, 5))
        val bulleted = original.setBlockType(BlockType.Bullet)
        assertEquals(listOf(BlockType.Bullet, BlockType.Bullet), bulleted.blocks.map { it.type })
        assertEquals(listOf(BlockType.Paragraph, BlockType.Paragraph),
            bulleted.setBlockType(BlockType.Bullet).blocks.map { it.type })

        val todo = bulleted.setBlockType(BlockType.Todo)
        assertEquals(listOf(false, false), todo.blocks.map { it.checked })
        val ticked = todo.toggleChecked(todo.blocks[0].id)
        assertEquals(listOf(true, false), ticked.blocks.map { it.checked })
        assertEquals(null, ticked.setBlockType(BlockType.Numbered).blocks[0].checked)
        assertEquals(bulleted.blocks, bulleted.toggleChecked(bulleted.blocks[0].id).blocks,
            "only a to-do has a tick to change")
    }

    @Test
    fun enterInAListContinuesItAndANewTodoStartsUnticked() {
        val bullet = Block(id = "b", type = BlockType.Bullet, indent = 1, runs = listOf(Run("item")))
        val continued = editor(bullet).select(TextSelection(4)).acceptTextChange("item\n", TextSelection(5))
        assertEquals(listOf("b", continued.blocks[1].id), continued.blocks.map { it.id })
        assertTrue(continued.blocks[1].id != "b", "a new paragraph needs its own identity")
        assertEquals(BlockType.Bullet, continued.blocks[1].type)
        assertEquals(1, continued.blocks[1].indent)

        val done = Block(id = "t", type = BlockType.Todo, checked = true, runs = listOf(Run("done")))
        val next = editor(done).select(TextSelection(4)).replace("\nnext\nlast")
        assertEquals(listOf(BlockType.Todo, BlockType.Todo, BlockType.Todo), next.blocks.map { it.type })
        assertEquals(listOf(true, false, false), next.blocks.map { it.checked })
    }

    @Test
    fun clearFormattingReturnsTheParagraphToPlainText() {
        val listed = Block(id = "b", type = BlockType.Todo, indent = 2, align = Align.Center, checked = true,
            runs = listOf(Run("task", setOf(Mark.Bold))))
        val caret = editor(listed).select(TextSelection(2)).clearFormatting().blocks.single()
        assertEquals(Block(id = "b", runs = listOf(Run("task", setOf(Mark.Bold)))), caret)
        val selected = editor(listed).select(TextSelection(0, 4)).clearFormatting().blocks.single()
        assertEquals(Block(id = "b", runs = listOf(Run("task"))), selected)
    }

    /** Android `LinkEditingTest.linksSelectedTextAndEditsTheStoredDestination`. */
    @Test
    fun linksSelectedTextAndEditsTheStoredDestination() {
        val linked = editor(Block.of("read this now")).select(TextSelection(5, 9))
            .insertLink("this", "https://example.com")
        assertEquals("read this now", linked.text)
        assertTrue(linked.blocks.single().runs.any { Mark.Link("https://example.com") in it.marks })
        assertEquals(TextSelection(9), linked.selection)

        val caretInLink = linked.select(TextSelection(7))
        assertEquals(LinkTarget("this", "https://example.com"), caretInLink.linkTarget)
        val edited = caretInLink.insertLink("that page", "https://example.org")
        assertEquals("read that page now", edited.text)
        assertEquals(listOf(Run("read "), Run("that page", setOf(Mark.Link("https://example.org"))), Run(" now")),
            edited.blocks.single().runs)
    }

    @Test
    fun linkInsertedAtTheCaretKeepsTheSurroundingFormatting() {
        val original = editor(Block(id = "b", runs = listOf(Run("ab", setOf(Mark.Italic)))))
            .select(TextSelection(1))
        assertEquals(LinkTarget("", null), original.linkTarget)
        val linked = original.insertLink("site", "https://example.com")
        assertEquals("asiteb", linked.text)
        assertEquals(setOf(Mark.Italic, Mark.Link("https://example.com")), linked.blocks.single().runs[1].marks)
        assertEquals(original, original.insertLink("", "https://example.com"), "an empty label inserts nothing")
    }

    @Test
    fun aLinkSplitByOtherFormattingIsStillOneLink() {
        val url = Mark.Link("https://example.com")
        val buffer = editor(Block(id = "b", runs = listOf(
            Run("go "), Run("bold", setOf(Mark.Bold, url)), Run("link", setOf(url)), Run(" end"),
        )))
        assertEquals(LinkTarget("boldlink", url.href), buffer.select(TextSelection(11)).linkTarget)
        assertEquals(LinkTarget("boldlink", url.href), buffer.select(TextSelection(3)).linkTarget)
        assertEquals(LinkTarget("", null), buffer.select(TextSelection(13)).linkTarget)
        val selected = buffer.select(TextSelection(3, 7))
        assertEquals(LinkTarget("bold", url.href), selected.linkTarget)
        assertEquals(listOf(null, url.href, url.href, null), listOf(2, 3, 10, 11).map(buffer::linkUrlAt),
            "opening follows the character under the pointer, not the caret's either-side rule")
    }
}
