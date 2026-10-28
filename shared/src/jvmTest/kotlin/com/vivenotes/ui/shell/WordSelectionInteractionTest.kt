package com.vivenotes.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.localization.Strings
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.Run
import com.vivenotes.model.newTable
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.draw.TableTags
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.textSelectionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WordSelectionInteractionTest {
    private val table = newTable(2, 1, x = 60f, y = 280f).let { grid -> grid.copy(rows = grid.rows.map { row ->
        row.copy(cells = row.cells.mapIndexed { index, cell ->
            cell.copy(blocks = listOf(Block.of(if (index == 0) "alpha beta" else "gamma omega")))
        })
    }) }
    private fun initial() = WorkspaceState.demo().let { state -> state.updatePage(state.selectedPageId) { page ->
        page.copy(document = page.document.copy(outlines = listOf(state.bodyTextOutline!!.copy(
            blocks = listOf(Block.of("one middle three"))), table)))
    } }.clearCanvasFocus()

    @Test
    fun ctrlDoubleClickKeepsBothWordsHighlightedAndCopyUsesBoth() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        val clipboard = Clipboard()
        workspace(observed, clipboard) { observed = it }
        val field = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        val layout = field.layout()
        val before = field.captureToImage().toPixelMap()
        holdCtrl()
        field.doubleWord(1)
        field.doubleWord(13)
        releaseCtrl()
        runOnIdle {
            assertEquals("one\nthree", observed.selectedText)
            assertEquals(listOf(TextSelection(0, 3), TextSelection(11, 16)), observed.textSelectionsFor(observed.bodyTextOutline!!.id))
        }
        val after = field.captureToImage().toPixelMap()
        val box = layout.getBoundingBox(0)
        var changed = 0
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(after.height)) {
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(after.width)) {
                if (before[x, y] != after[x, y]) changed++
            }
        }
        assertTrue(changed > 10, "the earlier word must retain a visible selection highlight")
        ctrl(Key.C)
        runOnIdle {
            assertEquals("one\nthree", clipboard.value?.text)
            assertEquals(2, observed.textSelectionsFor(observed.bodyTextOutline!!.id).size)
        }
        onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).performClick()
        runOnIdle {
            val buffer = RichTextBuffer(observed.bodyTextOutline!!.blocks)
            assertTrue(Mark.Bold in buffer.select(TextSelection(0, 3)).activeMarks)
            assertFalse(Mark.Bold in buffer.select(TextSelection(4, 10)).activeMarks)
            assertTrue(Mark.Bold in buffer.select(TextSelection(11, 16)).activeMarks)
            assertEquals(1, observed.structuralUndo.size)
        }
    }

    @Test
    fun ctrlDoubleClickAcrossCellsFormatsCopiesAndUndoesAllSelectedWords() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial().selectObject(table.id)
        val clipboard = Clipboard()
        workspace(observed, clipboard) { observed = it }
        val leftId = table.cellAt(0, 0)!!.id
        val rightId = table.cellAt(0, 1)!!.id
        holdCtrl()
        onNodeWithTag(TableTags.cell(rightId)).doubleWord(8)
        onNodeWithTag(TableTags.cell(leftId)).doubleWord(2)
        releaseCtrl()
        runOnIdle { assertEquals("alpha\nomega", observed.selectedText) }
        ctrl(Key.C)
        runOnIdle { assertEquals("alpha\nomega", clipboard.value?.text) }
        ctrl(Key.B)
        runOnIdle {
            fun marks(id: String, range: TextSelection) = RichTextBuffer(observed.selectedPage!!.document.outlines
                .filterIsInstance<Outline.Table>().single().rows.flatMap { it.cells }.single { it.id == id }.blocks)
                .select(range).activeMarks
            assertTrue(Mark.Bold in marks(leftId, TextSelection(0, 5)))
            assertFalse(Mark.Bold in marks(leftId, TextSelection(6, 10)))
            assertFalse(Mark.Bold in marks(rightId, TextSelection(0, 5)))
            assertTrue(Mark.Bold in marks(rightId, TextSelection(6, 11)))
            assertEquals(1, observed.structuralUndo.size)
        }
        ctrl(Key.Z)
        runOnIdle { assertEquals(table, observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Table>().single()) }
        ctrl(Key.R)
        runOnIdle { assertEquals("alpha\nomega", observed.selectedText) }
        val point = layoutPoint(onNodeWithTag(TableTags.cell(leftId)), 2)
        onNodeWithTag(TableTags.cell(leftId)).performMouseInput { rightClick(point) }
        onNodeWithText(Strings.copy).performClick()
        runOnIdle { assertEquals("alpha\nomega", clipboard.value?.text) }
    }

    @Test
    fun typingReplacesEverySelectedWordAndAnOrdinaryClickClearsExtraSelections() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = initial()
        workspace(observed, Clipboard()) { observed = it }
        val field = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        holdCtrl()
        field.doubleWord(1)
        field.doubleWord(13)
        releaseCtrl()
        field.performTextInput("new")
        runOnIdle { assertEquals("new middle new", observed.richText!!.text) }
        field.performTextInput("!")
        runOnIdle { assertEquals("new! middle new!", observed.richText!!.text) }
        field.performClick()
        runOnIdle { assertNull(observed.wordSelections) }
        field.doubleWord(5)
        runOnIdle {
            assertEquals("middle", observed.selectedText)
            assertNull(observed.wordSelections)
        }
    }

    @Test
    fun ctrlDoubleClickSelectsLinkedWordsWithoutOpeningTheLink() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val base = initial()
        var observed = base.updatePage(base.selectedPageId) { page -> page.copy(document = page.document.copy(
            outlines = page.document.outlines.map { if (it is Outline.Text) it.copy(blocks = listOf(Block(
                id = "linked", runs = listOf(Run("one", setOf(Mark.Link("https://example.org"))), Run(" middle three"))))) else it })) }
        val opened = mutableListOf<String>()
        workspace(observed, Clipboard(), uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }) { observed = it }
        holdCtrl()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).doubleWord(1)
        onNodeWithTag(WorkspaceTestTags.BodyEditor).doubleWord(13)
        releaseCtrl()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        runOnIdle {
            assertEquals("one\nthree", observed.selectedText)
            assertTrue(opened.isEmpty())
        }
    }

    private fun ComposeUiTest.workspace(initial: WorkspaceState, clipboard: Clipboard,
        uriHandler: UriHandler = object : UriHandler { override fun openUri(uri: String) {} },
        observe: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalUriHandler provides uriHandler) {
                WorkspaceScreen(state, { state = it(state); observe(state) })
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }
    private fun layoutPoint(field: SemanticsNodeInteraction, offset: Int) = field.layout().getBoundingBox(offset).center
    private fun SemanticsNodeInteraction.doubleWord(offset: Int) {
        val point = layout().getBoundingBox(offset).center
        performMouseInput { doubleClick(point) }
    }
    private fun ComposeUiTest.holdCtrl() = onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
    private fun ComposeUiTest.releaseCtrl() = onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
    private fun ComposeUiTest.ctrl(key: Key) = onRoot().performKeyInput {
        keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft)
    }
    private class Clipboard(var value: AnnotatedString? = null) : ClipboardManager {
        override fun getText() = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
