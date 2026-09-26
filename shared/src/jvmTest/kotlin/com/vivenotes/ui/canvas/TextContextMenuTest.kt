package com.vivenotes.ui.canvas

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.focusBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The text box's right-click menu and its clipboard shortcuts, through the real editor. */
@OptIn(ExperimentalTestApi::class)
class TextContextMenuTest {

    /** The demo's open page, "Review" made bold, nothing focused. */
    private val boldReview = WorkspaceState.demo().focusBody().selectText(TextSelection(0, 6))
        .toggleSelectedMark(Mark.Bold).clearCanvasFocus()

    /** A point on the word "Review", in the editor's coordinates. */
    private val onFirstWord = Offset(15f, 10f)

    @Test
    fun rightClickingTextEditsThatBoxAndOffersItsCommands() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val clipboard = FakeClipboard()
        var observed = boldReview
        setWorkspace(boldReview, clipboard) { observed = it }

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(1f, 10f)) }

        runOnIdle {
            assertEquals(observed.bodyTextOutline!!.id, observed.focusedTextOutlineId)
            assertEquals(TextSelection(0), observed.editorSelection, "the caret goes where the click was")
        }
        onNodeWithTag(TextMenuTags.SelectAll).assertIsEnabled()
        onNodeWithTag(TextMenuTags.Cut).assertIsNotEnabled()
        onNodeWithTag(TextMenuTags.Copy).assertIsNotEnabled()
        onNodeWithTag(TextMenuTags.Paste).assertIsNotEnabled()
        onNodeWithTag(TextMenuTags.PastePlainText).assertIsNotEnabled()

        onNodeWithTag(TextMenuTags.SelectAll).performClick()

        onNodeWithTag(TextMenuTags.SelectAll).assertDoesNotExist()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
        runOnIdle { assertEquals(TextSelection(0, observed.richText!!.text.length), observed.editorSelection) }
    }

    @Test
    fun everyCommandShowsItsNameAndShortcutOnOneLine() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace(boldReview, FakeClipboard()) {}

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(onFirstWord) }

        val commands = listOf(
            TextMenuTags.Cut to listOf("Cut", "Ctrl+X"),
            TextMenuTags.Copy to listOf("Copy", "Ctrl+C"),
            TextMenuTags.Paste to listOf("Paste", "Ctrl+V"),
            TextMenuTags.PastePlainText to listOf("Paste as plain text", "Ctrl+Shift+V"),
            TextMenuTags.SelectAll to listOf("Select all", "Ctrl+A"),
        )
        commands.forEach { (tag, words) -> onNodeWithTag(tag).assertTextEquals(*words.toTypedArray()) }
        // A shortcut squeezed onto a second line makes its row taller than the rest.
        val heights = commands.map { (tag) -> onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top } }
        assertEquals(1, heights.toSet().size, "rows differ in height: $heights")
    }

    @Test
    fun cutMovesTheSelectionToTheClipboard() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val clipboard = FakeClipboard()
        var observed = boldReview
        setWorkspace(boldReview, clipboard) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)

        editor.performTextInputSelection(TextRange(0, 6))
        val rest = runOnIdle { observed.richText!!.text.drop(6) }
        editor.performMouseInput { rightClick(onFirstWord) }
        onNodeWithTag(TextMenuTags.Cut).assertIsEnabled().performClick()

        onNodeWithTag(TextMenuTags.Cut).assertDoesNotExist()
        editor.assertIsFocused()
        runOnIdle {
            assertEquals("Review", clipboard.value?.text)
            assertEquals(rest, observed.richText!!.text)
            assertNotNull(observed.textClipboard)
        }
    }

    /** The menu names Ctrl+A for Select all, so the key must do what the menu does. */
    @Test
    fun ctrlASelectsTheWholeBox() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = boldReview
        setWorkspace(boldReview, FakeClipboard()) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)

        editor.performTextInputSelection(TextRange(3))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }

        runOnIdle { assertEquals(TextSelection(0, observed.richText!!.text.length), observed.editorSelection) }
    }

    @Test
    fun rightClickOutsideTheSelectionMovesTheCaretAndInsideKeepsIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = boldReview
        setWorkspace(boldReview, FakeClipboard()) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)

        editor.performTextInputSelection(TextRange(0, 6))
        editor.performMouseInput { rightClick(onFirstWord) }
        runOnIdle { assertEquals(TextSelection(0, 6), observed.editorSelection) }
        onNodeWithTag(TextMenuTags.Copy).assertIsEnabled()
    }

    @Test
    fun copyThenPasteBringsTheFormattingBackAndPastePlainTextDoesNot() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val clipboard = FakeClipboard()
            var observed = boldReview
            setWorkspace(boldReview, clipboard) { observed = it }
            val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)

            editor.performTextInputSelection(TextRange(0, 6))
            editor.performMouseInput { rightClick(onFirstWord) }
            onNodeWithTag(TextMenuTags.Copy).performClick()
            runOnIdle {
                assertEquals("Review", clipboard.value?.text)
                assertNotNull(observed.textClipboard)
            }
            editor.assertIsFocused()

            // Everything selected, so the right-click keeps it and Paste replaces it.
            val length = observed.richText!!.text.length
            editor.performTextInputSelection(TextRange(0, length))
            editor.performMouseInput { rightClick(onFirstWord) }
            onNodeWithTag(TextMenuTags.Paste).assertIsEnabled().performClick()
            runOnIdle {
                val block = observed.richText!!.blocks.single()
                assertEquals("Review", block.text)
                assertEquals(setOf(Mark.Bold), block.runs.single().marks)
            }

            editor.performTextInputSelection(TextRange(0, 6))
            editor.performMouseInput { rightClick(onFirstWord) }
            onNodeWithTag(TextMenuTags.PastePlainText).performClick()
            runOnIdle {
                val block = observed.richText!!.blocks.single()
                assertEquals("Review", block.text)
                assertEquals(emptySet(), block.runs.single().marks)
            }
            editor.assertIsFocused()
        }

    @Test
    fun ctrlCAndCtrlVKeepFormattingAndCtrlShiftVPastesPlainText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val clipboard = FakeClipboard()
        var observed = boldReview
        setWorkspace(boldReview, clipboard) { observed = it }
        val editor = onNodeWithTag(WorkspaceTestTags.BodyEditor)

        editor.performTextInputSelection(TextRange(0, 6))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        runOnIdle { assertEquals("Review", clipboard.value?.text) }

        val length = observed.richText!!.text.length
        editor.performTextInputSelection(TextRange(length))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        runOnIdle {
            val last = observed.richText!!.blocks.last().runs.last()
            assertEquals("Review", last.text)
            assertEquals(setOf(Mark.Bold), last.marks)
        }

        editor.performTextInputSelection(TextRange(0))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.V) } } }
        runOnIdle {
            assertEquals("ReviewReview", observed.richText!!.blocks.first().text.take(12))
            // Plain text takes the formatting at the caret, as typing there would: here, bold.
            assertEquals("ReviewReview", observed.richText!!.blocks.first().runs.first().text)
        }

        editor.performTextInputSelection(TextRange(0, 6))
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }
        runOnIdle {
            assertEquals("Review", clipboard.value?.text)
            assertEquals("Review", observed.richText!!.blocks.first().runs.first().text)
        }
    }

    @Test
    fun aRightClickOnAToDoOpensTheMenuWithoutTickingIt() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val todo = WorkspaceState.demo().focusBody().setSelectedBlockType(BlockType.Todo).clearCanvasFocus()
        var observed = todo
        setWorkspace(todo, FakeClipboard()) { observed = it }

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(11f, 10f)) }

        onNodeWithTag(TextMenuTags.SelectAll).assertIsDisplayed()
        runOnIdle { assertEquals(false, observed.richText!!.blocks.first().checked) }
    }

    @Test
    fun aRightClickOnBarePageDoesNotPlaceATextBox() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val armed = WorkspaceState.demo().toggleTextTool()
        var observed = armed
        setWorkspace(armed, FakeClipboard()) { observed = it }
        fun textBoxes() = observed.selectedPage!!.document.outlines.count { it is Outline.Text }

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { rightClick(Offset(780f, 500f)) }
        runOnIdle { assertEquals(1, textBoxes()) }
        onNodeWithTag(TextMenuTags.SelectAll).assertDoesNotExist()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
        runOnIdle { assertEquals(2, textBoxes(), "a left click there does place one") }
    }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState,
        clipboard: ClipboardManager,
        onStateChange: (WorkspaceState) -> Unit,
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                }
            }
        }
    }

    private class FakeClipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
