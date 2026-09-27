package com.vivenotes.ui.ribbon.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings → Hardware: the keyboard shortcuts it lists and rebinds, and that the workspace then
 * dispatches by those bindings, in the text box as well as on the canvas.
 */
@OptIn(ExperimentalTestApi::class)
class HardwarePaneTest {

    private var reported: KeyBindings? = null

    @Test
    fun hardwareDocksAPaneListingEveryShortcut() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.Open).assertIsSelected()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Keyboard)).assertIsSelected()
        onNodeWithTag(HardwareTags.kind(HardwareKind.Stylus)).assertIsNotSelected().assertIsNotEnabled()
        ShortcutAction.entries.forEach { action ->
            onNodeWithTag(HardwareTags.shortcut(action)).performScrollTo()
                .assertTextContains(action.label)
                .assertTextContains(KeyBindings.Default.primary(action)!!.label)
            onNodeWithTag(HardwareTags.reset(action)).assertDoesNotExist()
        }
        onNodeWithTag(HardwareTags.ResetAll).performScrollTo().assertIsNotEnabled()

        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    /** One pane at a time, as on Android: Paper Size and Hardware replace each other. */
    @Test
    fun hardwareAndPaperSizeShareTheDock() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.View)).performClick()
        onNodeWithTag(com.vivenotes.ui.ribbon.view.ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(HardwareTags.Shortcuts).assertDoesNotExist()
        onNodeWithTag(ToolPaneTags.field("Size")).assertExists()
    }

    @Test
    fun pressingNewKeysChangesTheShortcutAndResetRestoresIt() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        onNodeWithTag(ShortcutDialogTags.Capture).assertExists()
        press(listOf(Key.CtrlLeft, Key.ShiftLeft, Key.U))

        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        val ctrlShiftU = KeyChord(ShortcutKey.U, ctrl = true, shift = true)
        runOnIdle { assertEquals(listOf(ctrlShiftU), reported!!.chords(ShortcutAction.Undo)) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+Shift+U")
        onNodeWithTag(HardwareTags.ResetAll).assertIsEnabled()

        onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).performClick()
        runOnIdle { assertEquals(KeyBindings.Default, reported) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+Z")
        onNodeWithTag(HardwareTags.reset(ShortcutAction.Undo)).assertDoesNotExist()
    }

    @Test
    fun aChordInUseIsOnlyTakenOnceReplaceIsChosen() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).performClick()
        press(listOf(Key.CtrlLeft, Key.R))

        onNodeWithTag(ShortcutDialogTags.Captured, useUnmergedTree = true).onChild().assertTextContains("Ctrl+R")
        onNodeWithTag(ShortcutDialogTags.Message, useUnmergedTree = true).assertTextContains("Redo", substring = true)
        runOnIdle { assertNull(reported, "a chord in use was taken without asking") }
        onNodeWithTag(ShortcutDialogTags.Replace).performClick()

        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Undo)).assertTextContains("Ctrl+R")
        // Redo keeps Android's Ctrl+Shift+Z, and shows it.
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Redo)).assertTextContains("Ctrl+Shift+Z")
        onNodeWithTag(HardwareTags.reset(ShortcutAction.Redo)).assertExists()
    }

    @Test
    fun enterAlsoConfirmsReplacing() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Bold)).performScrollTo().performClick()
        press(listOf(Key.CtrlLeft, Key.I))
        press(listOf(Key.Enter))
        runOnIdle {
            assertEquals(KeyChord(ShortcutKey.I, ctrl = true), reported!!.primary(ShortcutAction.Bold))
            assertNull(reported!!.primary(ShortcutAction.Italic))
        }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Italic)).assertTextContains("Disabled")
    }

    @Test
    fun escCancelsAndBackspaceDisables() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.Escape))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertNull(reported) }

        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.Backspace))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertNull(reported!!.primary(ShortcutAction.NewPage)) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).assertTextContains("Disabled")
    }

    @Test
    fun aKeyThatTypesIsRefusedWithoutAModifier() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        press(listOf(Key.ShiftLeft, Key.K))
        onNodeWithTag(ShortcutDialogTags.Capture).assertExists()
        onNodeWithTag(ShortcutDialogTags.Message, useUnmergedTree = true).assertTextContains("Add Ctrl, Alt or Super", substring = true)
        onNodeWithTag(ShortcutDialogTags.Replace).assertDoesNotExist()
        // A function key needs nothing else.
        press(listOf(Key.F6))
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        runOnIdle { assertEquals(KeyChord(ShortcutKey.F6), reported!!.primary(ShortcutAction.NewPage)) }
    }

    @Test
    fun resetAllAsksAndThenRestoresEveryDefault() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val custom = KeyBindings.Default.rebind(ShortcutAction.Undo, KeyChord(ShortcutKey.U, ctrl = true))
            .rebind(ShortcutAction.Bold, null)
        setWorkspace(bindings = custom)
        openHardware()
        onNodeWithTag(HardwareTags.ResetAll).performScrollTo().performClick()
        onNodeWithTag(ShortcutDialogTags.CancelResetAll).performClick()
        onNodeWithTag(ShortcutDialogTags.ResetAll).assertDoesNotExist()
        runOnIdle { assertNull(reported) }

        onNodeWithTag(HardwareTags.ResetAll).performClick()
        onNodeWithTag(ShortcutDialogTags.ConfirmResetAll).performClick()
        runOnIdle { assertEquals(KeyBindings.Default, reported) }
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.Bold)).performScrollTo().assertTextContains("Ctrl+B")
        onNodeWithTag(HardwareTags.ResetAll).assertIsNotEnabled()
    }

    @Test
    fun aRebindingTakesEffectInTheWorkspaceAtOnce() = runDesktopComposeUiTest(width = 1600, height = 900) {
        setWorkspace()
        openHardware()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.ZoomIn)).performScrollTo().performClick()
        press(listOf(Key.CtrlLeft, Key.K))

        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(700f, 500f)) }
        press(listOf(Key.CtrlLeft, Key.Equals))
        assertZoom(100)
        press(listOf(Key.CtrlLeft, Key.K))
        assertZoom(125)
    }

    @Test
    fun textBoxShortcutsFollowTheirBindings() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val clipboard = FakeClipboard()
        val ctrlShiftC = KeyChord(ShortcutKey.C, ctrl = true, shift = true)
        var observed = WorkspaceState.demo()
        setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document),
            bindings = KeyBindings.Default.rebind(ShortcutAction.Copy, ctrlShiftC),
            clipboard = clipboard) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
        val selected = runOnIdle { observed.selectedText }
        assertTrue(selected.isNotEmpty())

        // Copy moved off Ctrl+C, and the text field's own copy must not take the key back.
        press(listOf(Key.CtrlLeft, Key.C))
        runOnIdle { assertNull(clipboard.value) }
        press(listOf(Key.CtrlLeft, Key.ShiftLeft, Key.C))
        runOnIdle { assertEquals(selected, clipboard.value?.text) }

        press(listOf(Key.CtrlLeft, Key.B))
        runOnIdle { assertTrue(Mark.Bold in observed.richText!!.select(TextSelection(0, 6)).activeMarks) }

        // The right-click menu names the keys in force.
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(15f, 10f)) }
        waitForIdle()
        assertTrue(onAllNodes(hasText("Ctrl+Shift+C") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty())
    }

    private fun ComposeUiTest.assertZoom(percent: Int) {
        onNodeWithTag(WorkspaceTestTags.ZoomIndicator)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Canvas zoom $percent percent"))
    }

    private fun ComposeUiTest.openHardware() {
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.Shortcuts).assertExists()
    }

    /** Holds [keys] down in order and lets them go in reverse, as fingers do. */
    private fun ComposeUiTest.press(keys: List<Key>) {
        onRoot().performKeyInput {
            keys.forEach { keyDown(it) }
            keys.reversed().forEach { keyUp(it) }
        }
    }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo().copy(activeTab = RibbonTab.Settings),
        bindings: KeyBindings = KeyBindings.Default,
        clipboard: ClipboardManager? = null,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            val content = @androidx.compose.runtime.Composable {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                    keyBindings = bindings, onKeyBindingsChange = { reported = it })
            }
            ViveNotesTheme(darkTheme = true) {
                if (clipboard == null) content()
                else CompositionLocalProvider(LocalClipboardManager provides clipboard) { content() }
            }
        }
    }

    private class FakeClipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
