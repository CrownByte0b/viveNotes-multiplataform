package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reported: Undo and Redo ignored text formatting — a highlight, Center. Each runs here as a person
 * does it, from the ribbon and the keyboard, and comes back off and on through the same history.
 */
@OptIn(ExperimentalTestApi::class)
class TextUndoInteractionTest {
    private val yellow = Mark.Highlight(0x66FFEB3B)

    private fun page(text: String): WorkspaceState {
        val demo = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
        val id = demo.bodyTextOutline!!.id
        return demo.updatePage(demo.selectedPageId) { page ->
            page.copy(document = page.document.copy(outlines = page.document.outlines.map { outline ->
                if (outline is Outline.Text && outline.id == id) outline.copy(blocks = listOf(Block.of(text))) else outline
            }))
        }.clearCanvasFocus()
    }

    private fun ComposeUiTest.setWorkspace(initial: WorkspaceState, onStateChange: (WorkspaceState) -> Unit) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }

    private val WorkspaceState.marks: Set<Mark> get() = richText!!.blocks.first().runs.first().marks
    private val WorkspaceState.align: Align get() = richText!!.blocks.first().align

    @Test
    fun formattingFromTheRibbonAndKeysIsUndoneAndRedone() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page("hello world")
        setWorkspace(observed) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        editor.performClick()
        editor.performTextInputSelection(TextRange(0, 5))

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.alignment(Align.Center)).performMouseInput { click(center) }
        runOnIdle {
            assertEquals(setOf(Mark.Bold, yellow), observed.marks)
            assertEquals(Align.Center, observed.align)
        }

        fun undo() = editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        undo()
        runOnIdle {
            assertEquals(Align.Start, observed.align)
            assertEquals(setOf(Mark.Bold, yellow), observed.marks)
            assertEquals(TextSelection(0, 5), observed.editorSelection)
        }
        undo()
        runOnIdle { assertEquals(setOf<Mark>(Mark.Bold), observed.marks) }
        undo()
        runOnIdle {
            assertEquals(emptySet(), observed.marks)
            assertEquals("hello world", observed.richText!!.text)
        }
        // Still editing the box, so the keys go on reaching it.
        editor.assertIsFocused()

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
        runOnIdle { assertEquals(setOf<Mark>(Mark.Bold), observed.marks) }
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        runOnIdle { assertEquals(setOf(Mark.Bold, yellow), observed.marks) }
        onNodeWithTag(WorkspaceTestTags.StructuralRedo).performClick()
        runOnIdle { assertEquals(Align.Center, observed.align) }
        editor.assertIsFocused()

        onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
        runOnIdle { assertEquals(Align.Start, observed.align) }
        editor.assertIsFocused()
    }

    /** The text field's own undo, on keys no shortcut takes, would put text back without its formatting. */
    @Test
    fun theTextFieldsOwnUndoNeverRuns() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page("hello world")
        setWorkspace(observed) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        editor.performClick()
        editor.performTextInputSelection(TextRange(0, 5))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        editor.performTextInputSelection(TextRange(11))
        editor.performTextInput("!")
        val typed = runOnIdle { observed.selectedPage!!.document }

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        runOnIdle { assertEquals(typed, observed.selectedPage!!.document) }

        // With the history used up, Ctrl+Z still goes nowhere else.
        repeat(4) { editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } } }
        runOnIdle {
            assertEquals("hello world", observed.richText!!.text)
            assertEquals(emptySet(), observed.marks)
            assertEquals(emptyList(), observed.structuralUndo)
        }
    }

    @Test
    fun typingIsUndoneAWordAtATime() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = page("start")
        setWorkspace(observed) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)
        editor.performClick()
        editor.performTextInputSelection(TextRange(5))
        " one two".forEach { editor.performTextInput(it.toString()) }
        runOnIdle { assertEquals("start one two", observed.richText!!.text) }

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        runOnIdle { assertEquals("start one ", observed.richText!!.text) }
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        runOnIdle { assertEquals("start ", observed.richText!!.text) }
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
        runOnIdle {
            assertEquals("start one ", observed.richText!!.text)
            assertEquals(TextSelection(10), observed.editorSelection)
        }
    }
}
