package com.vivenotes.ui.ribbon.file

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.FilePaneState
import com.vivenotes.data.DeletedItemKey
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.PageDoc
import com.vivenotes.data.DeletedItem
import com.vivenotes.workspace.NotebookTransferState
import com.vivenotes.workspace.RibbonTab
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The File tab's notebook commands and the transfer dialog, as Android's File tab has them. */
@OptIn(ExperimentalTestApi::class)
class FileTabTest {

    private val calls = mutableListOf<String>()
    private val actions = object : FileActions {
        override fun openPane(pane: FilePane) { calls += "pane:$pane" }
        override fun closePane() { calls += "close-pane" }
        override fun selectRevision(id: String) { calls += "select:$id" }
        override fun restoreRevision() { calls += "restore-revision" }
        override fun restoreDeletedItem(item: DeletedItem) { calls += "restore-deleted" }
        override fun reopenNotebook(id: String) { calls += "reopen:$id" }
        override fun closeNotebook() { calls += "close-notebook" }
        override fun deleteNotebook() { calls += "delete-notebook" }
        override fun exportNotebook() { calls += "export" }
        override fun importNotebook() { calls += "import" }
        override fun dismissTransfer() { calls += "dismiss" }
    }
    private var shown = WorkspaceState.demo()

    private fun ComposeUiTest.setWorkspace(
        initial: WorkspaceState = WorkspaceState.demo().copy(activeTab = RibbonTab.File),
        fileActions: FileActions? = actions,
    ) {
        shown = initial
        setContent {
            var state by androidx.compose.runtime.remember { mutableStateOf(initial) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state, onStateChange = { state = it(state); shown = state },
                    fileActions = fileActions)
            }
        }
    }

    @Test
    fun theTabListsAndroidsCommandsInItsOrder() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace()

        val labels = listOf("Export PDF", "Version History", "Deleted Items", "Closed Notebooks", "Close Notebook",
            "Export Notebook", "Import", "Delete Notebook")
        val lefts = labels.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts, "in Android's order")
        onNodeWithText("Export PDF").assertIsNotEnabled()
        listOf(FileRibbonTags.VersionHistory, FileRibbonTags.DeletedItems, FileRibbonTags.ClosedNotebooks,
            FileRibbonTags.CloseNotebook, FileRibbonTags.DeleteNotebook).forEach {
            onNodeWithTag(it).assertIsEnabled()
        }
    }

    @Test
    fun pdfOpensFromTheFileTabAndEscapeReturnsToTheSamePage() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val exporter = object : com.vivenotes.pdf.PdfExportService {
            override suspend fun defaultOptionsFor(pageId: String?) = com.vivenotes.pdf.PdfExportOptions()
            override suspend fun plan(request: com.vivenotes.pdf.PdfExportRequest): com.vivenotes.pdf.PdfExportPlan {
                val paper = com.vivenotes.pdf.PdfPaper.of(request.options.paper, request.options.orientation)
                val tile = com.vivenotes.pdf.PdfTile(0, 0, com.vivenotes.pdf.PdfBounds(0f, 0f, 1000f, 1000f))
                return com.vivenotes.pdf.PdfExportPlan(request.options, paper,
                    listOf(com.vivenotes.pdf.PlannedPage(request.pageId!!, "Draft",
                        com.vivenotes.pdf.PdfPagePlan(listOf(tile), emptyMap()))),
                    listOf(com.vivenotes.pdf.PdfSheetRef(0, 0)), "Draft.pdf")
            }
            override suspend fun preview(plan: com.vivenotes.pdf.PdfExportPlan, sheetIndex: Int, maxWidthPx: Int) =
                androidx.compose.ui.graphics.ImageBitmap(20, 30)
            override suspend fun chooseDestination(suggestedName: String): String? = null
            override suspend fun write(plan: com.vivenotes.pdf.PdfExportPlan, destination: String, onProgress: (Int, Int) -> Unit) = 0
            override suspend fun release() {}
        }
        val pdfActions = object : FileActions by actions { override val pdfExporter = exporter }
        setWorkspace(fileActions = pdfActions)
        val page = shown.selectedPageId
        onNodeWithTag(FileRibbonTags.ExportPdf).assertIsEnabled().performClick()
        onNodeWithTag(ExportPdfTags.DIALOG).fetchSemanticsNode()
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        onNodeWithTag(FileRibbonTags.ExportPdf).assertIsEnabled()
        runOnIdle { assertEquals(page, shown.selectedPageId) }
    }

    @Test
    fun pdfIsInertWithoutAnOpenPageEvenWhenTheExporterIsAvailable() = runDesktopComposeUiTest(width = 2000, height = 900) {
        val exporter = ExportPdfDialogTest.Exporter()
        val pdfActions = object : FileActions by actions { override val pdfExporter = exporter }
        setWorkspace(WorkspaceState(emptyList(), "", "", "", activeTab = RibbonTab.File), fileActions = pdfActions)
        onNodeWithTag(FileRibbonTags.ExportPdf).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithTag(ExportPdfTags.DIALOG).assertDoesNotExist()
        assertTrue(exporter.requests.isEmpty())
    }

    @Test
    fun exportNotebookAndImportRunTheirCommands() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace()

        onNodeWithTag(FileRibbonTags.ExportNotebook).assertIsEnabled().performClick()
        onNodeWithTag(FileRibbonTags.ImportNotebook).assertIsEnabled().performClick()

        runOnIdle { assertEquals(listOf("export", "import"), calls) }
    }

    @Test
    fun historyAndShelvesOpenAndNotebookCommandsAskBeforeChangingIt() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace()
        onNodeWithTag(FileRibbonTags.VersionHistory).performClick()
        onNodeWithTag(FileRibbonTags.DeletedItems).performClick()
        onNodeWithTag(FileRibbonTags.ClosedNotebooks).performClick()
        onNodeWithTag(FileRibbonTags.CloseNotebook).performClick()
        onNodeWithText("Close Calculus?").fetchSemanticsNode()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertTrue("close-notebook" !in calls) }
        onNodeWithTag(FileRibbonTags.CloseNotebook).performClick()
        onNodeWithTag(FilePaneTags.ConfirmNotebook).performClick()
        onNodeWithTag(FileRibbonTags.DeleteNotebook).performClick()
        onNodeWithTag(FilePaneTags.ConfirmNotebook).performClick()
        runOnIdle { assertEquals(listOf("pane:VersionHistory", "pane:DeletedItems", "pane:ClosedNotebooks",
            "close-notebook", "delete-notebook"), calls) }
    }

    @Test
    fun filePaneShowsStoredItemsAndInvokesRecoveryActions() = runDesktopComposeUiTest(width = 2000, height = 900) {
        val item = DeletedItem(DeletedItemKey("gone", DeletedItemKind.Page), "Draft", "Book", "Chapter", 42)
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.File,
            filePane = FilePaneState(pane = FilePane.DeletedItems, deletedItems = listOf(item))))
        onNodeWithTag(FilePaneTags.deleted("gone")).fetchSemanticsNode()
        onNodeWithTag(FilePaneTags.restoreDeleted("gone")).performClick()
        runOnIdle { assertTrue("restore-deleted" in calls) }
    }

    @Test
    fun revisionPreviewNeedsConfirmation() = runDesktopComposeUiTest(width = 2000, height = 900) {
        val revision = PageRevisionSummary("rev", "lecture-notes", 42, 120)
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.File,
            filePane = FilePaneState(pane = FilePane.VersionHistory, revisionPageId = "lecture-notes",
                revisions = listOf(revision), selectedRevisionId = "rev", preview = PageDoc.empty())))
        onNodeWithTag(FilePaneTags.Preview).fetchSemanticsNode()
        onNodeWithTag(FilePaneTags.RestoreRevision).performClick()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertTrue("restore-revision" !in calls) }
        onNodeWithTag(FilePaneTags.RestoreRevision).performClick()
        onNodeWithTag(FilePaneTags.ConfirmRestore).performClick()
        runOnIdle { assertTrue("restore-revision" in calls) }
    }

    @Test
    fun withoutFileDialogsNeitherIsAvailable() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(fileActions = null)

        onNodeWithTag(FileRibbonTags.ExportNotebook).assertIsNotEnabled()
        onNodeWithTag(FileRibbonTags.ImportNotebook).assertIsNotEnabled()
    }

    /** Export writes the notebook the open section is in, so with none open there is nothing to write. */
    @Test
    fun exportNeedsAnOpenNotebookAndImportDoesNot() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState(emptyList(), "", "", "", activeTab = RibbonTab.File))

        onNodeWithTag(FileRibbonTags.ExportNotebook).assertIsNotEnabled()
        onNodeWithTag(FileRibbonTags.ImportNotebook).assertIsEnabled()
    }

    @Test
    fun whileATransferRunsTheDialogStaysAndHoldsTheKeyboard() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.File,
            notebookTransfer = NotebookTransferState(running = true)))
        val pages = runOnIdle { shown.selectedSection!!.pages.size }

        onNodeWithTag(FileRibbonTags.TransferDialog).assertTextContains("Working with notebook")
        onNodeWithText("Checking and preparing the notebook…").fetchSemanticsNode()
        assertTrue(onAllNodesWithTag(FileRibbonTags.TransferOk).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(FileRibbonTags.ExportNotebook).assertIsNotEnabled()
        onNodeWithTag(FileRibbonTags.ImportNotebook).assertIsNotEnabled()
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        onNodeWithTag(FileRibbonTags.TransferBackdrop).performClick()
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); keyDown(Key.N); keyUp(Key.N); keyUp(Key.CtrlLeft) }

        runOnIdle {
            assertTrue(calls.isEmpty(), "the running dialog was dismissed: $calls")
            assertEquals(pages, shown.selectedSection!!.pages.size, "a shortcut reached the workspace behind the dialog")
        }
    }

    @Test
    fun aFinishedTransferSaysHowItWentUntilOk() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.File,
            notebookTransfer = NotebookTransferState(message = "Biology was imported.")))

        onNodeWithTag(FileRibbonTags.TransferDialog).assertTextContains("Notebook transfer complete")
        onNodeWithText("Biology was imported.").fetchSemanticsNode()
        onNodeWithTag(FileRibbonTags.TransferOk).performClick()

        runOnIdle { assertEquals(listOf("dismiss"), calls) }
    }

    /**
     * When the transfer ends, the progress row that held the keyboard goes; OK takes it over, and
     * the workspace's own fallback must not take it from OK — Esc and Enter belong to the dialog.
     */
    @Test
    fun whenTheTransferEndsOkHasTheKeyboard() = runDesktopComposeUiTest(width = 2000, height = 900) {
        val transfer = mutableStateOf(NotebookTransferState(running = true))
        setContent {
            var state by androidx.compose.runtime.remember { mutableStateOf(WorkspaceState.demo().copy(activeTab = RibbonTab.File)) }
            ViveNotesTheme(darkTheme = true) {
                WorkspaceScreen(state = state.copy(notebookTransfer = transfer.value),
                    onStateChange = { state = it(state) }, fileActions = actions)
            }
        }
        waitForIdle()

        transfer.value = NotebookTransferState(message = "Field Notes was exported as a .vive notebook.")
        waitForIdle()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }

        runOnIdle { assertEquals(listOf("dismiss"), calls) }
    }

    @Test
    fun aFailedTransferSaysWhy() = runDesktopComposeUiTest(width = 2000, height = 900) {
        setWorkspace(WorkspaceState.demo().copy(activeTab = RibbonTab.File,
            notebookTransfer = NotebookTransferState(error = "This .vive file is damaged or unsafe and was not imported.")))

        onNodeWithTag(FileRibbonTags.TransferDialog).assertTextContains("Notebook transfer failed")
        onNodeWithText("This .vive file is damaged or unsafe and was not imported.").fetchSemanticsNode()
        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }

        runOnIdle { assertEquals(listOf("dismiss"), calls, "Esc closes a finished transfer's dialog") }
    }
}
