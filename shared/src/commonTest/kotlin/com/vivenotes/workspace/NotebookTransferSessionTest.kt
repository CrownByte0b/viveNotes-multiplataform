package com.vivenotes.workspace

import com.vivenotes.data.NotebookImportResult
import com.vivenotes.data.PageLoad
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The File tab's `.vive` export and import over a stored workspace: Android's
 * `exportCurrentNotebook` and `importNotebook` — the open page saved first, the imported notebook
 * opened after, and the page that was open never written over what the archive restored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotebookTransferSessionTest {

    private val store = FakeNotesStore()
    private val sections = store.notebook(
        "Calculus",
        "Chapter 1" to listOf("Limits" to typed("limits"), "Series" to typed("series")),
        "Chapter 2" to listOf("Integrals" to typed("integrals")),
    )
    private val limits = store.pageIdsIn(sections[0])[0]
    private val files = FakeNotebookFiles(store)

    /** One instance, compared by identity of content: every `typed` call mints new block ids. */
    private val archived = typed("limits, as archived")

    @Test
    fun exportOffersTheNotebooksNameSavesTheOpenPageThenWritesThatNotebook() = runTest {
        val session = started()
        session.type("limits, rewritten")
        files.exportedName = "Calculus"

        session.fileActions(files).exportNotebook()
        runCurrent()

        assertEquals(listOf("Calculus.vive"), files.suggestedNames)
        assertEquals(listOf(session.state.value!!.selectedNotebookId to "/home/ada/Documents/chosen.vive"), files.exports)
        assertEquals(listOf("save:$limits"), files.writesAtTransfer, "the open page is saved before the export")
        assertEquals(NotebookTransferState(message = "Calculus was exported as a .vive notebook."),
            session.state.value!!.notebookTransfer)
    }

    @Test
    fun cancellingTheSaveDialogChangesNothing() = runTest {
        val session = started()
        files.destination = null

        session.fileActions(files).exportNotebook()
        runCurrent()

        assertEquals(1, files.suggestedNames.size)
        assertTrue(files.exports.isEmpty())
        assertFalse(session.state.value!!.notebookTransfer.visible)
    }

    @Test
    fun aFailedExportSaysWhyUntilDismissed() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        files.failure = IllegalStateException("The selected destination could not be opened.")

        actions.exportNotebook()
        runCurrent()

        assertEquals(NotebookTransferState(error = "The selected destination could not be opened."),
            session.state.value!!.notebookTransfer)
        actions.dismissTransfer()
        assertFalse(session.state.value!!.notebookTransfer.visible)
    }

    @Test
    fun aRunningTransferCanBeNeitherDismissedNorJoinedByAnother() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        files.gate = CompletableDeferred()

        actions.exportNotebook()
        runCurrent()
        actions.dismissTransfer()
        actions.exportNotebook()
        actions.importNotebook()
        runCurrent()

        assertEquals(NotebookTransferState(running = true), session.state.value!!.notebookTransfer)
        assertEquals(1, files.suggestedNames.size, "no second dialog while one transfer runs")
        files.gate!!.complete(Unit)
        runCurrent()
        assertFalse(session.state.value!!.notebookTransfer.running)
        assertTrue(files.imports.isEmpty())
    }

    @Test
    fun importOpensTheImportedNotebookAtItsFirstPageReadFromStorage() = runTest {
        val session = started()
        files.onImport = {
            val imported = store.notebook("Biology", "Cells" to listOf("Mitosis" to typed("mitosis")))
            NotebookImportResult("biology", "Biology", imported[0], store.pageIdsIn(imported[0])[0],
                created = true, restored = false)
        }

        session.fileActions(files).importNotebook()
        runCurrent()

        val state = session.state.value!!
        assertEquals("Biology", state.selectedNotebook!!.name)
        assertEquals("Cells", state.selectedSection!!.name)
        assertEquals("Mitosis", state.selectedPage!!.title)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals("mitosis", state.selectedPage!!.body)
        assertEquals(NotebookTransferState(message = "Biology was imported."), state.notebookTransfer)
    }

    /** The case Android guards against: the import restores the very page that was open. */
    @Test
    fun anImportThatReplacesTheOpenPageShowsTheArchiveAndNeverWritesTheOldBodyBack() = runTest {
        val session = started()
        session.type("limits, edited here")
        files.onImport = {
            store.storeBody(limits, PageLoad.Loaded(archived))
            store.touchPage(limits, "Limits (archived)")
            NotebookImportResult("calculus", "Calculus", sections[0], limits, created = false, restored = false)
        }

        session.fileActions(files).importNotebook()
        runCurrent()
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS * 3)
        runCurrent()

        val page = session.state.value!!.selectedPage!!
        assertEquals(limits, page.id)
        assertEquals("limits, as archived", page.body)
        assertEquals("Limits (archived)", page.title)
        assertEquals(listOf("save:$limits"), store.writes, "saved once, before the import, and not after")
        assertEquals(archived, (store.body(limits) as PageLoad.Loaded).doc)
        assertEquals(NotebookTransferState(message = "Calculus was updated from the .vive notebook."),
            session.state.value!!.notebookTransfer)
    }

    /**
     * An edit that reaches the page while the import runs — the dialog should prevent it — is
     * queued behind the import, and must not overwrite what the import restored.
     */
    @Test
    fun anEditMadeWhileTheImportRunsIsNotWrittenOverWhatItRestored() = runTest {
        val session = started()
        files.gate = CompletableDeferred()
        files.onImport = {
            store.storeBody(limits, PageLoad.Loaded(archived))
            NotebookImportResult("calculus", "Calculus", sections[0], limits, created = false, restored = false)
        }

        session.fileActions(files).importNotebook()
        runCurrent()
        session.type("typed during the import")
        session.update { it.updateSelectedPage(title = "Retitled during the import") }
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS + 1)
        runCurrent()
        files.gate!!.complete(Unit)
        runCurrent()

        assertEquals(archived, (store.body(limits) as PageLoad.Loaded).doc)
        assertTrue(store.renames.isEmpty(), "the title typed meanwhile is not stored over the archive's")
        assertEquals("limits, as archived", session.state.value!!.selectedPage!!.body)
    }

    /** A page the archive restored is not in the list the workspace had; it opens once listed. */
    @Test
    fun anImportIntoTheOpenSectionOpensAPageItsListDidNotYetShow() = runTest {
        val session = started()
        files.onImport = {
            val restored = store.createPage(sections[0], "Proofs")
            NotebookImportResult("calculus", "Calculus", sections[0], restored, created = false, restored = true)
        }

        session.fileActions(files).importNotebook()
        runCurrent()

        val state = session.state.value!!
        assertEquals("Proofs", state.selectedPage!!.title)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals(NotebookTransferState(message = "Calculus was restored from the .vive notebook."), state.notebookTransfer)
    }

    @Test
    fun structuralUndoCannotPutBackAPageTheImportReplaced() = runTest {
        val session = started()
        session.update { it.deleteTextBox("text") }
        assertTrue(session.state.value!!.structuralUndo.isNotEmpty())
        // Structurally different from the snapshot the delete left: undoing onto it would drop a box.
        val restored = PageDoc(outlines = listOf(
            Outline.Text(id = "text", blocks = listOf(Block.of("limits, as archived"))),
            Outline.Text(id = "second", y = 200f, blocks = listOf(Block.of("added in the archive"))),
        ))
        files.onImport = {
            store.storeBody(limits, PageLoad.Loaded(restored))
            NotebookImportResult("calculus", "Calculus", sections[0], limits, created = false, restored = false)
        }

        session.fileActions(files).importNotebook()
        runCurrent()
        assertTrue(session.state.value!!.structuralUndo.isEmpty(), "no undo step reaches back past the import")
        session.update { it.undoStructure() }
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS * 2)
        runCurrent()

        assertEquals(restored, (store.body(limits) as PageLoad.Loaded).doc)
        assertEquals(restored, session.state.value!!.selectedPage!!.document)
    }

    @Test
    fun aFailedImportSaysWhyAndLeavesTheOpenPageAsItWas() = runTest {
        val session = started()
        session.type("kept")
        files.failure = IllegalStateException("This .vive file is damaged or unsafe and was not imported.")

        session.fileActions(files).importNotebook()
        runCurrent()

        val state = session.state.value!!
        assertEquals(limits, state.selectedPageId)
        assertEquals("kept", state.selectedPage!!.body)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals(NotebookTransferState(error = "This .vive file is damaged or unsafe and was not imported."),
            state.notebookTransfer)
    }

    @Test
    fun cancellingTheOpenDialogImportsNothing() = runTest {
        val session = started()
        files.source = null

        session.fileActions(files).importNotebook()
        runCurrent()

        assertTrue(files.imports.isEmpty())
        assertFalse(session.state.value!!.notebookTransfer.visible)
    }

    private fun TestScope.started(): WorkspaceSession =
        WorkspaceSession(store, backgroundScope, createdLabel = { "created $it" }).also {
            it.start()
            runCurrent()
        }

    private fun WorkspaceSession.type(text: String) = update {
        it.focusBody().editSelectedText(text, TextSelection(text.length))
    }

    private fun typed(text: String) =
        PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))))
}
