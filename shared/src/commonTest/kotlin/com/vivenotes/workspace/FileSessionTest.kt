package com.vivenotes.workspace

import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSessionTest {
    private val store = FakeNotesStore()
    private val sections = store.notebook("Field Notes", "Work" to listOf("Draft" to typed("first")))
    private val pageId = store.pageIdsIn(sections.single()).single()
    private val files = FakeNotebookFiles(store)

    private fun TestScope.started(): WorkspaceSession = WorkspaceSession(store, backgroundScope, { "date $it" }).also {
        it.start()
        runCurrent()
    }

    @Test
    fun closeAndReopenKeepsTheNotebookAndItsPage() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        val notebookId = session.state.value!!.selectedNotebookId

        actions.closeNotebook()
        runCurrent()
        assertTrue(session.state.value!!.notebooks.isEmpty())
        assertNotNull(store.body(pageId))

        actions.openPane(FilePane.ClosedNotebooks)
        runCurrent()
        assertEquals(listOf(notebookId), session.state.value!!.filePane.closedNotebooks.map { it.notebook.id })
        actions.reopenNotebook(notebookId)
        runCurrent()
        assertEquals(notebookId, session.state.value!!.selectedNotebookId)
        assertEquals(pageId, session.state.value!!.selectedPageId)
        assertTrue(session.state.value!!.filePane.closedNotebooks.isEmpty())
    }

    @Test
    fun deletedNotebookAppearsInRecoveryAndRestores() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        actions.deleteNotebook()
        runCurrent()
        assertTrue(session.state.value!!.notebooks.isEmpty())

        actions.openPane(FilePane.DeletedItems)
        runCurrent()
        val item = session.state.value!!.filePane.deletedItems.single()
        assertEquals("Field Notes", item.name)
        actions.restoreDeletedItem(item)
        runCurrent()
        assertEquals("Field Notes", session.state.value!!.notebooks.single().name)
        assertTrue(session.state.value!!.filePane.deletedItems.isEmpty())
        assertNotNull(store.pageById(pageId))
    }

    @Test
    fun versionRestoreSavesTheCurrentPageAndReloadsTheCheckpoint() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        val old = typed("old checkpoint")
        store.revisions[pageId] = mutableListOf(PageRevisionSummary("rev-1", pageId, 42, 100) to old)
        session.update { it.focusBody().editSelectedText("current edit", TextSelection(12)) }

        actions.openPane(FilePane.VersionHistory)
        runCurrent()
        assertEquals("current edit", (store.body(pageId) as com.vivenotes.data.PageLoad.Loaded).doc.outlines
            .filterIsInstance<Outline.Text>().first().blocks.first().text)
        actions.selectRevision("rev-1")
        runCurrent()
        assertEquals(old, session.state.value!!.filePane.preview)
        actions.restoreRevision()
        runCurrent()
        assertEquals("old checkpoint", session.state.value!!.selectedPage!!.body)
        assertFalse(session.state.value!!.filePane.busy)
        assertTrue(session.state.value!!.filePane.message!!.contains("previous page"))
    }

    @Test
    fun failedSaveKeepsNotebookOpenAndLeavesTheCurrentVersionUntouched() = runTest {
        val session = started()
        val actions = session.fileActions(files)
        val old = typed("old checkpoint")
        store.revisions[pageId] = mutableListOf(PageRevisionSummary("rev-1", pageId, 42, 100) to old)
        store.saveFailure = IllegalStateException("Disk full")
        session.update { it.focusBody().editSelectedText("unsaved edit", TextSelection(12)) }

        actions.closeNotebook()
        runCurrent()
        assertEquals("Field Notes", session.state.value!!.notebooks.single().name)
        actions.deleteNotebook()
        runCurrent()
        assertEquals("Field Notes", session.state.value!!.notebooks.single().name)
        assertTrue(store.deletes.isEmpty())

        actions.openPane(FilePane.VersionHistory)
        runCurrent()
        actions.selectRevision("rev-1")
        runCurrent()
        actions.restoreRevision()
        runCurrent()
        assertEquals("unsaved edit", session.state.value!!.selectedPage!!.body)
        assertTrue(session.state.value!!.filePane.error!!.contains("could not be saved"))
        assertEquals("first", (store.body(pageId) as com.vivenotes.data.PageLoad.Loaded).doc.outlines
            .filterIsInstance<Outline.Text>().first().blocks.first().text)
    }

    private fun typed(text: String) = PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))))
}
