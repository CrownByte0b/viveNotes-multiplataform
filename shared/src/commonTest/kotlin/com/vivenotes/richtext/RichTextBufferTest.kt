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
    fun lineSpacingChangesEverySelectedParagraphAndNoOther() {
        val three = editor(Block(id = "a", runs = listOf(Run("one"))), Block(id = "b", runs = listOf(Run("two"))),
            Block(id = "c", runs = listOf(Run("three"))))

        val spaced = three.select(TextSelection(1, 5)).setLineSpacing(2f)
        assertEquals(listOf(2f, 2f, 1f), spaced.blocks.map { it.lineSpacing })
        assertEquals(listOf("a", "b", "c"), spaced.blocks.map { it.id })
        assertEquals(three.text, spaced.text)
        assertEquals(TextSelection(1, 5), spaced.selection)
        assertEquals(listOf(2f, 2f, 1.15f), spaced.select(TextSelection(9)).setLineSpacing(1.15f).blocks.map { it.lineSpacing },
            "a caret changes only its own paragraph")

        assertEquals(Block.MAX_LINE_SPACING, three.setLineSpacing(9f).blocks.first().lineSpacing)
        assertEquals(Block.MIN_LINE_SPACING, three.setLineSpacing(0.2f).blocks.first().lineSpacing)
        assertEquals(three, three.setLineSpacing(Float.NaN))
    }

    @Test
    fun aParagraphSplitOffKeepsItsLineSpacingAndClearFormattingResetsIt() {
        val spaced = editor(Block(id = "s", type = BlockType.Bullet, lineSpacing = 1.5f, runs = listOf(Run("item"))))

        val entered = spaced.select(TextSelection(4)).replace("\nnext")
        assertEquals(listOf(1.5f, 1.5f), entered.blocks.map { it.lineSpacing }, "Enter carries it on, as alignment")
        assertEquals(listOf(1.5f, 1.5f), spaced.select(TextSelection(4)).insertPlainParagraph().blocks.map { it.lineSpacing })

        assertEquals(listOf(1f, 1f), entered.select(TextSelection(0, 9)).clearFormatting().blocks.map { it.lineSpacing })
        assertEquals(1f, spaced.select(TextSelection(2)).clearFormatting().blocks.single().lineSpacing)
    }

    @Test
    fun pastedWholeParagraphsBringTheirLineSpacing() {
        val copied = editor(Block(id = "d", lineSpacing = 2f, runs = listOf(Run("double"))),
            Block(id = "o", lineSpacing = 3f, runs = listOf(Run("triple")))).select(TextSelection(0, 13)).selectedFragment()

        assertEquals(listOf(2f, 3f), editor(Block(id = "e")).insertFragment(copied).blocks.map { it.lineSpacing })
        val intoText = editor(Block(id = "t", runs = listOf(Run("before after")))).select(TextSelection(7))
            .insertFragment(copied)
        assertEquals(listOf(1f, 1f), intoText.blocks.map { it.lineSpacing },
            "a piece sharing a line takes the spacing of the paragraph it lands in")
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
    fun typingAfterAnInsertedLinkLeavesTheLinkEvenAcrossSpaces() {
        val url = Mark.Link("https://example.com")
        val linked = editor(Block.of("link"))
            .select(TextSelection(0, 4)).insertLink("link", url.href)
        assertEquals(emptySet(), linked.typingMarks)

        val first = linked.acceptTextChange("link more", TextSelection(9))
        val second = first.acceptTextChange("link more words", TextSelection(15))
        assertEquals(listOf(Run("link", setOf(url)), Run(" more words")), second.blocks.single().runs)
        assertEquals(listOf(RichTextBuffer.LinkSpan(0, 4, url.href)), second.linkSpans())
    }

    @Test
    fun caretAtEitherLinkEdgeTypesOutsideItButInsideItStillEditsTheLink() {
        val url = Mark.Link("https://example.com")
        val original = editor(Block(id = "b", runs = listOf(
            Run("li", setOf(Mark.Bold, url)), Run("nk", setOf(Mark.Italic, url)),
        )))

        val before = original.select(TextSelection(0)).acceptTextChange("xlink", TextSelection(1))
        assertEquals(listOf(Run("x", setOf(Mark.Bold)), Run("li", setOf(Mark.Bold, url)),
            Run("nk", setOf(Mark.Italic, url))), before.blocks.single().runs)

        val after = original.select(TextSelection(4)).acceptTextChange("link x", TextSelection(6))
        assertEquals(listOf(Run("li", setOf(Mark.Bold, url)), Run("nk", setOf(Mark.Italic, url)),
            Run(" x", setOf(Mark.Italic))), after.blocks.single().runs)

        val inside = original.select(TextSelection(2)).acceptTextChange("liXnk", TextSelection(3))
        assertEquals(listOf(RichTextBuffer.LinkSpan(0, 5, url.href)), inside.linkSpans())
    }

    @Test
    fun aLinkSplitByOtherFormattingIsStillOneLink() {
        val url = Mark.Link("https://example.com")
        val buffer = editor(Block(id = "b", runs = listOf(
            Run("go "), Run("bold", setOf(Mark.Bold, url)), Run("link", setOf(url)), Run(" end"),
        )))
        assertEquals(LinkTarget("boldlink", url.href), buffer.select(TextSelection(11)).linkTarget)
        assertEquals(listOf(RichTextBuffer.LinkSpan(3, 11, url.href)), buffer.linkSpans())
        assertEquals(LinkTarget("boldlink", url.href), buffer.select(TextSelection(3)).linkTarget)
        assertEquals(LinkTarget("", null), buffer.select(TextSelection(13)).linkTarget)
        val selected = buffer.select(TextSelection(3, 7))
        assertEquals(LinkTarget("bold", url.href), selected.linkTarget)
        assertEquals(listOf(null, url.href, url.href, null), listOf(2, 3, 10, 11).map(buffer::linkUrlAt),
            "opening follows the character under the pointer, not the caret's either-side rule")
    }

    @Test
    fun selectedFragmentKeepsMarksAndParagraphStylesCutToTheSelection() {
        val buffer = editor(
            Block(id = "a", type = BlockType.Heading1, runs = listOf(Run("Title "), Run("bold", setOf(Mark.Bold)))),
            Block(id = "b", type = BlockType.Bullet, indent = 2, runs = listOf(Run("item one"))),
            Block(id = "c", type = BlockType.Todo, checked = true, runs = listOf(Run("done"))),
        ).select(TextSelection(8, 22))

        val fragment = buffer.selectedFragment()

        assertEquals(listOf("ld", "item one", "do"), fragment.map { it.text })
        assertEquals(setOf(Mark.Bold), fragment[0].runs.single().marks)
        assertEquals(listOf(BlockType.Heading1, BlockType.Bullet, BlockType.Todo), fragment.map { it.type })
        assertEquals(2, fragment[1].indent)
        assertEquals(true, fragment[2].checked)
        assertTrue(editor(Block.of("abc")).select(TextSelection(1)).selectedFragment().isEmpty())
    }

    @Test
    fun aPieceOfOneParagraphPastesIntoTheParagraphAtTheCaretWithItsMarks() {
        val copied = editor(Block(id = "s", type = BlockType.Heading1,
            runs = listOf(Run("big "), Run("bold", setOf(Mark.Bold))))).select(TextSelection(2, 8)).selectedFragment()
        val target = editor(Block(id = "t", type = BlockType.Quote, runs = listOf(Run("ab")))).select(TextSelection(1))

        val pasted = target.insertFragment(copied)

        assertEquals("ag boldb", pasted.text)
        val block = pasted.blocks.single()
        assertEquals("t", block.id)
        assertEquals(BlockType.Quote, block.type, "a piece of a paragraph takes the paragraph it lands in")
        assertEquals(listOf(Run("ag "), Run("bold", setOf(Mark.Bold)), Run("b")), block.runs)
        assertEquals(TextSelection(7), pasted.selection)
        assertEquals(setOf(Mark.Bold), pasted.typingMarks, "typing goes on in what was just pasted")
    }

    @Test
    fun pastedParagraphsKeepTheirStylesExceptWhereTheyShareALineWithTextAlreadyThere() {
        val copied = editor(
            Block(id = "h", type = BlockType.Heading2, runs = listOf(Run("head"))),
            Block(id = "m", type = BlockType.Numbered, indent = 1, runs = listOf(Run("middle", setOf(Mark.Italic)))),
            Block(id = "l", type = BlockType.Bullet, runs = listOf(Run("last"))),
        ).select(TextSelection(0, 16)).selectedFragment()

        val intoText = editor(Block(id = "t", runs = listOf(Run("before after")))).select(TextSelection(7))
            .insertFragment(copied)
        assertEquals("before head\nmiddle\nlastafter", intoText.text)
        assertEquals(listOf(BlockType.Paragraph, BlockType.Numbered, BlockType.Paragraph), intoText.blocks.map { it.type })
        assertEquals("t", intoText.blocks.first().id)
        assertTrue(intoText.blocks.drop(1).none { it.id in setOf("t", "h", "m", "l") }, "pasted blocks get new ids")
        assertEquals(1, intoText.blocks[1].indent)
        assertEquals(setOf(Mark.Italic), intoText.blocks[1].runs.single().marks)
        assertEquals(TextSelection(7 + 16), intoText.selection)

        val intoEmpty = editor(Block(id = "e")).insertFragment(copied)
        assertEquals(listOf(BlockType.Heading2, BlockType.Numbered, BlockType.Bullet), intoEmpty.blocks.map { it.type })
        assertEquals("head\nmiddle\nlast", intoEmpty.text)
    }

    @Test
    fun pastingOverASelectionAcrossParagraphsReplacesIt() {
        val copied = editor(Block.of("one"), Block.of("two")).select(TextSelection(1, 5)).selectedFragment()
        val target = editor(Block(id = "x", runs = listOf(Run("abc"))), Block(id = "y", runs = listOf(Run("def"))))
            .select(TextSelection(1, 6))

        val pasted = target.insertFragment(copied)

        assertEquals("ane\ntf", pasted.text)
        assertEquals(listOf("x", "y"), pasted.blocks.map { it.id })
        assertEquals(TextSelection(5), pasted.selection)
    }

    /** Ctrl+Enter: the line after the caret is a plain paragraph, the list item before it untouched. */
    @Test
    fun aPlainParagraphLeavesTheListWhereEnterWouldMakeAnotherItem() {
        val list = editor(Block(id = "a", type = BlockType.Bullet, indent = 1, align = Align.Center,
            runs = listOf(Run("item", setOf(Mark.Bold)))))

        val atEnd = list.select(TextSelection(4)).insertPlainParagraph()
        assertEquals("item\n", atEnd.text)
        assertEquals(listOf(BlockType.Bullet, BlockType.Paragraph), atEnd.blocks.map { it.type })
        assertEquals("a", atEnd.blocks.first().id)
        assertEquals(listOf(1, 1), atEnd.blocks.map { it.indent }, "the indent carries on as with Enter")
        assertEquals(Align.Center, atEnd.blocks.last().align)
        assertEquals(TextSelection(5), atEnd.selection)
        assertEquals("item\nnext", atEnd.replace("next").text)
        assertEquals(BlockType.Paragraph, atEnd.replace("next").blocks.last().type)
        assertEquals(BlockType.Bullet, list.select(TextSelection(4)).replace("\n").blocks.last().type,
            "Enter itself still makes another item")

        val inMiddle = list.select(TextSelection(2)).insertPlainParagraph()
        assertEquals(listOf("it", "em"), inMiddle.blocks.map { it.text })
        assertEquals(listOf(BlockType.Bullet, BlockType.Paragraph), inMiddle.blocks.map { it.type })
        assertEquals(setOf(Mark.Bold), inMiddle.blocks.last().runs.single().marks)

        val overSelection = list.select(TextSelection(1, 3)).insertPlainParagraph()
        assertEquals("i\nm", overSelection.text)
        assertEquals(TextSelection(2), overSelection.selection)
    }

    @Test
    fun aPlainParagraphAlsoLeavesToDosQuotesCodeAndHeadings() {
        listOf(BlockType.Todo, BlockType.Quote, BlockType.Code, BlockType.Heading1, BlockType.Numbered).forEach { type ->
            val block = Block(id = "b", type = type, runs = listOf(Run("text")),
                checked = if (type == BlockType.Todo) true else null)
            val split = editor(block).select(TextSelection(4)).insertPlainParagraph()
            assertEquals(type, split.blocks.first().type)
            assertEquals(block.checked, split.blocks.first().checked, "the item keeps its tick")
            assertEquals(BlockType.Paragraph, split.blocks.last().type, "after $type")
            assertEquals(null, split.blocks.last().checked)
        }
    }

    /** What another app copied as one heading or item is that paragraph, pasted onto an empty line. */
    @Test
    fun formattedTextFromAnotherAppKeepsTheStyleOfAWholeParagraph() {
        val heading = listOf(Block(id = "h", type = BlockType.Heading2, runs = listOf(Run("Title"))))

        val onEmpty = editor(Block.of("before"), Block(id = "e"), Block.of("after")).select(TextSelection(7))
            .insertFormatted(heading)
        assertEquals("before\nTitle\nafter", onEmpty.text)
        assertEquals(listOf(BlockType.Paragraph, BlockType.Heading2, BlockType.Paragraph), onEmpty.blocks.map { it.type })
        assertEquals("e", onEmpty.blocks[1].id)

        val overWholeLine = editor(Block.of("replace me")).select(TextSelection(0, 10)).insertFormatted(heading)
        assertEquals(BlockType.Heading2, overWholeLine.blocks.single().type)

        val intoText = editor(Block.of("see here")).select(TextSelection(4)).insertFormatted(heading)
        assertEquals("see Titlehere", intoText.text)
        assertEquals(BlockType.Paragraph, intoText.blocks.single().type, "words in a sentence take the sentence's style")

        val plain = editor(Block(id = "b", type = BlockType.Bullet)).insertFormatted(listOf(Block.of("words")))
        assertEquals(BlockType.Bullet, plain.blocks.single().type, "a plain paragraph leaves the item its bullet")

        val two = editor(Block(id = "e")).insertFormatted(listOf(Block.of("Head", BlockType.Heading1), Block.of("item", BlockType.Bullet)))
        assertEquals(listOf(BlockType.Heading1, BlockType.Bullet), two.blocks.map { it.type })
    }
}
