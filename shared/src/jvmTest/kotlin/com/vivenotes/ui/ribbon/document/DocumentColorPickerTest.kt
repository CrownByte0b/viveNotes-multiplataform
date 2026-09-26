package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.RibbonTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The colour menus must accept real pointer clicks with the editor's range still selected. */
@OptIn(ExperimentalTestApi::class)
class DocumentColorPickerTest {
    @Test
    fun mainButtonsApplyDefaultsWithoutOpeningPalettes() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings()) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }
        onNodeWithTag("${DocumentRibbonTags.FontColor}-none").assertDoesNotExist()
        onNodeWithTag("${DocumentRibbonTags.Highlight}-none").assertDoesNotExist()
        runOnIdle {
            val marks = observed.richText!!.select(TextSelection(0, 6)).activeMarks
            assertTrue(Mark.TextColor(0xFFE53935.toInt()) in marks)
            assertTrue(Mark.Highlight(0x66FFEB3B) in marks)
        }
    }

    @Test
    fun paletteChoiceIsReusedByMainButtons() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings()) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
        val green = 0xFF43A047.toInt()
        val blue = 0x6640C4FF

        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.FontColor}-$green").performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.Highlight)).performMouseInput { click(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$blue").performMouseInput { click(center) }

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.File)).performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(7, 12))
        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }
        runOnIdle {
            val marks = observed.richText!!.select(TextSelection(7, 12)).activeMarks
            assertTrue(Mark.TextColor(green) in marks)
            assertTrue(Mark.Highlight(blue) in marks)
        }
    }

    @Test
    fun fontColourAndHighlightWorkAtDefaultDpi() = checkPickers(InterfaceSettings())

    @Test
    fun fontColourAndHighlightWorkAtMinimumDpi() = checkPickers(
        InterfaceSettings(displayScale = 0.5f, uiScale = 0.5f, fontScale = 0.5f))

    @Test
    fun fontColourAndHighlightWorkAtEnlargedDpi() = checkPickers(
        InterfaceSettings(displayScale = 1.5f, uiScale = 1.5f, fontScale = 1.8f))

    @Test
    fun fontColourAndHighlightWorkAtMaximumDisplayDpi() = checkPickers(
        InterfaceSettings(displayScale = 2.5f))

    @Test
    fun coloursPickedAtCaretApplyToNewlyTypedText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings()) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(6))
        val red = 0xFFE53935.toInt()
        val yellow = 0x66FFEB3B
        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.FontColor}-$red").performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$yellow").performMouseInput { click(center) }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInput("X")
        runOnIdle {
            val inserted = observed.richText!!.blocks.first().runs.first { "X" in it.text }
            assertTrue(Mark.TextColor(red) in inserted.marks)
            assertTrue(Mark.Highlight(yellow) in inserted.marks)
        }
    }

    private fun checkPickers(settings: InterfaceSettings) = runDesktopComposeUiTest(width = 3200, height = 1400) {
        var observed = WorkspaceState.demo()
        setWorkspace(settings) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        val red = 0xFFE53935.toInt()
        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.FontColor}-$red").performMouseInput { click(center) }
        runOnIdle {
            assertEquals(com.vivenotes.richtext.TextSelection(0, 6), observed.editorSelection)
            assertTrue(Mark.TextColor(red) in observed.richText!!.blocks.first().runs.first().marks)
        }

        val yellow = 0x66FFEB3B
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$yellow").performMouseInput { click(center) }
        runOnIdle {
            val marks = observed.richText!!.blocks.first().runs.first().marks
            assertTrue(Mark.TextColor(red) in marks)
            assertTrue(Mark.Highlight(yellow) in marks)
        }

        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.FontColor}-none").performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-none").performMouseInput { click(center) }
        runOnIdle {
            val marks = observed.richText!!.blocks.first().runs.first().marks
            assertFalse(marks.any { it is Mark.TextColor || it is Mark.Highlight })
        }
        onNodeWithTag(DocumentRibbonTags.FontColor).performMouseInput { click(center) }
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }
        runOnIdle {
            val marks = observed.richText!!.blocks.first().runs.first().marks
            assertTrue(Mark.TextColor(red) in marks)
            assertTrue(Mark.Highlight(yellow) in marks)
        }
    }

    private fun ComposeUiTest.setWorkspace(
        settings: InterfaceSettings,
        onChange: (WorkspaceState) -> Unit,
    ) {
        setContent {
            var state by remember { mutableStateOf(WorkspaceState.demo()) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state, onStateChange = { state = it(state); onChange(state) }, interfaceSettings = settings)
            }
        }
    }
}
