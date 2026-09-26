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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.model.Mark
import com.vivenotes.model.BlockType
import com.vivenotes.model.Outline
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.focusBody
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
    fun theDocumentTabIsOpenWhenTheWorkspaceOpens() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace()

        onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Document)).assertIsSelected()
        onNodeWithTag(DocumentRibbonTags.Paste).assertIsDisplayed()
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
                    .focusBody().selectText(TextSelection(0, 6)),
            ) { observed = it }

            onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).performClick()
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).assertIsSelected()
            runOnIdle { assertEquals(setOf(Mark.Bold), observed.richText?.blocks?.first()?.runs?.first()?.marks) }

            onNodeWithTag(DocumentRibbonTags.ClearFormatting).performClick()
            runOnIdle { assertEquals(emptySet(), observed.richText?.blocks?.first()?.runs?.first()?.marks) }
        }

    @Test
    fun selectedRibbonButtonMatchesHoverFootprint() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
                .focusBody().selectText(TextSelection(0, 6)))
            val button = onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold))
            val idle = button.captureToImage().toPixelMap()
            button.performMouseInput { moveTo(Offset(20f, 20f)) }
            mainClock.advanceTimeBy(300)
            val hover = button.captureToImage().toPixelMap()
            button.performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(780f, 500f))
            }
            mainClock.advanceTimeBy(300)
            val selected = button.captureToImage().toPixelMap()
            for (y in listOf(4, 6, 8, 10)) {
                val hoverRange = (0 until idle.width).filter { idle[it, y] != hover[it, y] }
                val selectedRange = (0 until idle.width).filter { idle[it, y] != selected[it, y] }
                assertTrue(hoverRange.isNotEmpty())
                assertEquals(hoverRange, selectedRange, "Selected highlight width differs from hover at y=$y")
            }
        }

    @Test
    fun clickingFormattingButtonsKeepsASelectionMadeInTheEditor() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
            runOnIdle { assertEquals(TextSelection(0, 6), observed.editorSelection) }
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
            runOnIdle {
                assertEquals(TextSelection(0, 6), observed.editorSelection)
                assertTrue(Mark.Bold in observed.richText!!.blocks.first().runs.first().marks)
            }
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Italic)).performClick()
            runOnIdle {
                assertEquals(TextSelection(0, 6), observed.editorSelection)
                assertTrue(Mark.Italic in observed.richText!!.blocks.first().runs.first().marks)
            }
        }

    @Test
    fun pickerFormattingAndClearUseTheLiveEditorSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
            onNodeWithTag(DocumentRibbonTags.FontSize).performClick()
            onNodeWithTag("${DocumentRibbonTags.FontSize}-24").performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
            runOnIdle {
                assertEquals(TextSelection(0, 6), observed.editorSelection)
                assertTrue(Mark.FontSize(24) in observed.richText!!.blocks.first().runs.first().marks)
            }
            onNodeWithTag(DocumentRibbonTags.FontColor).performClick()
            val red = 0xFFE53935.toInt()
            onNodeWithTag("${DocumentRibbonTags.FontColor}-$red").performClick()
            runOnIdle {
                assertEquals(TextSelection(0, 6), observed.editorSelection)
                assertTrue(Mark.TextColor(red) in observed.richText!!.blocks.first().runs.first().marks)
            }
            onNodeWithTag(DocumentRibbonTags.ClearFormatting).performClick()
            runOnIdle {
                assertEquals(TextSelection(0, 6), observed.editorSelection)
                assertTrue(observed.richText!!.blocks.first().runs.first().marks.isEmpty())
            }
        }

    @Test
    fun remainingFormattingControlsApplyToLiveEditorSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            var observed = initial
            setWorkspace(initial = initial) { observed = it }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))

            onNodeWithTag(DocumentRibbonTags.mark(Mark.Underline)).performClick()
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Strikethrough)).performClick()
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Subscript)).performClick()
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Superscript)).performClick()
            runOnIdle {
                val marks = observed.richText!!.blocks.first().runs.first().marks
                assertTrue(Mark.Underline in marks)
                assertTrue(Mark.Strikethrough in marks)
                assertTrue(Mark.Superscript in marks)
                assertFalse(Mark.Subscript in marks)
                assertEquals(TextSelection(0, 6), observed.editorSelection)
            }

            onNodeWithTag(DocumentRibbonTags.Highlight).performClick()
            val yellow = 0x66FFEB3B
            onNodeWithTag("${DocumentRibbonTags.Highlight}-$yellow").performClick()
            runOnIdle { assertTrue(Mark.Highlight(yellow) in
                observed.richText!!.blocks.first().runs.first().marks) }

            onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Bullet)).performScrollTo().performClick()
            onNodeWithTag(DocumentRibbonTags.IncreaseIndent).performScrollTo().performClick()
            runOnIdle {
                assertEquals(BlockType.Bullet, observed.richText!!.currentBlock.type)
                assertEquals(1, observed.richText!!.currentBlock.indent)
            }
            onNodeWithTag(DocumentRibbonTags.Styles).performScrollTo().performClick()
            onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Heading1)).performClick()
            runOnIdle {
                assertEquals(BlockType.Heading1, observed.richText!!.currentBlock.type)
                assertEquals(TextSelection(0, 6), observed.editorSelection)
            }
        }

    @Test
    fun documentListButtonUpdatesTheParagraphType() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)) {
                observed = it
            }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()

            onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Bullet))
                .performScrollTo().performClick()

            runOnIdle { assertEquals(BlockType.Bullet, observed.richText?.currentBlock?.type) }
        }

    @Test
    fun documentRibbonIncludesTheAndroidControlsAndDisablesOnlyWhatCannotRun() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document))

            listOf(
                DocumentRibbonTags.Text, DocumentRibbonTags.Paste,
                DocumentRibbonTags.Cut, DocumentRibbonTags.Copy,
                DocumentRibbonTags.FontFamily, DocumentRibbonTags.FontSize,
                DocumentRibbonTags.FontColor, DocumentRibbonTags.Highlight,
                DocumentRibbonTags.ClearFormatting, DocumentRibbonTags.Styles,
                DocumentRibbonTags.Equation, DocumentRibbonTags.Link, DocumentRibbonTags.Picture,
            ).forEach { onNodeWithTag(it).assertExists() }
            onNodeWithTag(DocumentRibbonTags.Text).assertIsEnabled()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            onNodeWithTag(DocumentRibbonTags.Link).assertIsEnabled()
            // No LaTeX renderer on desktop yet, and this screen was given no picture storage.
            onNodeWithTag(DocumentRibbonTags.Equation).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Picture).assertIsNotEnabled()
        }

    /** Regression: formatting ran against the page's first text box when no box was being edited. */
    @Test
    fun textCommandsAreDisabledUntilATextBoxIsBeingEdited() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            var observed = initial
            setWorkspace(initial = initial) { observed = it }
            val textCommands = listOf(
                DocumentRibbonTags.mark(Mark.Bold), DocumentRibbonTags.blockType(BlockType.Bullet),
                DocumentRibbonTags.IncreaseIndent, DocumentRibbonTags.ClearFormatting,
                DocumentRibbonTags.Paste, DocumentRibbonTags.FontColor, DocumentRibbonTags.Link,
            )

            textCommands.forEach { onNodeWithTag(it).assertIsNotEnabled() }
            onNodeWithTag(DocumentRibbonTags.blockType(BlockType.Bullet)).performClick()
            runOnIdle { assertEquals(initial.selectedPage!!.document, observed.selectedPage!!.document) }
            onNodeWithTag(DocumentRibbonTags.Text).assertIsEnabled()

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            textCommands.forEach { onNodeWithTag(it).assertIsEnabled() }

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 600f)) }
            textCommands.forEach { onNodeWithTag(it).assertIsNotEnabled() }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            onNodeWithTag(WorkspaceTestTags.TitleEditor).performClick()
            onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold)).assertIsNotEnabled()
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
            runOnIdle { assertEquals(initial, observed.selectedPage!!.document.outlines.size) }
        }

    @Test
    fun textPlacementReplacesEmptyBoxAndEscapeDiscardsTheLastEmptyBox() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            setWorkspace(initial = observed) { observed = it }
            val originalCount = observed.selectedPage!!.document.outlines.size

            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                click(Offset(780f, 450f))
            }
            val firstId = observed.focusedTextOutlineId
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                click(Offset(100f, 620f))
            }
            runOnIdle {
                assertEquals(originalCount + 1, observed.selectedPage!!.document.outlines.size)
                assertTrue(observed.selectedPage!!.document.outlines.none { it.id == firstId })
            }
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performKeyInput {
                keyDown(Key.Escape)
                keyUp(Key.Escape)
            }
            runOnIdle {
                assertEquals(originalCount, observed.selectedPage!!.document.outlines.size)
                assertFalse(observed.textToolArmed)
            }
        }

    @Test
    fun typingIntoNewTextBoxKeepsItWhenTextToolIsDismissed() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            setWorkspace(initial = observed) { observed = it }

            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                click(Offset(780f, 450f))
            }
            val id = observed.focusedTextOutlineId!!
            onNodeWithTag(WorkspaceTestTags.textBox(id) + "-editor").assertIsFocused()
                .performTextReplacement("written")
            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.any { it.id == id })
                assertEquals("written", observed.selectedPage!!.document.outlines
                    .filterIsInstance<Outline.Text>().first { it.id == id }.blocks.first().text)
            }
        }

    @Test
    fun focusedNonEmptyTextBoxShowsCopySelectAllDeleteToolkit() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val id = initial.bodyTextOutline!!.id
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
    fun textBoxOutlineAppearsOnlyWhileSelected() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val id = initial.bodyTextOutline!!.id
            setWorkspace(initial = initial)

            onNodeWithTag(WorkspaceTestTags.textBoxOutline(id)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            onNodeWithTag(WorkspaceTestTags.textBoxOutline(id)).assertExists()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                click(Offset(780f, 500f))
            }
            onNodeWithTag(WorkspaceTestTags.textBoxOutline(id)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertExists()
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
            val initial = base.copyTextBox(base.bodyTextOutline!!.id)
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

    /**
     * Regression: a toolkit wider than its object puts its later buttons over bare page, and the
     * page took their clicks as taps on itself too. Whichever handled the click first, the object
     * lost its selection — before the button acted on it, or right after.
     */
    @Test
    fun toolkitButtonsPastANarrowObjectActWithoutClearingTheSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val narrow = Outline.Shape(id = "narrow", x = 300f, y = 350f, width = 40f, height = 40f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id != base.selectedPageId) page else page.copy(document = page.document.copy(
                            outlines = page.document.outlines + narrow))
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.primeObject(narrow.id)).performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectCopy).performClick()

            runOnIdle {
                assertEquals(listOf(narrow.id), observed.canvasClipboard.objects.map { it.id })
                assertEquals(setOf(narrow.id), observed.selectedObjectIds)
            }
            onNodeWithTag(WorkspaceTestTags.ObjectDelete).performClick()
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.none { it.id == narrow.id })
            }
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

            onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performTouchInput {
                swipe(start = Offset(250f, 320f), end = Offset(450f, 450f))
            }
            runOnIdle { assertEquals(setOf(shape.id), observed.selectedObjectIds) }
            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performMouseInput {
                moveTo(Offset(40f, 35f))
                press()
                moveTo(Offset(90f, 65f))
                release()
            }
            runOnIdle { assertTrue(observed.selectedPage!!.document.outlines
                .filterIsInstance<Outline.Shape>().first { it.id == shape.id }.x > shape.x) }
        }

    @Test
    fun mouseLassoSelectsAndCanDeleteWrittenTextBoxes() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val first = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)
                .editSelectedText("first", TextSelection(5))
            val firstId = first.focusedTextOutlineId!!
            val second = first.createTextBox(500f, 420f)
                .editSelectedText("second", TextSelection(6))
            val secondId = second.focusedTextOutlineId!!
            var observed = second.copy(activeTab = RibbonTab.Draw).toggleObjectLasso()
            setWorkspace(initial = observed) { observed = it }

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(250f, 320f))
                press()
                moveTo(Offset(700f, 600f))
                release()
            }
            runOnIdle { assertEquals(setOf(firstId, secondId), observed.selectedTextOutlineIds) }
            onNodeWithTag(WorkspaceTestTags.textBoxOutline(firstId)).assertExists()
            onNodeWithTag(WorkspaceTestTags.textBoxOutline(secondId)).assertExists()

            onNodeWithTag(DrawRibbonTags.PointerTool).performClick()
            onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(350f, 390f))
                press()
                moveTo(Offset(700f, 600f))
                release()
            }
            runOnIdle { assertEquals(setOf(firstId, secondId), observed.selectedTextOutlineIds) }
            onNodeWithTag(WorkspaceTestTags.textGrip(firstId)).performMouseInput {
                moveTo(Offset(40f, 12f))
                press()
                moveTo(Offset(90f, 42f))
                release()
            }
            runOnIdle {
                val moved = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
                assertTrue(moved.first { it.id == firstId }.x > 300f)
                assertTrue(moved.first { it.id == secondId }.x > 500f)
            }
            onNodeWithTag(WorkspaceTestTags.ObjectDelete).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.document.outlines.none {
                it.id == firstId || it.id == secondId
            }) }
        }

    @Test
    fun pointerModeDragsSelectionBoxOverObjectsAndSelectButtonClearsIt() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
            val shape = Outline.Shape(id = "pointer-shape", x = 300f, y = 350f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(
                            document = page.document.copy(outlines = page.document.outlines + shape))
                        else page
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(DrawRibbonTags.PointerTool).assertIsSelected()

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(250f, 320f))
                press()
                moveTo(Offset(450f, 450f))
                release()
            }
            runOnIdle { assertEquals(setOf(shape.id), observed.selectedObjectIds) }
            onNodeWithTag(DrawRibbonTags.PointerTool).performClick()
            runOnIdle { assertTrue(observed.selectedObjectIds.isEmpty()) }
        }

    @Test
    fun draggingUnselectedObjectSelectsAndMovesIt() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val shape = Outline.Shape(id = "drag-shape", x = 300f, y = 350f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(
                            document = page.document.copy(outlines = page.document.outlines + shape))
                        else page
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performMouseInput {
                moveTo(Offset(40f, 35f))
                press()
                moveTo(Offset(100f, 65f))
                release()
            }
            runOnIdle {
                assertEquals(setOf(shape.id), observed.selectedObjectIds)
                assertTrue(observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>()
                    .first { it.id == shape.id }.x > shape.x)
            }
        }

    @Test
    fun escapeReturnsTextAndLassoToolsToPointerMode() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            setWorkspace(initial = observed) { observed = it }

            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performKeyInput {
                keyDown(Key.Escape)
                keyUp(Key.Escape)
            }
            runOnIdle { assertFalse(observed.textToolArmed) }
            onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.Draw)).performClick()
            onNodeWithTag(DrawRibbonTags.ObjectLasso).performClick()
            onNodeWithTag(DrawRibbonTags.ObjectLasso).assertIsSelected()
            onNodeWithTag(DrawRibbonTags.ObjectLasso).performKeyInput {
                keyDown(Key.Escape)
                keyUp(Key.Escape)
            }
            runOnIdle { assertFalse(observed.objectLassoArmed) }
            onNodeWithTag(DrawRibbonTags.PointerTool).assertIsSelected()
        }

    @Test
    fun ctrlWheelZoomsCanvasAndUpdatesCornerIndicator() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace()
            onNodeWithTag(WorkspaceTestTags.ZoomIndicator).assertExists()
            onNodeWithText("100%").assertExists()
            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(500f, 400f))
                scroll(-1f)
            }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            onNodeWithText("110%").assertExists()
        }

    @Test
    fun canvasPlacementUsesPageCoordinatesAfterCursorAnchoredZoom() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            setWorkspace(initial = observed) { observed = it }
            onNodeWithTag(DocumentRibbonTags.Text).performClick()
            // Off the button, so its tooltip closes and the window is the only root again.
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(Offset(780f, 500f)) }
            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(780f, 500f))
                scroll(-1f)
            }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                click(Offset(780f, 500f))
            }
            runOnIdle {
                val added = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
                    .last()
                assertEquals(780f, added.x, 1f)
                assertEquals(500f, added.y, 1f)
            }
        }

    @Test
    fun fontSizeAndColourPickersWriteAndroidMarks() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo()
            setWorkspace(
                initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
                    .focusBody().selectText(TextSelection(0, 6)),
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
                    .focusBody().selectText(TextSelection(0, 6)),
                clipboard = clipboard,
            ) { observed = it }

            onNodeWithTag(DocumentRibbonTags.Copy).performClick()
            runOnIdle { assertEquals("Review", clipboard.value?.text) }
            onNodeWithTag(DocumentRibbonTags.Cut).performClick()
            runOnIdle { assertFalse(observed.selectedPage!!.body.startsWith("Review")) }
            onNodeWithTag(DocumentRibbonTags.Cut).assertIsNotEnabled()
            onNodeWithTag(DocumentRibbonTags.Paste).performClick()
            runOnIdle { assertTrue(observed.selectedPage!!.body.startsWith("Review")) }
        }

    @Test
    fun copyAndCutUseASelectionMadeInTheEditor() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val clipboard = FakeClipboard()
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            var observed = initial
            setWorkspace(initial = initial, clipboard = clipboard) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 6))
            runOnIdle { assertEquals(TextSelection(0, 6), observed.editorSelection) }
            onNodeWithTag(DocumentRibbonTags.Copy).performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertIsFocused()
            runOnIdle { assertEquals("Review", clipboard.value?.text) }
            onNodeWithTag(DocumentRibbonTags.Cut).performClick()
            runOnIdle { assertFalse(observed.selectedPage!!.body.startsWith("Review")) }
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
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
                } else {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) })
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
