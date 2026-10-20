package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.Run
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.LocalClipboardHtml
import com.vivenotes.ui.canvas.TextMenuTags
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text box bugs reported on the Test page, each from a fresh workspace, as a person meets them. */
@OptIn(ExperimentalTestApi::class)
class TextBoxEditingTest {

    /** The demo page with its first text box — 720 dp wide, under the title — holding [blocks], unedited. */
    private fun page(vararg blocks: Block): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        val id = demo.bodyTextOutline!!.id
        return demo.updatePage(demo.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = page.document.outlines.map { outline ->
                if (outline is Outline.Text && outline.id == id) outline.copy(blocks = blocks.toList()) else outline
            }))
        }.clearCanvasFocus()
    }

    private fun WorkspaceState.boxId(): String = bodyTextOutline!!.id

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.lineMiddle(line: Int): Float = (getLineTop(line) + getLineBottom(line)) / 2f

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) < 0.12f && abs(green - other.green) < 0.12f && abs(blue - other.blue) < 0.12f

    /** Reported: bullets, numbers and to-do boxes vanished from a box nobody was editing. */
    @Test
    fun listMarkersStayWhenTheBoxShowsItsPreview() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val linked = Block(id = "l", runs = listOf(Run("see "), Run("docs", setOf(Mark.Link("https://example.com")))))
        val state = page(linked, Block.of("one", BlockType.Bullet), Block.of("two", BlockType.Numbered),
            Block(id = "t", type = BlockType.Todo, runs = listOf(Run("three")), checked = false))
        var observed = state
        setWorkspace(state) { observed = it }

        val preview = onNodeWithTag("text-box-preview").assertExists()
        val pixels = preview.captureToImage().toPixelMap()
        val green = Color(0xFF4CAF50)
        val accent = (0 until minOf(40, pixels.width)).sumOf { x ->
            (0 until pixels.height).count { y -> pixels[x, y].near(green) }
        }
        assertTrue(accent > 20, "the markers' accent should be drawn in the margin, found $accent pixels")

        // The to-do's box ticks it from the preview too, without opening the box.
        val layout = preview.textLayout()
        preview.performMouseInput { click(Offset(11f, layout.lineMiddle(3))) }
        runOnIdle {
            assertEquals(true, observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
                .first { it.id == state.boxId() }.blocks.last().checked)
            assertEquals(null, observed.focusedTextOutlineId)
        }
    }

    /** A click on a preview edits where it was clicked, not at the box's first character. */
    @Test
    fun clickingAPreviewStartsEditingWhereClicked() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val state = page(Block.of("Area is \$x\$ here."), Block.of("second line"), Block.of("third line"))
        var observed = state
        setWorkspace(state) { observed = it }

        val preview = onNodeWithTag("text-box-preview")
        val layout = preview.textLayout()
        preview.performMouseInput { click(Offset(layout.getHorizontalPosition(layout.layoutInput.text.length - 4, true),
            layout.lineMiddle(2))) }

        runOnIdle {
            assertEquals(state.boxId(), observed.focusedTextOutlineId)
            val caret = observed.editorSelection
            assertTrue(caret.collapsed)
            val thirdLine = "Area is \$x\$ here.\nsecond line\n".length
            assertTrue(caret.start in thirdLine..thirdLine + 10, "the caret is on the line clicked: $caret")
        }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
    }

    /**
     * Reported on the Test page: a click in a box taller than the window moved the page. Typing must
     * still keep the caret in view.
     */
    @Test
    fun clickingATallBoxLeavesThePageWhereItIs() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val lines = (1..180).map { Block.of("line $it") }.toTypedArray()
        val state = page(*lines)
        var observed = state
        setWorkspace(state) { observed = it }
        val box = onNodeWithTag(WorkspaceTestTags.textBox(state.boxId()))
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val canvasHeight = canvas.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        repeat(3) {
            canvas.performMouseInput { moveTo(Offset(200f, canvasHeight / 2)); scroll(10f) }
            waitForIdle()
        }
        val scrolled = box.getUnclippedBoundsInRoot()
        assertTrue(scrolled.top < canvas.getUnclippedBoundsInRoot().top, "the box's top should be above the window: $scrolled")

        canvas.performMouseInput { click(Offset(200f, canvasHeight / 2)) }
        waitForIdle()
        runOnIdle { assertEquals(state.boxId(), observed.focusedTextOutlineId) }
        assertEquals(scrolled, box.getUnclippedBoundsInRoot(), "clicking the box scrolled the page")
        val caret = observed.editorSelection.start
        assertTrue(caret > "line 10\n".length * 5, "the caret is where the box was clicked, not at its top: $caret")

        // Low in the window, Enter pushes the caret out of it, and the page follows the caret.
        canvas.performMouseInput { click(Offset(200f, canvasHeight - 30f)) }
        waitForIdle()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
        val beforeTyping = box.getUnclippedBoundsInRoot()
        val beforeCaret = observed.editorSelection.start
        val beforeLength = observed.richTextFor(state.boxId())!!.text.length
        repeat(8) { onNodeWithTag(WorkspaceTestTags.BodyEditor).performKeyInput { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals(beforeLength + 8, observed.richTextFor(state.boxId())!!.text.length,
            "the focused box must receive all eight newlines")
        val afterTyping = box.getUnclippedBoundsInRoot()
        assertTrue(afterTyping.top < beforeTyping.top,
            "typing below the window should scroll to the caret: before=$beforeTyping after=$afterTyping " +
                "caret=$beforeCaret->${observed.editorSelection.start} " +
                "length=$beforeLength->${observed.richTextFor(state.boxId())!!.text.length}")
    }

    /** Reported: the caret of a new, empty list item stood left of its bullet. */
    @Test
    fun anEmptyLastItemsCaretSitsWhereItsTextWillStart() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val state = page(Block.of("item", BlockType.Bullet), Block(id = "e", type = BlockType.Bullet))
        setWorkspace(state)

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
        val layout = onNodeWithTag(WorkspaceTestTags.BodyEditor).textLayout()
        val textStart = layout.getCursorRect(0).left
        assertTrue(textStart > 15f, "a list item's text starts after its marker: $textStart")
        assertEquals(textStart, layout.getCursorRect("item\n".length).left, 0.5f)
    }

    /** Ctrl+Enter makes the next line a plain paragraph: the way out of a list. */
    @Test
    fun ctrlEnterLeavesTheList() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val state = page(Block.of("one", BlockType.Bullet), Block.of("two", BlockType.Bullet))
        var observed = state
        setWorkspace(state) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        val layout = editor.textLayout()
        editor.performMouseInput { click(Offset(layout.getLineRight(1) + 200f, layout.lineMiddle(1))) }
        runOnIdle { assertEquals(TextSelection("one\ntwo".length), observed.editorSelection) }

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        editor.performTextInput("after")

        runOnIdle {
            val blocks = observed.richText!!.blocks
            assertEquals(listOf("one", "two", "after"), blocks.map { it.text })
            assertEquals(listOf(BlockType.Bullet, BlockType.Bullet, BlockType.Paragraph), blocks.map { it.type })
        }
        editor.performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(BlockType.Paragraph, observed.richText!!.blocks.last().type, "Enter now continues the paragraph") }
    }

    /** Found while fixing the caret: keys and clicks around empty lines and line ends went a line too far. */
    @Test
    fun upDownEndAndClicksStayOnTheLinesTheyAimFor() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val first = "a much longer first line"
        val state = page(Block.of(first), Block(id = "e"), Block.of("ab"), Block.of("xyz"))
        var observed = state
        setWorkspace(state) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        val layout = editor.textLayout()
        val empty = first.length + 1
        val ab = empty + 1
        val end = ab + "ab\nxyz".length

        // The empty line takes a click anywhere along it.
        listOf(5f, 300f).forEach { x ->
            editor.performMouseInput { click(Offset(x, layout.lineMiddle(1))) }
            runOnIdle { assertEquals(TextSelection(empty), observed.editorSelection, "click at $x") }
        }
        editor.performMouseInput { click(Offset(layout.getLineRight(0) + 300f, layout.lineMiddle(0))) }
        runOnIdle { assertEquals(TextSelection(first.length), observed.editorSelection, "past a line's end is its end") }

        fun press(key: Key, expected: Int) {
            editor.performKeyInput { pressKey(key) }
            runOnIdle { assertEquals(TextSelection(expected), observed.editorSelection, "after $key") }
        }
        press(Key.DirectionDown, empty)
        press(Key.DirectionDown, ab + 2)
        press(Key.DirectionDown, end)
        press(Key.DirectionUp, ab + 2)
        press(Key.DirectionUp, empty)
        press(Key.DirectionUp, first.length)
        press(Key.MoveHome, 0)
        press(Key.MoveEnd, first.length)

        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        runOnIdle { assertEquals(TextSelection(first.length, empty), observed.editorSelection) }
    }

    /** What a browser copies arrives with its headings, lists and marks, from every Paste. */
    @Test
    fun pastingFromABrowserKeepsWhatThePageShowed() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val clipboard = FakeClipboard(AnnotatedString("Steps\nOpen it\nClose it"))
        val html = ClipboardHtml { "<meta charset='utf-8'><h2>Steps</h2><ul><li>Open <b>it</b></li><li>Close it</li></ul>" }
        val state = page(Block.of("start"))
        var observed = state
        setWorkspace(state, clipboard, html) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        fun assertPasted(how: String, types: List<BlockType>) = runOnIdle {
            val pasted = observed.richText!!.blocks.takeLast(types.size)
            val texts = if (types.size == 4) listOf("Steps", "", "Open it", "Close it") else listOf("Steps", "Open it", "Close it")
            assertEquals(texts, pasted.map { it.text }, how)
            assertEquals(types, pasted.map { it.type }, how)
            if (types.first() == BlockType.Heading2) assertTrue(Mark.Bold in pasted[2].runs.last().marks, how)
        }
        // The page sets its list apart from the heading: a blank line between them.
        val formatted = listOf(BlockType.Heading2, BlockType.Paragraph, BlockType.Bullet, BlockType.Bullet)
        // Each paste goes on a new line at the end, which Enter makes after the last pasted item.
        fun newLine() = editor.performKeyInput { pressKey(Key.Enter) }

        editor.performMouseInput { click(Offset(400f, editor.textLayout().lineMiddle(0))) }
        newLine()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        assertPasted("Ctrl+V", formatted)

        newLine()
        onNodeWithTag(DocumentRibbonTags.Paste).performScrollTo().performClick()
        assertPasted("ribbon Paste", formatted)

        newLine()
        val layout = editor.textLayout()
        editor.performMouseInput { rightClick(Offset(60f, layout.lineMiddle(layout.lineCount - 1))) }
        onNodeWithTag(TextMenuTags.Paste).performClick()
        assertPasted("menu Paste", formatted)

        newLine()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.V) } } }
        assertPasted("Paste as plain text", List(3) { BlockType.Bullet })
    }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState,
        clipboard: ClipboardManager = FakeClipboard(),
        html: ClipboardHtml? = null,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalClipboardHtml provides html) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                }
            }
        }
    }

    private class FakeClipboard(var value: AnnotatedString? = null) : ClipboardManager {
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
