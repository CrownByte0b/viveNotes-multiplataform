package com.vivenotes.ui.shell

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ai.*
import com.vivenotes.data.DeletedItem
import com.vivenotes.search.*
import com.vivenotes.ui.components.HoverTooltipDelayMillis
import com.vivenotes.ui.components.ToolPaneTags
import com.vivenotes.ui.ribbon.file.FilePaneTags
import com.vivenotes.ui.ribbon.file.FileRibbonTags
import com.vivenotes.ui.ribbon.settings.HardwareTags
import com.vivenotes.ui.ribbon.settings.ModelsTags
import com.vivenotes.ui.ribbon.settings.ShortcutDialogTags
import com.vivenotes.ui.ribbon.view.ViewRibbonTags
import com.vivenotes.ui.search.SearchTags
import com.vivenotes.workspace.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class EscapeDismissalTest {
    private var observed = WorkspaceState.demo()
    private var change: ((WorkspaceState) -> WorkspaceState) -> Unit = {}
    private var fileCloses = 0
    private val files = object : FileActions {
        override fun openPane(pane: FilePane) = change { it.copy(filePane = FilePaneState(pane = pane)) }
        override fun closePane() { fileCloses++; change { it.copy(filePane = FilePaneState()) } }
        override fun selectRevision(id: String) {}
        override fun restoreRevision() {}
        override fun restoreDeletedItem(item: DeletedItem) {}
        override fun reopenNotebook(id: String) {}
        override fun closeNotebook() {}
        override fun deleteNotebook() {}
        override fun exportNotebook() {}
        override fun importNotebook() {}
        override fun dismissTransfer() {}
    }
    private val search = object : ContentSearchService {
        override fun search(request: SearchRequest) = flowOf(SearchResults())
    }
    private val models = object : AiModels {
        override val state = MutableStateFlow(AiModelsState(handwritingText = AiModelInstallState.Installed))
        override fun download(engine: FormulaEngine) {}
        override fun delete(engine: FormulaEngine) {}
        override fun selectFormulaEngine(engine: FormulaEngine) {}
    }

    private fun ComposeUiTest.workspace(
        tab: RibbonTab = RibbonTab.Document,
        bindings: KeyBindings = KeyBindings.Default,
    ) {
        observed = WorkspaceState.demo().copy(activeTab = tab, inkTool = InkTool.Pen)
        setContent {
            var state by remember { mutableStateOf(observed) }
            change = { state = it(state); observed = state }
            WorkspaceScreen(state, change, keyBindings = bindings, search = search, aiModels = models, fileActions = files)
        }
    }

    private fun ComposeUiTest.escape() = onNodeWithTag(WorkspaceTestTags.HeaderBar).performKeyInput { pressKey(Key.Escape) }

    @Test fun escapeClosesSearchFromItsFocusedQueryEvenWithSelectToolDisabled() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            workspace(bindings = KeyBindings.Default.rebind(ShortcutAction.SelectTool, null))
            onNodeWithTag(WorkspaceTestTags.HeaderBar).performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft)
            }
            onNodeWithTag(SearchTags.Query).performTextInput("needle")
            escape()
            onNodeWithTag(SearchTags.Pane).assertDoesNotExist()
            runOnIdle { assertEquals(InkTool.Pen, observed.inkTool) }
            onNodeWithTag(WorkspaceTestTags.HeaderBar).performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.F); keyUp(Key.CtrlLeft) }
            onNodeWithTag(SearchTags.Query).assertIsFocused()
        }

    @Test fun escapeClosesHardwareAndTheNextPressReturnsToThePointer() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            workspace(RibbonTab.Settings)
            onNodeWithTag(HardwareTags.Open).performClick()
            escape()
            onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
            runOnIdle { assertEquals(InkTool.Pen, observed.inkTool) }
            escape()
            runOnIdle { assertNull(observed.inkTool) }
        }

    @Test fun escapeClosesModels() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace(RibbonTab.Settings)
        onNodeWithTag(ModelsTags.Open).performClick()
        escape()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
        runOnIdle { assertEquals(InkTool.Pen, observed.inkTool) }
    }

    @Test fun escapeClosesPaperSizeFromAFocusedNumberField() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace(RibbonTab.View)
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(ToolPaneTags.field("Top")).performClick()
        escape()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
        runOnIdle { assertEquals(InkTool.Pen, observed.inkTool) }
    }

    @Test fun escapeClosesAllFilePanesAndDoesNotExposeTheCoveredSettingsPane() {
        FilePane.entries.forEach { pane ->
            runDesktopComposeUiTest(width = 1600, height = 900) {
                workspace(RibbonTab.Settings)
                onNodeWithTag(HardwareTags.Open).performClick()
                onNodeWithTag(WorkspaceTestTags.ribbonTab(RibbonTab.File)).performClick()
                val command = when (pane) {
                    FilePane.VersionHistory -> FileRibbonTags.VersionHistory
                    FilePane.DeletedItems -> FileRibbonTags.DeletedItems
                    FilePane.ClosedNotebooks -> FileRibbonTags.ClosedNotebooks
                }
                onNodeWithTag(command).performClick()
                onNodeWithTag(FilePaneTags.Pane).assertExists()
                val closesBefore = fileCloses
                escape()
                onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
                runOnIdle { assertEquals(closesBefore + 1, fileCloses) }
            }
        }
    }

    @Test fun escapeDismissesVisibleHoverTooltipsAndTheyStayDismissedUntilHoveredAgain() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            workspace()
            mainClock.autoAdvance = false
            val button = onNodeWithTag(WorkspaceTestTags.NavigationToggle)
            button.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HoverTooltipDelayMillis + 20)
            onNodeWithText("Hide notebook navigation").assertExists()
            escape()
            mainClock.advanceTimeByFrame()
            onNodeWithText("Hide notebook navigation").assertDoesNotExist()
            mainClock.advanceTimeBy(2_000)
            onNodeWithText("Hide notebook navigation").assertDoesNotExist()
            runOnIdle { assertEquals(InkTool.Pen, observed.inkTool) }
            onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(center) }
            button.performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(HoverTooltipDelayMillis + 20)
            onNodeWithText("Hide notebook navigation").assertExists()
        }

    @Test fun aVisibleTooltipIsDismissedBeforeTheRightPanel() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace(RibbonTab.Settings)
        onNodeWithTag(HardwareTags.Open).performClick()
        mainClock.autoAdvance = false
        onNodeWithTag(WorkspaceTestTags.NavigationToggle).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(HoverTooltipDelayMillis + 20)
        onNodeWithText("Hide notebook navigation").assertExists()
        escape()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Hide notebook navigation").assertDoesNotExist()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        escape()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    @Test fun aShortcutDialogDismissesBeforeItsPanel() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace(RibbonTab.Settings)
        onNodeWithTag(HardwareTags.Open).performClick()
        onNodeWithTag(HardwareTags.shortcut(ShortcutAction.NewPage)).performClick()
        escape()
        onNodeWithTag(ShortcutDialogTags.Capture).assertDoesNotExist()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        escape()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    @Test fun escapeClosesADropdownBeforeThePanelUnderIt() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace(RibbonTab.View)
        onNodeWithTag(ViewRibbonTags.PaperSize).performClick()
        onNodeWithTag(ToolPaneTags.field("Size")).performClick()
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).assertExists()
        mainClock.advanceTimeBy(2_000)
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(ToolPaneTags.option("Size", "A4")).assertDoesNotExist()
        onNodeWithTag(ToolPaneTags.Pane).assertExists()
        escape()
        onNodeWithTag(ToolPaneTags.Pane).assertDoesNotExist()
    }

    @Test fun escapeClosesAContextMenu() = runDesktopComposeUiTest(width = 1600, height = 900) {
        workspace()
        onNodeWithTag(WorkspaceTestTags.page("lecture-notes")).performMouseInput { rightClick(center) }
        onNodeWithText("Rename page").assertExists()
        onNodeWithText("Rename page").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithText("Rename page").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Rename page").assertDoesNotExist()
    }
}
