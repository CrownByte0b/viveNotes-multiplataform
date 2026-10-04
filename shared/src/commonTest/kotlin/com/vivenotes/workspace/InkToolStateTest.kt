package com.vivenotes.workspace

import com.vivenotes.data.InkEdit
import com.vivenotes.data.InkWriter
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InkToolStateTest {
    @Test
    fun inkToolsAreExclusiveAndEscapeAndOtherToolsClearThem() {
        val armed = WorkspaceState.demo().toggleTextTool().toggleInkTool(InkTool.Pen)
        assertEquals(InkTool.Pen, armed.inkTool)
        assertFalse(armed.textToolArmed)
        assertFalse(armed.shapeToolArmed)
        assertFalse(armed.objectLassoArmed)
        assertNull(armed.selectPointer().inkTool)
        assertNull(armed.toggleInkTool(InkTool.Pen).inkTool)
        assertNull(armed.toggleTextTool().inkTool)
        assertNull(armed.toggleShapeTool().inkTool)
        assertNull(armed.toggleObjectLasso().inkTool)
        val unreadable = armed.updatePage(armed.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertEquals(unreadable, unreadable.toggleInkTool(InkTool.Highlighter))
    }

    @Test
    fun anInkEditChangesOnlyItsLoadedPageAndKeepsTheDocumentUntouched() {
        val state = WorkspaceState.demo().toggleInkTool(InkTool.Pen)
        val page = state.selectedPage!!
        val edit = InkEdit.AddStroke(row(page.id))
        val changed = state.applyInkEdit(edit, InkPage(page.id, emptyList()))
        assertSame(page.document, changed.selectedPage!!.document)
        assertEquals(edit, changed.pendingInkEdits.single().edit)
        assertEquals(state, state.applyInkEdit(edit, InkPage("other-page", emptyList())))
        assertEquals(state, state.applyInkEdit(InkEdit.AddStroke(row("other-page")), InkPage(page.id, emptyList())))
        assertEquals(state, state.applyInkEdit(InkEdit.EraseStrokes(emptySet()), InkPage(page.id, emptyList())))
    }

    @Test
    fun failedInkAppendStopsTheEraseAndFlushRetriesInOrderWithoutDocumentAutosave() = runTest {
        val store = FakeNotesStore()
        store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
        val writer = RecordingInkWriter()
        val logs = mutableListOf<String>()
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer,
            log = DebugLog(true, logs::add))
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        val secret = row(pageId).copy(id = "private-row-identifier", points = byteArrayOf(42, 41))
        session.update { it.toggleInkTool(InkTool.Pen) }
        session.update { it.applyInkEdit(InkEdit.AddStroke(secret), InkPage(pageId, emptyList())) }
        session.update { it.applyInkEdit(InkEdit.EraseStrokes(setOf(secret.id)), InkPage(pageId, emptyList())) }
        writer.fail = true
        runCurrent()
        assertEquals(1, writer.attempts)
        assertEquals(2, session.state.value!!.pendingInkEdits.size)
        assertTrue(writer.saved.isEmpty())
        assertTrue(logs.any { it.contains("tool Pen") })
        assertTrue(logs.any { it.contains("disk unavailable") })
        assertTrue(logs.none { it.contains(secret.id) })
        writer.fail = false
        session.flush()
        assertEquals(listOf("add", "erase"), writer.saved)
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
        assertTrue(store.saves.isEmpty())
        assertTrue(logs.any { it.contains("stroke saved") })
        assertTrue(logs.any { it.contains("whole-stroke erase saved") })
    }

    @Test
    fun changingPagesDoesNotLoseAnAlreadyQueuedInkWrite() = runTest {
        val store = FakeNotesStore()
        val section = store.notebook("Notebook", "Section" to listOf("A" to PageDoc.empty(), "B" to PageDoc.empty())).single()
        val writer = RecordingInkWriter()
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer)
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        session.update { it.toggleInkTool(InkTool.Pen).applyInkEdit(InkEdit.AddStroke(row(pageId)), InkPage(pageId, emptyList())) }
        session.update { it.selectPage(store.pageIdsIn(section).last()) }
        session.flush()
        assertEquals(listOf("add"), writer.saved)
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
    }

    @Test
    fun initialSlowInkReadKeepsAuthoringDisabledUntilOriginalInkArrives() = runTest {
        val store = FakeNotesStore()
        store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : com.vivenotes.data.InkSource {
            override suspend fun loadInk(pageId: String): InkPage {
                gate.await()
                return InkPage(pageId, listOf(visible("original")))
            }
        }
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = source)
        session.start()
        runCurrent()
        val loading = session.state.value!!
        assertTrue(loading.selectedPage!!.editable)
        assertFalse(loading.selectedPage!!.inkReady)
        session.update { it.toggleInkTool(InkTool.Pen) }
        assertNull(session.state.value!!.inkTool)
        session.update { it.copy(inkTool = InkTool.Pen).applyInkEdit(
            InkEdit.AddStroke(row(it.selectedPageId)), InkPage(it.selectedPageId, emptyList())) }
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertTrue(session.state.value!!.selectedPage!!.inkReady)
        assertEquals(listOf("original"), session.state.value!!.selectedPage!!.ink!!.strokes.map { it.id })
    }

    @Test
    fun failedPendingStrokeIsMergedWithOriginalInkWhenItsPageReopens() = runTest {
        val store = FakeNotesStore()
        val section = store.notebook("Notebook", "Section" to listOf("A" to PageDoc.empty(), "B" to PageDoc.empty())).single()
        val writer = RecordingInkWriter().apply { fail = true; original = listOf(visible("original")) }
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer)
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        session.update { it.toggleInkTool(InkTool.Pen).applyInkEdit(
            InkEdit.AddStroke(row(pageId)), InkPage(pageId, listOf(visible("original"), visible("stroke")))) }
        runCurrent()
        session.update { it.selectPage(store.pageIdsIn(section).last()) }
        runCurrent()
        session.update { it.selectPage(pageId) }
        runCurrent()
        assertEquals(3, writer.attempts, "page navigation retries the failed write")
        assertEquals(listOf("original", "stroke"), session.state.value!!.selectedPage!!.ink!!.strokes.map { it.id })
        assertTrue(session.state.value!!.selectedPage!!.inkReady)
        assertEquals(1, session.state.value!!.pendingInkEdits.size)
        writer.fail = false
        session.flush()
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
    }

    @Test
    fun failedInkOnAnUnloadedPageBlocksNotebookExportImportAndClose() = runTest {
        val store = FakeNotesStore()
        val section = store.notebook("Notebook", "Section" to listOf("A" to PageDoc.empty(), "B" to PageDoc.empty())).single()
        val writer = RecordingInkWriter().apply { fail = true }
        val files = FakeNotebookFiles(store)
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer)
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        session.update { it.toggleInkTool(InkTool.Pen).applyInkEdit(InkEdit.AddStroke(row(pageId)), InkPage(pageId, emptyList())) }
        runCurrent()
        session.update { it.selectPage(store.pageIdsIn(section).last()) }
        runCurrent()
        val actions = session.fileActions(files)
        actions.exportNotebook()
        runCurrent()
        assertTrue(files.exports.isEmpty())
        assertTrue(session.state.value!!.notebookTransfer.error!!.contains("Ink changes"))
        actions.dismissTransfer()
        actions.importNotebook()
        runCurrent()
        assertTrue(files.imports.isEmpty())
        actions.dismissTransfer()
        val notebook = session.state.value!!.selectedNotebookId
        actions.closeNotebook()
        runCurrent()
        assertTrue(store.observeTree().first().any { it.notebook.id == notebook })
        assertEquals(1, session.state.value!!.pendingInkEdits.size)
    }

    @Test
    fun aSuspendedImportRejectsAnInkGestureAndDoesNotReplayItOverTheArchive() = runTest {
        val store = FakeNotesStore()
        val section = store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty())).single()
        val writer = RecordingInkWriter()
        val files = FakeNotebookFiles(store).apply { gate = kotlinx.coroutines.CompletableDeferred() }
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer)
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        val notebook = session.state.value!!.selectedNotebookId
        files.onImport = {
            writer.original = listOf(visible("archived"))
            com.vivenotes.data.NotebookImportResult(notebook, "Notebook", section, pageId, false, false)
        }
        session.update { it.toggleInkTool(InkTool.Pen) }
        session.fileActions(files).importNotebook()
        runCurrent()
        assertTrue(session.state.value!!.notebookTransfer.running)
        session.update { it.applyInkEdit(InkEdit.AddStroke(row(pageId)), InkPage(pageId, emptyList())) }
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
        files.gate!!.complete(Unit)
        runCurrent()
        session.flush()
        assertTrue(writer.saved.isEmpty())
        assertEquals(listOf("archived"), session.state.value!!.selectedPage!!.ink!!.strokes.map { it.id })
    }

    @Test
    fun failedPendingInkBlocksRevisionRestoreUntilItIsSaved() = runTest {
        val store = FakeNotesStore()
        store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
        val writer = RecordingInkWriter().apply { fail = true }
        val files = FakeNotebookFiles(store)
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = writer)
        session.start()
        runCurrent()
        val pageId = session.state.value!!.selectedPageId
        val original = (store.body(pageId) as com.vivenotes.data.PageLoad.Loaded).doc
        val archive = PageDoc(outlines = listOf(com.vivenotes.model.Outline.Text(
            id = "archived", blocks = listOf(com.vivenotes.model.Block.of("old checkpoint")))))
        store.revisions[pageId] = mutableListOf(com.vivenotes.data.db.PageRevisionSummary("rev", pageId, 42, 100) to archive)
        session.update { it.toggleInkTool(InkTool.Pen).applyInkEdit(InkEdit.AddStroke(row(pageId)), InkPage(pageId, emptyList())) }
        runCurrent()
        val actions = session.fileActions(files)
        actions.openPane(FilePane.VersionHistory)
        runCurrent()
        actions.selectRevision("rev")
        runCurrent()
        actions.restoreRevision()
        runCurrent()
        assertEquals(original, (store.body(pageId) as com.vivenotes.data.PageLoad.Loaded).doc)
        assertTrue(session.state.value!!.filePane.error!!.contains("could not be saved"))
        assertEquals(1, session.state.value!!.pendingInkEdits.size)
        writer.fail = false
        actions.restoreRevision()
        runCurrent()
        assertEquals(archive, (store.body(pageId) as com.vivenotes.data.PageLoad.Loaded).doc)
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
    }

    private fun visible(id: String) = com.vivenotes.model.ink.VisibleInkStroke(
        id, "marker", 1, 3f, 0xff000000.toInt(), false, listOf(com.vivenotes.model.ink.InkSample(10f, 20f)))

    private class RecordingInkWriter : InkWriter {
        var fail = false
        var attempts = 0
        val saved = mutableListOf<String>()
        var original: List<com.vivenotes.model.ink.VisibleInkStroke> = emptyList()
        override suspend fun loadInk(pageId: String) = InkPage(pageId, original)
        override fun overlayPendingInk(page: InkPage, edits: List<InkEdit>): InkPage = edits.fold(page) { snapshot, edit ->
            when (edit) {
                is InkEdit.AddStroke -> snapshot.copy(strokes = snapshot.strokes + edit.row.let { row ->
                    com.vivenotes.model.ink.VisibleInkStroke(row.id, row.brushFamily, row.brushVersion,
                        row.sizeDp, row.colorArgb, row.colorFollowsTheme, emptyList())
                })
                is InkEdit.EraseStrokes -> snapshot.copy(strokes = snapshot.strokes.filterNot { it.id in edit.ids })
            }
        }
        override suspend fun applyInkEdit(pageId: String, edit: InkEdit) {
            attempts++
            if (fail) error("disk unavailable")
            saved += when (edit) { is InkEdit.AddStroke -> "add"; is InkEdit.EraseStrokes -> "erase" }
        }
    }

    private fun row(pageId: String) = InkStrokeEntity("stroke", pageId, 0, "marker", 1, 3f,
        0xff000000.toInt(), true, 0.25f, 0, 0f, 0f, 20f, 20f, byteArrayOf(1), "ink/androidx1", 1L)
}
