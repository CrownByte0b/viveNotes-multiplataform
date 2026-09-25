package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.model.Mark
import com.vivenotes.model.BlockType
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceScreenTest {

    @Test
    fun largeWindowShowsThreePaneWorkspace() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()

        onNodeWithTag(WorkspaceTestTags.NotebookPane).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PagePane).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Homework 1")
    }

    @Test
    fun compactWindowKeepsCanvasAndCollapsesNavigationPanes() =
        runDesktopComposeUiTest(width = 700, height = 900) {
            setWorkspace()

            onNodeWithTag(WorkspaceTestTags.NotebookPane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PagePane).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
        }

    @Test
    fun selectingSectionOpensItsFirstPage() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.section("chapter-2")).performClick()

        onNodeWithTag(WorkspaceTestTags.page("sequences")).assertIsDisplayed()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Sequences")
        runOnIdle {
            assertEquals("chapter-2", observed.selectedSectionId)
            assertEquals("sequences", observed.selectedPageId)
        }
    }

    @Test
    fun ribbonTabChangesVisibleCommands() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).performClick()

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).assertIsSelected()
        onNodeWithText("Pen").assertIsDisplayed()
        runOnIdle { assertEquals(RibbonTab.Draw, observed.activeTab) }
    }

    @Test
    fun addPageAndEditPlaceholderContent() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.AddPage).performClick()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).performTextReplacement("Project plan")
        onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextReplacement("First milestone")

        onNodeWithTag(WorkspaceTestTags.page("chapter-1-draft-3")).assertExists()
        onNodeWithTag(WorkspaceTestTags.TitleEditor).assertTextContains("Project plan")
        onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains("First milestone")
        runOnIdle {
            assertEquals("Project plan", observed.selectedPage?.title)
            assertEquals("First milestone", observed.selectedPage?.body)
            assertEquals("First milestone", observed.selectedPage?.preview)
        }
    }

    @Test
    fun navigationToggleUpdatesVisibilityState() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }

        onNodeWithTag(WorkspaceTestTags.NavigationToggle).performClick()

        runOnIdle { assertFalse(observed.navigationVisible) }
    }

    @Test
    fun documentMarkButtonFormatsSelectedTextAndClearRemovesIt() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(
                initial = WorkspaceState.demo()
                    .copy(activeTab = RibbonTab.Document)
                    .selectText(TextSelection(0, 6)),
            ) { observed = it }

            onNodeWithTag(WorkspaceTestTags.documentMark(Mark.Bold)).performClick()
            onNodeWithTag(WorkspaceTestTags.documentMark(Mark.Bold)).assertIsSelected()
            runOnIdle { assertEquals(setOf(Mark.Bold), observed.richText?.blocks?.first()?.runs?.first()?.marks) }

            onNodeWithTag(WorkspaceTestTags.ClearFormatting).performClick()
            runOnIdle { assertEquals(emptySet(), observed.richText?.blocks?.first()?.runs?.first()?.marks) }
        }

    @Test
    fun documentListButtonUpdatesTheParagraphType() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)) {
                observed = it
            }

            onNodeWithTag(WorkspaceTestTags.blockType(BlockType.Bullet))
                .performScrollTo().performClick()

            runOnIdle { assertEquals(BlockType.Bullet, observed.richText?.currentBlock?.type) }
        }

    @Test
    fun documentRibbonIncludesTheAndroidControlsAndDisablesUnportedActions() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document))

            listOf(
                DocumentRibbonTags.Text, DocumentRibbonTags.Paste,
                DocumentRibbonTags.Cut, DocumentRibbonTags.Copy,
                DocumentRibbonTags.FontFamily, DocumentRibbonTags.FontSize,
                DocumentRibbonTags.FontColor, DocumentRibbonTags.Highlight,
                WorkspaceTestTags.ClearFormatting, WorkspaceTestTags.Styles,
                DocumentRibbonTags.Equation, DocumentRibbonTags.Link, DocumentRibbonTags.Picture,
            ).forEach { onNodeWithTag(it).assertExists() }
            onNodeWithTag(DocumentRibbonTags.Text).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Equation).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Link).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Picture).assertIsNotEnabled()
        }

    @Test
    fun fontSizeAndColourPickersWriteAndroidMarks() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(
                initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
                    .selectText(TextSelection(0, 6)),
            ) { observed = it }

            onNodeWithTag(DocumentRibbonTags.FontSize).performClick()
            onNodeWithTag("${DocumentRibbonTags.FontSize}-24").performClick()
            runOnIdle { assertTrue(Mark.FontSize(24) in observed.richText!!.blocks.first().runs.first().marks) }

            onNodeWithTag(DocumentRibbonTags.FontColor).performClick()
            val red = 0xFFE53935.toInt()
            onNodeWithTag("${DocumentRibbonTags.FontColor}-$red").performClick()
            runOnIdle { assertTrue(Mark.TextColor(red) in observed.richText!!.blocks.first().runs.first().marks) }
        }

    @Test
    fun documentClipboardButtonsCopyCutAndPasteSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val clipboard = FakeClipboard()
            var observed = WorkspaceState.demo()
            setWorkspace(
                initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
                    .selectText(TextSelection(0, 6)),
                clipboard = clipboard,
            ) { observed = it }

            onNodeWithTag(DocumentRibbonTags.Copy).performClick()
            runOnIdle { assertEquals("Review", clipboard.value?.text) }
            onNodeWithTag(DocumentRibbonTags.Cut).performClick()
            runOnIdle { assertFalse(observed.selectedPage!!.body.startsWith("Review")) }
            onNodeWithTag(DocumentRibbonTags.Paste).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.body.startsWith("Review")) }
        }

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo(),
        clipboard: ClipboardManager? = null,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                if (clipboard == null) {
                    WorkspaceScreen(state = state, onStateChange = { state = it; onStateChange(it) })
                } else {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        WorkspaceScreen(state = state, onStateChange = { state = it; onStateChange(it) })
                    }
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
