package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.vivenotes.localization.Strings
import com.vivenotes.model.Mark
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.EditorDefaults
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

    // Android 291eafc: the marker toggles its ink against what the text already carries, the
    // palette's ringed swatch takes the highlight off, and the ink picked is the device's.

    @Test
    fun theMarkerAppliesItsInkToUnhighlightedText() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        val saved = mutableListOf<EditorDefaults>()
        setWorkspace(InterfaceSettings(), highlighted(null, ink = pink), saved::add) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsNotSelected()
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }

        onNodeWithTag("${DocumentRibbonTags.Highlight}-none").assertDoesNotExist()
        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsSelected()
        runOnIdle {
            assertEquals(listOf(Mark.Highlight(pink)), highlights(observed, 0, 6))
            assertTrue(saved.isEmpty(), "the marker does not change its own ink")
        }
    }

    @Test
    fun theMarkerClearsItsOwnInk() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings(), highlighted(pink, ink = pink)) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsSelected()
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }

        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsNotSelected()
        runOnIdle {
            assertTrue(highlights(observed, 0, 6).isEmpty())
            assertEquals(TextSelection(0, 6), observed.editorSelection)
        }
    }

    @Test
    fun theMarkerSwapsAnotherColourForItsInk() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        val saved = mutableListOf<EditorDefaults>()
        setWorkspace(InterfaceSettings(), highlighted(yellow, ink = pink), saved::add) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsNotSelected()
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }

        runOnIdle {
            assertEquals(listOf(Mark.Highlight(pink)), highlights(observed, 0, 6))
            assertEquals(pink, observed.editorDefaults.highlight)
            assertTrue(saved.isEmpty(), "the marker does not change its own ink")
        }
    }

    /** A selection only partly highlighted counts as not having it, so the press covers all of it. */
    @Test
    fun theMarkerCoversAPartlyHighlightedSelection() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings(), highlighted(pink, ink = pink, end = 3)) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Highlight).assertIsNotSelected()
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }

        runOnIdle { assertEquals(listOf(Mark.Highlight(pink)), highlights(observed, 0, 6)) }
    }

    /** Picking another ink switches the text to it and makes it the one the marker lays next. */
    @Test
    fun aPickedInkHighlightsTheTextAndBecomesTheMarkersInk() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        val saved = mutableListOf<EditorDefaults>()
        setWorkspace(InterfaceSettings(), highlighted(yellow), saved::add) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.Highlight))
            .assertContentDescriptionEquals(Strings.highlight_colour)
            .performMouseInput { click(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$yellow").assertIsSelected()
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$green").assertIsNotSelected()
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$green").performMouseInput { click(center) }

        onNodeWithTag("${DocumentRibbonTags.Highlight}-none").assertDoesNotExist()
        runOnIdle {
            assertEquals(listOf(Mark.Highlight(green)), highlights(observed, 0, 6))
            assertEquals(listOf(green), saved.map { it.highlight })
            assertEquals(green, observed.editorDefaults.highlight)
        }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(7, 12))
        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { click(center) }
        runOnIdle { assertEquals(listOf(Mark.Highlight(green)), highlights(observed, 7, 12)) }
    }

    /** The ringed swatch is the text's own colour, so picking it again takes it off. */
    @Test
    fun theRingedInkTakesTheHighlightOff() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace(InterfaceSettings(), highlighted(green)) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.Highlight).performMouseInput { rightClick(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$green").assertIsSelected()
        onNodeWithTag("${DocumentRibbonTags.Highlight}-$green").performMouseInput { click(center) }

        runOnIdle { assertTrue(highlights(observed, 0, 6).isEmpty()) }
    }

    @Test
    fun noneTakesTheHighlightOffAndKeepsTheInk() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        val saved = mutableListOf<EditorDefaults>()
        setWorkspace(InterfaceSettings(), highlighted(green, ink = pink), saved::add) { observed = it }
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

        onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.Highlight)).performMouseInput { click(center) }
        onNodeWithTag("${DocumentRibbonTags.Highlight}-none").performMouseInput { click(center) }

        runOnIdle {
            assertTrue(highlights(observed, 0, 6).isEmpty())
            assertEquals(pink, observed.editorDefaults.highlight)
            assertTrue(saved.isEmpty())
        }
    }

    /** The palette and the ink kept with the defaults offer the same colours. */
    @Test
    fun everyOfferedHighlightIsAnInkTheDefaultsKeep() {
        assertEquals(EditorDefaults.SUPPORTED_HIGHLIGHTS, HighlightColors.map { it.first }.toSet())
        assertEquals(EditorDefaults.DEFAULT_HIGHLIGHT, HighlightColors.first().first)
    }

    private val yellow = 0x66FFEB3B
    private val green = 0x6676FF03
    private val pink = 0x66FF4081

    /** The demo page, its body's first characters up to [end] highlighted in [argb], and the marker's [ink]. */
    private fun highlighted(argb: Int?, ink: Int = EditorDefaults.DEFAULT_HIGHLIGHT, end: Int = 6): WorkspaceState {
        val demo = WorkspaceState.demo()
        val marked = if (argb == null) demo else demo.focusTextBox(demo.bodyTextOutline!!.id)
            .selectText(TextSelection(0, end)).setSelectedMark(Mark.Highlight(argb)).clearCanvasFocus()
        return marked.setEditorDefaults(marked.editorDefaults.copy(highlight = ink))
    }

    private fun highlights(state: WorkspaceState, start: Int, end: Int): List<Mark> =
        state.richText!!.select(TextSelection(start, end)).activeMarks.filterIsInstance<Mark.Highlight>()

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
        initial: WorkspaceState = WorkspaceState.demo(),
        onDefaults: (EditorDefaults) -> Unit = {},
        onChange: (WorkspaceState) -> Unit,
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state, onStateChange = { state = it(state); onChange(state) }, interfaceSettings = settings,
                    onEditorDefaultsChange = onDefaults)
            }
        }
    }
}
