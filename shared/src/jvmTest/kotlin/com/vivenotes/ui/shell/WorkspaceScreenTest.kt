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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.model.Mark
import com.vivenotes.model.BlockType
import com.vivenotes.model.Outline
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
            onNodeWithTag(DocumentRibbonTags.Text).assertIsEnabled()
            onNodeWithTag(DocumentRibbonTags.Equation).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Link).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Picture).assertIsNotEnabled()
        }

    @Test
    fun textButtonTogglesAndBareCanvasTapCreatesOnlyWhileArmed() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            setWorkspace(initial = observed) { observed = it }
            val initial = observed.selectedPage!!.document.outlines.size

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
            runOnIdle { assertEquals(initial, observed.selectedPage!!.document.outlines.size) }
            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            onNodeWithTag(DocumentRibbonTags.Text).assertIsSelected()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
            runOnIdle { assertEquals(initial + 1, observed.selectedPage!!.document.outlines.size) }
            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
            runOnIdle { assertEquals(initial + 1, observed.selectedPage!!.document.outlines.size) }
        }

    @Test
    fun focusedNonEmptyTextBoxShowsCopySelectAllDeleteToolkit() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val id = initial.focusedTextOutline!!.id
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            onNodeWithTag(WorkspaceTestTags.TextBoxCopy).assertExists()
            onNodeWithTag(WorkspaceTestTags.ObjectColor).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.TextBoxSelectAll).performClick()
            runOnIdle { assertEquals(observed.richText!!.text.length, observed.editorSelection.max) }
            onNodeWithTag(WorkspaceTestTags.TextBoxCopy).performClick()
            runOnIdle { assertEquals(id, observed.canvasClipboard.texts.single().id) }
            onNodeWithTag(WorkspaceTestTags.TextBoxDelete).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.document.outlines.none { it.id == id }) }
        }

    @Test
    fun emptyTextBoxHidesToolkitUntilTextIsEntered() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)
                .copy(activeTab = RibbonTab.Document)
            val id = initial.focusedTextOutline!!.id
            setWorkspace(initial = initial)

            onNodeWithTag(WorkspaceTestTags.TextBoxCopy).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.textGrip(id)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.textBox(id) + "-editor")
                .performTextReplacement("A new note")
            onNodeWithTag(WorkspaceTestTags.TextBoxCopy).assertExists()
            onNodeWithTag(WorkspaceTestTags.textGrip(id)).assertExists()
        }

    @Test
    fun doubleClickOnEmptyCanvasOffersSharedObjectPaste() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val initial = base.copyTextBox(base.focusedTextOutline!!.id)
            var observed = initial
            setWorkspace(initial = initial) { observed = it }
            val before = initial.selectedPage!!.document.outlines.size

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                doubleClick(Offset(780f, 500f))
            }
            onNodeWithTag(WorkspaceTestTags.CanvasPaste).performClick()
            runOnIdle { assertEquals(before + 1, observed.selectedPage!!.document.outlines.size) }
        }

    @Test
    fun primeObjectToolkitLocksCopiesAndDeletesTheSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val pageId = base.selectedPageId
            val shape = Outline.Shape(id = "ui-shape", x = 300f, y = 350f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == pageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + shape)) else page
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
            onNodeWithContentDescription("Red").performClick()
            runOnIdle { assertEquals(0xFFEF4444.toInt(),
                (observed.selectedPage!!.document.outlines.first { it.id == shape.id } as Outline.Shape).borderArgb) }
            onNodeWithTag(WorkspaceTestTags.ObjectLock).performClick()
            runOnIdle { assertTrue(observed.selectedObjectsLocked) }
            onNodeWithTag(WorkspaceTestTags.StructuralUndo).performClick()
            runOnIdle { assertEquals(null, observed.selectedPage!!.document.outlines
                .first { it.id == shape.id }.lockGroup) }
            onNodeWithTag(WorkspaceTestTags.StructuralRedo).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.document.outlines
                .first { it.id == shape.id }.lockGroup != null) }
            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectCopy).performClick()
            runOnIdle { assertEquals(shape.id, observed.canvasClipboard.objects.single().id) }
            onNodeWithTag(WorkspaceTestTags.ObjectDelete).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.document.outlines.none { it.id == shape.id }) }
        }

    @Test
    fun drawLassoSelectsPrimeObjectInCanvasRectangle() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
            val id = base.selectedPageId
            val shape = Outline.Shape(id = "lasso-shape", x = 300f, y = 350f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == id) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + shape)) else page
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.ObjectLasso).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
                swipe(start = Offset(250f, 320f), end = Offset(450f, 450f))
            }
            runOnIdle { assertEquals(setOf(shape.id), observed.selectedObjectIds) }
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
