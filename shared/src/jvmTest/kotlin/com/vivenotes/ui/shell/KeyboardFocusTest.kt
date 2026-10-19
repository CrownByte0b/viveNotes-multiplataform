package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.model.Outline
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.ui.navigation.NavigationTestTags
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.settings.InterfaceTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keys reach only the focused element and what contains it. These start the way a person does —
 * no click on the canvas first — and keep pressing shortcuts after the focus has been somewhere
 * else, which is where the shortcuts first failed in the real app.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardFocusTest {

    /** Regression: at launch nothing had the focus, so no shortcut worked until the canvas was clicked. */
    @Test
    fun shortcutsWorkBeforeAnythingIsClicked() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()
        ctrl(Key.Equals)
        assertZoom(125)
    }

    @Test
    fun shortcutsWorkAfterEscDiscardsAnEmptyTextBox() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(WorkspaceState.demo().toggleTextTool()) { observed = it }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(700f, 600f)) }
        val boxes = runOnIdle { observed.selectedPage!!.document.outlines.count { it is Outline.Text } }
        press(Key.Escape)
        runOnIdle {
            assertEquals(boxes - 1, observed.selectedPage!!.document.outlines.count { it is Outline.Text },
                "Esc did not discard the empty text box")
        }
        ctrl(Key.Equals)
        assertZoom(125)
        val pages = runOnIdle { observed.selectedSection!!.pages.size }
        ctrl(Key.N)
        runOnIdle { assertEquals(pages + 1, observed.selectedSection!!.pages.size) }
    }

    /**
     * Regression: Esc ended the editing but the text field kept the keyboard, so Ctrl+Z undid the
     * text instead of the canvas and typing went on into the box.
     */
    @Test
    fun afterEscTheTextBoxNoLongerTakesKeys() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val base = WorkspaceState.demo()
        val shape = Outline.Shape(id = "esc-shape", x = 300f, y = 500f)
        val withShape = base.copy(notebooks = base.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                        outlines = page.document.outlines + shape)) else page
                })
            })
        })
        var observed = withShape.selectObject(shape.id).moveSelectedObjects(40f, 0f)
        setWorkspace(observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
        // Something for the field's own undo to take back, were Ctrl+Z to reach it.
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInput("q")
        val typed = runOnIdle { observed.richText!!.text }

        press(Key.Escape)
        runOnIdle { assertEquals(null, observed.focusedTextOutlineId) }
        ctrl(Key.Z)
        runOnIdle {
            assertEquals(300f, observed.selectedPage!!.document.outlines
                .filterIsInstance<Outline.Shape>().first { it.id == shape.id }.x, "Ctrl+Z did not undo the canvas")
            assertEquals(null, observed.focusedTextOutlineId, "Ctrl+Z went back into the text box")
            val body = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
                .first { it.id == observed.bodyTextOutline?.id }
            assertEquals(typed, RichTextBuffer(body.blocks).text, "Ctrl+Z undid the text")
        }
    }

    @Test
    fun shortcutsWorkAfterADialogCloses() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()
        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.Cancel).performClick()
        ctrl(Key.Equals)
        assertZoom(125)

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Settings)).performClick()
        onNodeWithTag(InterfaceTags.Open).performClick()
        onNodeWithTag(InterfaceTags.Cancel).performClick()
        ctrl(Key.Minus)
        assertZoom(100)
    }

    @Test
    fun shortcutsWorkAfterClickingThePageListAndTheRibbon() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performClick()
        val pages = runOnIdle { observed.selectedSection!!.pages.size }
        ctrl(Key.N)
        runOnIdle { assertEquals(pages + 1, observed.selectedSection!!.pages.size) }

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.View)).performClick()
        ctrl(Key.Equals)
        assertZoom(125)
        ctrl(Key.Zero)
        assertZoom(100)
        runOnIdle { assertTrue(observed.activeTab == RibbonTab.View) }
    }

    /** I is the Document tab's Text button, from a fresh launch with nothing clicked, both ways. */
    @Test
    fun iTogglesTheTextBoxToolBeforeAnythingIsClicked() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        press(Key.I)
        runOnIdle { assertTrue(observed.textToolArmed, "I did not arm the Text box tool") }
        onNodeWithTag(DocumentRibbonTags.Text).assertIsSelected()
        press(Key.I)
        runOnIdle { assertFalse(observed.textToolArmed, "a second I did not put the tool down") }
        onNodeWithTag(DocumentRibbonTags.Text).assertIsNotSelected()
    }

    /**
     * Where I would be typed — a text box, the page title, a dialog's field — it is the letter, and
     * the tool stays as it was. Once the keyboard has left the field, I is the shortcut again.
     */
    @Test
    fun iInATextFieldIsALetterNotTheShortcut() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
        press(Key.I)
        runOnIdle { assertFalse(observed.textToolArmed, "I in a text box armed the tool") }
        press(Key.Escape)
        press(Key.I)
        runOnIdle { assertTrue(observed.textToolArmed, "I after Esc left the text box did nothing") }
        press(Key.I)

        onNodeWithTag(WorkspaceTestTags.TitleEditor).performClick()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertIsFocused()
        press(Key.I)
        runOnIdle { assertFalse(observed.textToolArmed, "I in the page title armed the tool") }

        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performMouseInput { rightClick(center) }
        onNodeWithTag(NavigationTestTags.Rename).performClick()
        onNodeWithTag(NavigationTestTags.NameField).assertIsFocused()
        press(Key.I)
        runOnIdle { assertFalse(observed.textToolArmed, "I in the rename field armed the tool") }
        onNodeWithTag(NavigationTestTags.Cancel).performClick()

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(700f, 600f)) }
        press(Key.I)
        runOnIdle { assertTrue(observed.textToolArmed, "I on the page after the fields did nothing") }
    }

    private fun ComposeUiTest.assertZoom(percent: Int) {
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Canvas zoom $percent percent"))
    }

    private fun ComposeUiTest.press(key: Key) = onRoot().performKeyInput { keyDown(key); keyUp(key) }

    private fun ComposeUiTest.ctrl(key: Key) =
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft) }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo(),
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
            }
        }
    }
}
