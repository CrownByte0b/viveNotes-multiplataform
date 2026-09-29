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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.ribbon.document.DocumentRibbonTags
import com.vivenotes.ui.ribbon.draw.DrawRibbonTags
import com.vivenotes.ui.ribbon.draw.ShapeMenuTags
import com.vivenotes.ui.ribbon.draw.ShapeObjectTags
import com.vivenotes.ui.canvas.TextMenuTags
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.ui.account.AccountService
import com.vivenotes.ui.account.AccountSession
import com.vivenotes.ui.account.AccountTags
import com.vivenotes.ui.account.AccountSubscription
import com.vivenotes.ui.account.AccountRequestException
import com.vivenotes.ui.account.GoogleSignInOutcome
import com.vivenotes.ui.account.AccountProvider
import kotlinx.coroutines.flow.MutableStateFlow
import com.vivenotes.model.Mark
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ends
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
    fun shapePickerCreatesStyledSolidAndExposesPrimeObjectControls() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
            setWorkspace(initial = observed) { observed = it }
            onNodeWithTag(ShapeMenuTags.ShapeTool).performClick()
            onNodeWithTag(ShapeMenuTags.ShapeTool).assertIsSelected().performClick()
            onNodeWithTag("draw-shape-page-1").performClick()
            onNodeWithTag(ShapeMenuTags.kind(ShapeKind.Cube)).performClick()
            onNodeWithTag(ShapeMenuTags.lineType(LineType.Dashed)).performClick()
            onNodeWithTag(ShapeMenuTags.fill(0xFFE01B24.toInt())).performClick()
            onNodeWithTag("draw-shape-custom-fill").performScrollTo().performTextReplacement("#123456")
            onNodeWithTag("draw-shape-custom-fill-apply").performClick()
            onNodeWithTag(ShapeMenuTags.ShapeTool).performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(450f, 400f))
                press()
                moveTo(Offset(560f, 500f))
                release()
            }
            runOnIdle {
                val shape = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().last()
                assertEquals(ShapeKind.Cube, shape.kind)
                assertEquals(LineType.Dashed, shape.lineType)
                assertEquals(0xFF123456.toInt(), shape.fillArgb)
                assertTrue(shape.segments.any { it.hidden })
                assertEquals(setOf(shape.id), observed.selectedObjectIds)
            }
            onNodeWithTag(ShapeObjectTags.LineType).assertIsDisplayed()
            onNodeWithTag(ShapeObjectTags.Width).assertIsDisplayed()
            onNodeWithTag(ShapeObjectTags.Fill).assertIsDisplayed()
            onNodeWithTag(ShapeObjectTags.LineType).performClick()
            onNodeWithText("Dotted").performClick()
            onNodeWithTag(ShapeObjectTags.Width).performClick()
            onNodeWithText("9 pt").performClick()
            onNodeWithTag(ShapeObjectTags.Fill).performClick()
            onNodeWithText("No fill").performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectColor).performClick()
            onNodeWithTag("object-shape-custom-border").performTextReplacement("#345678")
            onNodeWithTag("object-shape-custom-border-apply").performClick()
            runOnIdle {
                val shape = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().last()
                assertEquals(LineType.Dotted, shape.lineType)
                assertEquals(9f, shape.borderWidth)
                assertEquals(null, shape.fillArgb)
                assertEquals(0xFF345678.toInt(), shape.borderArgb)
            }
            onNodeWithTag(WorkspaceTestTags.ObjectCopy).performClick()
            runOnIdle { assertEquals(1, observed.canvasClipboard.objects.size) }
        }

    @Test
    fun selectedLineEndCanBeDraggedInBothAxes() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var observed = WorkspaceState.demo().setShapeSettings(
                com.vivenotes.workspace.ShapeToolSettings(kind = ShapeKind.Line))
                .toggleShapeTool().createShape(300f, 350f, 450f, 400f)
                .copy(activeTab = RibbonTab.Draw)
            val shape = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().last()
            setWorkspace(initial = observed) { observed = it }
            onNodeWithTag("workspace-shape-end-${shape.id}-true").assertIsDisplayed()
                .performMouseInput {
                    moveTo(center)
                    press()
                    moveTo(center + Offset(40f, 30f))
                    release()
                }
            runOnIdle {
                val moved = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().last()
                assertTrue(moved.ends().last().x > shape.ends().last().x)
                assertTrue(moved.ends().last().y > shape.ends().last().y)
            }
        }

    @Test
    fun accountButtonIsRightmostAndOpensWorkingForm() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val account = FakeAccountService()
        setWorkspace(accountService = account)

        val accountBounds = onNodeWithTag(AccountTags.Open).getUnclippedBoundsInRoot()
        val redoBounds = onNodeWithTag(WorkspaceTestTags.StructuralRedo).getUnclippedBoundsInRoot()
        assertTrue(accountBounds.left >= redoBounds.right)
        onNodeWithTag(AccountTags.Open).performClick()
        val screenBounds = onNodeWithTag(AccountTags.Screen).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(screenBounds.right - screenBounds.left > 1300.dp)
        onNodeWithTag(AccountTags.Email).performTextReplacement("owner@example.com")
        onNodeWithTag(AccountTags.Password).performTextReplacement("password123")
        onNodeWithTag(AccountTags.SignIn).performClick()
        waitForIdle()
        assertEquals("https://notes.example.com", account.server)
        assertEquals("owner@example.com", account.email)
        onNodeWithTag(AccountTags.Disconnect).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(AccountTags.SignIn).assertIsDisplayed()
        onNodeWithTag(AccountTags.Back).performClick()
        onNodeWithTag(WorkspaceTestTags.PageCanvas).assertIsDisplayed()
    }

    @Test
    fun failedAccountDisconnectOffersLocalForget() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val account = FakeAccountService(failDisconnect = true)
        account.session.value = AccountSession("https://notes.example.com", "owner@example.com", "a", "d", "active",
            managed = true)
        setWorkspace(accountService = account)
        onNodeWithTag(AccountTags.Open).performClick()
        onNodeWithTag(AccountTags.Disconnect).performClick()
        waitForIdle()
        onNodeWithTag(AccountTags.Forget).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(AccountTags.SignIn).assertIsDisplayed()
    }

    @Test
    fun googleSignInUsesMainWindowAndShowsManagedMembership() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val account = FakeAccountService(googleEnabled = true)
            setWorkspace(accountService = account)
            onNodeWithTag(AccountTags.Open).performClick()
            onNodeWithTag(AccountTags.Google).assertIsEnabled().performClick()
            waitForIdle()
            onNodeWithTag(AccountTags.Subscription).assertIsDisplayed()
            onNodeWithText("google@example.com").assertIsDisplayed()
        }

    @Test
    fun twoRejectedManagedLoginsExposePasswordRecovery() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val account = FakeAccountService(rejectPassword = true)
            setWorkspace(accountService = account)
            onNodeWithTag(AccountTags.Open).performClick()
            onNodeWithTag(AccountTags.Email).performTextReplacement("owner@example.com")
            onNodeWithTag(AccountTags.Password).performTextReplacement("wrongpass")
            repeat(2) {
                onNodeWithTag(AccountTags.SignIn).performClick()
                waitForIdle()
            }
            onNodeWithText("Forgot password?").performClick()
            onNodeWithTag(AccountTags.ResetRequest).performClick()
            waitForIdle()
            assertEquals("owner@example.com", account.resetEmail)
            onNodeWithTag(AccountTags.ResetComplete).assertIsDisplayed()
        }

    @Test
    fun managedAccountCreationRequiresMatchingPasswordConfirmation() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val account = FakeAccountService()
            setWorkspace(accountService = account)
            onNodeWithTag(AccountTags.Open).performClick()
            onNodeWithText("Create account").performClick()
            onNodeWithTag(AccountTags.Email).performTextReplacement("owner@example.com")
            onNodeWithTag(AccountTags.Password).performTextReplacement("password123")
            onNodeWithTag(AccountTags.Create).assertIsNotEnabled()
            onNodeWithTag(AccountTags.CreateConfirm).performTextReplacement("different")
            onNodeWithTag(AccountTags.Create).assertIsNotEnabled()
            onNodeWithTag(AccountTags.CreateConfirm).performTextReplacement("password123")
            onNodeWithTag(AccountTags.Create).assertIsEnabled().performClick()
            waitForIdle()
            assertTrue(account.created)
        }

    @Test
    fun latexPreviewOnAStoredTextBoxRevealsTheEditableSource() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val latex = "Area is \$\\frac{a}{b}\$."
            setWorkspace(initial = WorkspaceState.demo().updateSelectedPage(body = latex).clearCanvasFocus())

            onNodeWithTag("text-box-preview").assertIsDisplayed().performClick()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertTextContains(latex).assertIsFocused()
            onNodeWithTag("text-box-preview").assertDoesNotExist()
        }

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
    fun selectedRibbonButtonUsesUnderlineInsteadOfHoverFill() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setWorkspace(initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
                .focusBody().selectText(TextSelection(0, 6)))
            val button = onNodeWithTag(DocumentRibbonTags.mark(Mark.Bold))
            val idle = button.captureToImage().toPixelMap()
            button.performMouseInput { moveTo(Offset(20f, 20f)) }
            mainClock.advanceTimeBy(300)
            val hover = button.captureToImage().toPixelMap()
            assertTrue((0 until idle.width).all { hover[it, 4] == Color(0xFF32343A) },
                "Hover should fill the whole button")
            button.performClick()
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(780f, 500f))
            }
            mainClock.advanceTimeBy(300)
            val selected = button.captureToImage().toPixelMap()
            assertTrue((0 until idle.width).all { selected[it, 4] == idle[it, 4] },
                "Selection should leave the upper background flat")
            assertTrue((12 until idle.width - 12).all {
                selected[it, selected.height - 2] == Color(0xFF007FFF)
            }, "Selection should show the inset blue underline")
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
            onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.FontColor)).performClick()
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

            onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.Highlight)).performClick()
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
    fun focusedTextBoxHasNoFloatingToolkitAndUsesRightClickForWholeBoxActions() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().copy(activeTab = RibbonTab.Document)
            val id = initial.bodyTextOutline!!.id
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
            onNodeWithTag(WorkspaceTestTags.ObjectColor).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(15f, 10f)) }
            onNodeWithTag(TextMenuTags.CopyBox).performClick()
            runOnIdle { assertEquals(id, observed.canvasClipboard.texts.single().id) }
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performMouseInput { rightClick(Offset(15f, 10f)) }
            onNodeWithTag(TextMenuTags.DeleteBox).performClick()
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
    fun emptyTextBoxHidesMoveAndResizeHandlesUntilTextIsEntered() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().toggleTextTool().createTextBox(300f, 350f)
                .copy(activeTab = RibbonTab.Document)
            val id = initial.focusedTextOutline!!.id
            setWorkspace(initial = initial)

            onNodeWithTag(WorkspaceTestTags.textGrip(id)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.textBox(id) + "-editor")
                .performTextReplacement("A new note")
            onNodeWithTag(WorkspaceTestTags.textGrip(id)).assertExists()
            onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).assertExists()
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
            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.groupCorner(3)).assertDoesNotExist()
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
    fun textGripMovesBeforeReleaseAndRecordsOneUndoStep() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().focusBody()
            val id = initial.focusedTextOutlineId!!
            val startX = initial.bodyTextOutline!!.x
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.textGrip(id)).performMouseInput {
                moveTo(Offset(45f, 12f))
                press()
                moveTo(Offset(85f, 24f))
            }
            runOnIdle {
                assertTrue(observed.bodyTextOutline!!.x > startX)
                assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size)
            }
            val firstX = observed.bodyTextOutline!!.x
            onNodeWithTag(WorkspaceTestTags.textGrip(id)).performMouseInput {
                moveTo(Offset(100f, 32f))
            }
            runOnIdle { assertTrue(observed.bodyTextOutline!!.x > firstX) }
            onNodeWithTag(WorkspaceTestTags.textGrip(id)).performMouseInput { release() }
            runOnIdle {
                assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size)
                assertEquals(startX, observed.undoStructure().bodyTextOutline!!.x)
            }
        }

    @Test
    fun textBoxHasOneCornerHandleThatResizesBothAxesBeforeRelease() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().focusBody()
            val id = initial.focusedTextOutlineId!!
            val start = initial.bodyTextOutline!!
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).assertExists()
            onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).performMouseInput {
                moveTo(Offset(12f, 12f))
                press()
                moveTo(Offset(62f, 52f))
            }
            runOnIdle {
                val resized = observed.bodyTextOutline!!
                assertTrue(resized.width > start.width)
                assertTrue(resized.minHeight > start.minHeight)
            }
            onNodeWithTag(WorkspaceTestTags.textResizeHandle(id)).performMouseInput { release() }
            runOnIdle {
                assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size)
                assertEquals(start.width, observed.undoStructure().bodyTextOutline!!.width)
            }
        }

    @Test
    fun textMoveGripIsCompactAndUsesTheDarkSurfaceColour() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().focusBody()
            val id = initial.focusedTextOutlineId!!
            setWorkspace(initial = initial)

            val grip = onNodeWithTag(WorkspaceTestTags.textGrip(id))
            val bounds = grip.getUnclippedBoundsInRoot()
            assertEquals(48.dp, bounds.right - bounds.left)
            val pixels = grip.captureToImage().toPixelMap()
            val surface = pixels[pixels.width / 6, pixels.height / 3]
            assertTrue(surface.luminance() < 0.3f, "grip surface is too bright: $surface")
        }

    @Test
    fun primeObjectMoveAndCornerResizeApplyBeforeRelease() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val shape = Outline.Equation(id = "live-shape", x = 300f, y = 350f)
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
                moveTo(Offset(90f, 65f))
            }
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>()
                    .first { it.id == shape.id }.x > shape.x)
            }
            onNodeWithTag(WorkspaceTestTags.primeObject(shape.id)).performMouseInput { release() }
            val moved = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>()
                .first { it.id == shape.id }

            onNodeWithTag(WorkspaceTestTags.objectCorner(shape.id, 3)).performMouseInput {
                moveTo(Offset(7f, 7f))
                press()
                moveTo(Offset(47f, 37f))
            }
            runOnIdle {
                val resized = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>()
                    .first { it.id == shape.id }
                assertTrue(resized.width > moved.width,
                    "width ${moved.width} -> ${resized.width}; selected ${observed.selectedObjectIds}")
                assertTrue(resized.height > moved.height,
                    "height ${moved.height} -> ${resized.height}")
            }
            onNodeWithTag(WorkspaceTestTags.objectCorner(shape.id, 3)).performMouseInput { release() }
            runOnIdle { assertEquals(initial.structuralUndo.size + 2, observed.structuralUndo.size) }
        }

    @Test
    fun multipleSelectedObjectsShareOneFrameAndResizeTogether() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val first = Outline.Equation(id = "group-first", x = 300f, y = 350f,
                width = 100f, height = 80f)
            val second = Outline.Equation(id = "group-second", x = 520f, y = 460f,
                width = 90f, height = 60f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + listOf(first, second))) else page
                    })
                })
            }, selectedObjectIds = setOf(first.id, second.id))
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            val frame = onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame)
            frame.assertIsDisplayed()
            val bounds = frame.getUnclippedBoundsInRoot()
            val firstBounds = onNodeWithTag(WorkspaceTestTags.primeObject(first.id)).getUnclippedBoundsInRoot()
            val secondBounds = onNodeWithTag(WorkspaceTestTags.primeObject(second.id)).getUnclippedBoundsInRoot()
            assertTrue(bounds.left < firstBounds.left && bounds.top < firstBounds.top)
            assertTrue(bounds.right > secondBounds.right && bounds.bottom > secondBounds.bottom)
            onNodeWithTag(WorkspaceTestTags.objectCorner(first.id, 3)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.objectCorner(second.id, 3)).assertDoesNotExist()

            onNodeWithTag(WorkspaceTestTags.groupCorner(3)).performMouseInput {
                moveTo(Offset(7f, 7f))
                press()
                moveTo(Offset(47f, 37f))
            }
            runOnIdle {
                val resized = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>()
                assertTrue(resized.first { it.id == first.id }.width > first.width)
                assertTrue(resized.first { it.id == second.id }.width > second.width)
                assertTrue(resized.first { it.id == second.id }.x > second.x)
            }
            onNodeWithTag(WorkspaceTestTags.groupCorner(3)).performMouseInput { release() }
            runOnIdle { assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size) }
        }

    @Test
    fun clickingAndDraggingEmptySpaceInsideGroupFrameKeepsAndMovesTheSelection() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val first = Outline.Equation(id = "move-first", x = 300f, y = 350f,
                width = 100f, height = 80f)
            val second = Outline.Equation(id = "move-second", x = 520f, y = 460f,
                width = 90f, height = 60f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + listOf(first, second))) else page
                    })
                })
            }, selectedObjectIds = setOf(first.id, second.id))
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            // The point lies between the objects, inside their shared frame.
            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).performMouseInput {
                moveTo(Offset(170f, 80f))
                press()
                release()
            }
            runOnIdle { assertEquals(setOf(first.id, second.id), observed.selectedObjectIds) }
            onNodeWithTag(WorkspaceTestTags.primeObject(first.id)).performClick()
            runOnIdle { assertEquals(setOf(first.id, second.id), observed.selectedObjectIds) }
            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).performMouseInput {
                moveTo(Offset(170f, 80f))
                press()
                moveTo(Offset(215f, 110f))
                release()
            }
            runOnIdle {
                assertEquals(setOf(first.id, second.id), observed.selectedObjectIds)
                val moved = observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Equation>()
                assertTrue(moved.first { it.id == first.id }.x > first.x)
                assertTrue(moved.first { it.id == second.id }.x > second.x)
                assertEquals(initial.structuralUndo.size + 1, observed.structuralUndo.size)
            }

            onRoot().performKeyInput { keyDown(Key.Delete); keyUp(Key.Delete) }
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.none { it.id == first.id || it.id == second.id })
                assertTrue(observed.selectedObjectIds.isEmpty())
            }
            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).assertDoesNotExist()
        }

    @Test
    fun deleteInFocusedTextEditorRemovesTextInsteadOfTheBox() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val initial = WorkspaceState.demo().focusBody().selectText(TextSelection(0, 1))
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.BodyEditor).performTextInputSelection(TextRange(0, 1))
            onNodeWithTag(WorkspaceTestTags.BodyEditor).performKeyInput {
                keyDown(Key.Delete)
                keyUp(Key.Delete)
            }
            runOnIdle {
                assertEquals(initial.selectedPage!!.body.drop(1), observed.selectedPage!!.body)
                assertEquals(initial.selectedPage!!.document.outlines.size,
                    observed.selectedPage!!.document.outlines.size)
            }
        }

    @Test
    fun deleteAfterMarqueeSelectionNeedsNoExtraCanvasClick() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo().copy(activeTab = RibbonTab.Draw)
            val first = Outline.Equation(id = "marquee-first", x = 300f, y = 350f,
                width = 100f, height = 80f)
            val second = Outline.Equation(id = "marquee-second", x = 520f, y = 460f,
                width = 90f, height = 60f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + listOf(first, second))) else page
                    })
                })
            })
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput {
                moveTo(Offset(250f, 320f))
                press()
                moveTo(Offset(700f, 600f))
                release()
            }
            runOnIdle { assertEquals(setOf(first.id, second.id), observed.selectedObjectIds) }
            onRoot().performKeyInput { keyDown(Key.Delete); keyUp(Key.Delete) }
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.none { it.id == first.id || it.id == second.id })
                assertTrue(observed.selectedObjectIds.isEmpty())
            }
        }

    @Test
    fun mixedTextAndObjectSelectionMovesAndDeletesFromTheFrame() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val text = Outline.Text.empty().copy(id = "group-text", x = 300f, y = 350f,
                width = 120f, blocks = listOf(Block.of("Selected text")))
            val shape = Outline.Equation(id = "group-equation", x = 520f, y = 460f,
                width = 90f, height = 60f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + listOf(text, shape))) else page
                    })
                })
            }, selectedTextOutlineIds = setOf(text.id), selectedObjectIds = setOf(shape.id))
            var observed = initial
            setWorkspace(initial = initial) { observed = it }

            onNodeWithTag(WorkspaceTestTags.textBox(text.id)).performClick()
            runOnIdle {
                assertEquals(setOf(text.id), observed.selectedTextOutlineIds)
                assertEquals(setOf(shape.id), observed.selectedObjectIds)
            }
            onNodeWithTag(WorkspaceTestTags.textBox(text.id)).performMouseInput {
                moveTo(Offset(20f, 20f))
                press()
                moveTo(Offset(60f, 45f))
                release()
            }
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.filterIsInstance<Outline.Text>()
                    .first { it.id == text.id }.x > text.x)
            }
            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).performMouseInput {
                moveTo(Offset(170f, 80f))
                press()
                moveTo(Offset(210f, 105f))
                release()
            }
            runOnIdle {
                val outlines = observed.selectedPage!!.document.outlines
                assertTrue(outlines.filterIsInstance<Outline.Text>().first { it.id == text.id }.x > text.x)
                assertTrue(outlines.filterIsInstance<Outline.Equation>().first { it.id == shape.id }.x > shape.x)
                assertEquals(setOf(text.id), observed.selectedTextOutlineIds)
                assertEquals(setOf(shape.id), observed.selectedObjectIds)
            }
            onRoot().performKeyInput { keyDown(Key.Delete); keyUp(Key.Delete) }
            runOnIdle {
                assertTrue(observed.selectedPage!!.document.outlines.none { it.id == text.id || it.id == shape.id })
                assertEquals(base.selectedPage!!.body, observed.selectedPage!!.body)
            }
        }

    @Test
    fun lockedGroupKeepsItsFrameWithoutResizeHandles() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val base = WorkspaceState.demo()
            val first = Outline.Equation(id = "locked-first", x = 300f, y = 350f)
            val second = Outline.Equation(id = "locked-second", x = 520f, y = 460f)
            val initial = base.copy(notebooks = base.notebooks.map { notebook ->
                notebook.copy(sections = notebook.sections.map { section ->
                    section.copy(pages = section.pages.map { page ->
                        if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                            outlines = page.document.outlines + listOf(first, second))) else page
                    })
                })
            }, selectedObjectIds = setOf(first.id, second.id)).toggleObjectLock()
            setWorkspace(initial = initial)

            onNodeWithTag(WorkspaceTestTags.GroupSelectionFrame).assertIsDisplayed()
            onNodeWithTag(WorkspaceTestTags.groupCorner(3)).assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.objectCorner(first.id, 3)).assertDoesNotExist()
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
    fun ctrlZAndCtrlRUndoAndRedoCanvasChanges() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val base = WorkspaceState.demo()
        val shape = Outline.Shape(id = "shortcut-shape", x = 300f, y = 350f)
        val withShape = base.copy(notebooks = base.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                        outlines = page.document.outlines + shape)) else page
                })
            })
        })
        val initial = withShape.selectObject(shape.id).moveSelectedObjects(40f, 0f)
        var observed = initial
        setWorkspace(initial = initial) { observed = it }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }

        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.Z); keyUp(Key.Z); keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertEquals(300f, observed.selectedPage!!.document.outlines
                .filterIsInstance<Outline.Shape>().first { it.id == shape.id }.x)
            assertTrue(observed.structuralRedo.isNotEmpty())
        }
        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.R); keyUp(Key.R); keyUp(Key.CtrlLeft)
        }
        runOnIdle { assertEquals(340f, observed.selectedPage!!.document.outlines
            .filterIsInstance<Outline.Shape>().first { it.id == shape.id }.x) }
    }

    /** Android's redo chord works beside the desktop's Ctrl+R. */
    @Test
    fun ctrlShiftZAlsoRedoes() = runDesktopComposeUiTest(width = 1400, height = 900) {
        val base = WorkspaceState.demo()
        val shape = Outline.Shape(id = "redo-shape", x = 300f, y = 350f)
        val withShape = base.copy(notebooks = base.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { page ->
                    if (page.id == base.selectedPageId) page.copy(document = page.document.copy(
                        outlines = page.document.outlines + shape)) else page
                })
            })
        })
        var observed = withShape.selectObject(shape.id).moveSelectedObjects(40f, 0f)
        setWorkspace(initial = observed) { observed = it }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.Z); keyUp(Key.Z); keyUp(Key.CtrlLeft) }
        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.ShiftLeft); keyDown(Key.Z)
            keyUp(Key.Z); keyUp(Key.ShiftLeft); keyUp(Key.CtrlLeft)
        }
        runOnIdle { assertEquals(340f, observed.selectedPage!!.document.outlines
            .filterIsInstance<Outline.Shape>().first { it.id == shape.id }.x) }
    }

    /** Held down, Ctrl+N adds one page: a key's repeats are not new presses. */
    @Test
    fun ctrlNAddsOnePageEvenWhenHeld() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        val before = observed.selectedSection!!.pages.size
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
        // Held past the keyboard's repeat delay, the key sends repeats as a real one does.
        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.N); advanceEventTime(900); keyUp(Key.N); keyUp(Key.CtrlLeft)
        }
        runOnIdle { assertEquals(before + 1, observed.selectedSection!!.pages.size) }
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.N); keyUp(Key.N); keyUp(Key.CtrlLeft) }
        runOnIdle { assertEquals(before + 2, observed.selectedSection!!.pages.size) }
    }

    /** Android's zoom keys, the numpad's among them; in a text box they zoom and type nothing. */
    @Test
    fun zoomKeysZoomFromTheCanvasAndFromATextBox() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        fun ctrl(key: Key) = onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft) }
        onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { click(Offset(780f, 500f)) }
        ctrl(Key.Equals)
        onNodeWithText("125%").assertExists()
        ctrl(Key.Minus)
        ctrl(Key.Minus)
        onNodeWithText("75%").assertExists()
        ctrl(Key.Zero)
        onNodeWithText("100%").assertExists()
        ctrl(Key.NumPadAdd)
        onNodeWithText("125%").assertExists()

        onNodeWithTag(WorkspaceTestTags.BodyEditor).performClick()
        val text = runOnIdle { observed.richText!!.text }
        ctrl(Key.Zero)
        onNodeWithText("100%").assertExists()
        runOnIdle { assertEquals(text, observed.richText!!.text) }
    }

    @Test
    fun middleClickAutoscrollContinuesWhilePointerHoldsItsPosition() = runDesktopComposeUiTest(width = 1400, height = 900) {
        var observed = WorkspaceState.demo()
        setWorkspace { observed = it }
        mainClock.autoAdvance = false
        val before = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)

        canvas.performMouseInput {
            moveTo(Offset(520f, 420f))
            press(MouseButton.Tertiary)
            moveTo(Offset(620f, 520f))
        }
        mainClock.advanceTimeBy(300)
        val panned = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        assertTrue(panned.left < before.left && panned.top < before.top)
        val anchorInk = androidx.compose.ui.graphics.Color(0xFF30343B)
        assertEquals(anchorInk, canvas.captureToImage().toPixelMap()[520, 420])
        mainClock.advanceTimeBy(300)
        val continued = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        assertTrue(continued.left < panned.left && continued.top < panned.top)
        assertEquals(anchorInk, canvas.captureToImage().toPixelMap()[520, 420])
        onNodeWithTag("workspace-pan-indicator").assertDoesNotExist()
        runOnIdle { assertTrue(observed.selectedObjectIds.isEmpty()) }

        canvas.performMouseInput { moveTo(Offset(450f, 350f)) }
        mainClock.advanceTimeBy(300)
        val returned = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        assertTrue(returned.left > continued.left && returned.top > continued.top)
        canvas.performMouseInput { release(MouseButton.Tertiary) }
        mainClock.advanceTimeBy(300)
        val stopped = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        assertEquals(returned, stopped)
        assertTrue(canvas.captureToImage().toPixelMap()[520, 420] != anchorInk)
    }

    @Test
    fun quickMiddleClickReleaseRestoresPointerAndDoesNotKeepScrolling() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setWorkspace { }
        mainClock.autoAdvance = false
        val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas)
        val before = onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot()
        canvas.performMouseInput {
            moveTo(Offset(520f, 420f))
            press(MouseButton.Tertiary)
        }
        val anchorInk = androidx.compose.ui.graphics.Color(0xFF30343B)
        assertEquals(anchorInk, canvas.captureToImage().toPixelMap()[520, 420])
        canvas.performMouseInput { release(MouseButton.Tertiary) }
        mainClock.advanceTimeBy(300)
        assertTrue(canvas.captureToImage().toPixelMap()[520, 420] != anchorInk)
        canvas.performMouseInput { moveTo(Offset(620f, 520f)) }
        mainClock.advanceTimeBy(300)
        assertEquals(before, onNodeWithTag(WorkspaceTestTags.BodyEditor).getUnclippedBoundsInRoot())
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

            onNodeWithTag(DocumentRibbonTags.colorMenu(DocumentRibbonTags.FontColor)).performClick()
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
        accountService: AccountService? = null,
        onStateChange: (WorkspaceState) -> Unit = {},
    ) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                if (clipboard == null) {
                    WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                        accountService = accountService)
                } else {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        WorkspaceScreen(state = state, onStateChange = { state = it(state); onStateChange(state) },
                            accountService = accountService)
                    }
                }
            }
        }
    }

    private class FakeAccountService(
        private val failDisconnect: Boolean = false,
        private val googleEnabled: Boolean = false,
        private val rejectPassword: Boolean = false,
    ) : AccountService {
        override val session = MutableStateFlow<AccountSession?>(null)
        override val managedServerUrl = "https://notes.example.com"
        override val googleAvailable = googleEnabled
        var server: String? = null
        var email: String? = null
        var resetEmail: String? = null
        var created = false
        override suspend fun connect(serverUrl: String, email: String, password: String, create: Boolean) {
            if (rejectPassword) throw AccountRequestException(401, "invalid_credentials", "Wrong credentials")
            server = serverUrl
            this.email = email
            created = create
            session.value = AccountSession(serverUrl, email, "account", "device", "active", managed = true)
        }
        override suspend fun signInWithGoogle(): GoogleSignInOutcome {
            session.value = AccountSession(managedServerUrl, "google@example.com", "a", "d", "active",
                provider = AccountProvider.Google, managed = true)
            return GoogleSignInOutcome.Connected
        }
        override suspend fun requestPasswordReset(email: String) { resetEmail = email }
        override suspend fun refreshSubscription() = AccountSubscription("active")
        override suspend fun disconnect() {
            if (failDisconnect) error("Server unreachable")
            session.value = null
        }
        override suspend fun forget() { session.value = null }
    }

    private class FakeClipboard : ClipboardManager {
        var value: AnnotatedString? = null
        override fun getText(): AnnotatedString? = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }
}
