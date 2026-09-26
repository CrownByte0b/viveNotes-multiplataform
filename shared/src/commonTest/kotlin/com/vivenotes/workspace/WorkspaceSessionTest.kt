package com.vivenotes.workspace

import com.vivenotes.data.PageLoad
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSessionTest {

    private val store = FakeNotesStore()
    private val sections = store.notebook(
        "Calculus",
        "Chapter 1" to listOf("Limits" to typed("limits"), "Series" to typed("series")),
        "Chapter 2" to listOf("Integrals" to typed("integrals")),
    )
    private val limits = store.pageIdsIn(sections[0])[0]
    private val series = store.pageIdsIn(sections[0])[1]
    private val integrals = store.pageIdsIn(sections[1])[0]

    @Test
    fun nothingIsShownUntilStorageIsSeededAndRead() = runTest {
        val session = session()
        assertNull(session.state.value)

        session.start()
        runCurrent()

        assertEquals(1, store.seeds)
        assertNotNull(session.state.value)
    }

    /** Android's first landing: the first notebook's first section, and its first page open. */
    @Test
    fun opensTheFirstPageOfTheFirstSectionWithItsBodyRead() = runTest {
        val session = started()

        val state = session.state.value!!
        assertEquals(sections[0], state.selectedSectionId)
        assertEquals(limits, state.selectedPageId)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals("limits", state.selectedPage!!.body)
        assertEquals(listOf("Limits", "Series"), state.selectedSection!!.pages.map { it.title })
        assertEquals("created 1000", state.selectedPage!!.createdLabel)
    }

    /** Only the open page's body is read: listing pages must never decode every document. */
    @Test
    fun onlyTheOpenPageHasItsBodyRead() = runTest {
        val session = started()

        assertEquals(listOf(limits), store.loads)
        val other = session.state.value!!.selectedSection!!.pages.first { it.id == series }
        assertEquals(PageContent.Unloaded, other.content)
        assertEquals("series", other.preview, "the list still shows the stored preview")
    }

    @Test
    fun typingIsSavedOnceTheAutosaveDelayPassesWithoutAnotherChange() = runTest {
        val session = started()

        session.type("l")
        advanceTimeBy(300)
        session.type("li")
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(store.saves.isEmpty(), "saved before typing paused")

        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf(limits to "li"), store.saves.map { (id, doc) -> id to doc.text() })
        assertEquals("li", store.saves.single().second.text())
    }

    @Test
    fun openingAnotherPageSavesTheOneLeftAtOnceAndReadsTheNewOne() = runTest {
        val session = started()
        session.type("edited")

        session.update { it.selectPage(series) }
        runCurrent()

        assertEquals(listOf(limits to "edited"), store.saves.map { (id, doc) -> id to doc.text() })
        val state = session.state.value!!
        assertEquals("series", state.selectedPage!!.body)
        assertEquals(PageContent.Unloaded, state.selectedSection!!.pages.first { it.id == limits }.content)
    }

    /** The save is queued ahead of the read, so coming straight back shows what was just typed. */
    @Test
    fun aPageReopenedStraightAwayShowsWhatWasTypedOnIt() = runTest {
        val session = started()
        session.type("kept")
        session.update { it.selectPage(series) }
        session.update { it.selectPage(limits) }

        runCurrent()

        assertEquals("kept", session.state.value!!.selectedPage!!.body)
    }

    /** A page is read afresh on every opening, so a body stored meanwhile is the one shown. */
    @Test
    fun aBodyStoredWhileThePageWasClosedIsWhatItReopensWith() = runTest {
        val session = started()
        session.update { it.selectPage(series) }
        runCurrent()

        store.storeBody(limits, PageLoad.Loaded(typed("from elsewhere")))
        session.update { it.selectPage(limits) }
        runCurrent()

        assertEquals("from elsewhere", session.state.value!!.selectedPage!!.body)
    }

    @Test
    fun browsingWithoutEditingWritesNothing() = runTest {
        val session = started()

        session.update { it.selectPage(series) }
        session.update { it.selectSection(sections[1]) }
        runCurrent()
        session.flush()

        assertTrue(store.saves.isEmpty())
        assertEquals(integrals, session.state.value!!.selectedPageId, "the section's first page opens")
    }

    @Test
    fun titleEditsAreStoredAsTheyAreTypedAndNotUndoneByTheListCatchingUp() = runTest {
        val session = started()

        session.update { it.updateSelectedPage(title = "L") }
        session.update { it.updateSelectedPage(title = "Li") }
        // A list update carrying an older title, as the pages flow can while storage catches up.
        store.touchPage(limits, "L")
        runCurrent()

        assertEquals(listOf(limits to "L", limits to "Li"), store.renames)
        assertEquals("Li", session.state.value!!.selectedPage!!.title)
    }

    @Test
    fun aListUpdateKeepsTheOpenPagesUnsavedEdits() = runTest {
        val session = started()
        session.type("unsaved")

        store.touchPage(series, "Series, renamed elsewhere")
        runCurrent()

        val state = session.state.value!!
        assertEquals("unsaved", state.selectedPage!!.body)
        assertEquals("Series, renamed elsewhere", state.selectedSection!!.pages.first { it.id == series }.title)
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS + 1)
        runCurrent()
        assertEquals("unsaved", store.saves.single().second.text())
    }

    /** Android's rule: a body that will not decode is shown read-only and is never written over. */
    @Test
    fun anUnreadablePageCannotBeEditedAndIsNeverSaved() = runTest {
        store.storeBody(limits, PageLoad.Unreadable("{broken", IllegalStateException("bad json")))
        val session = started()
        val raw = store.body(limits)

        assertEquals(PageContent.Unreadable, session.state.value!!.selectedPage!!.content)
        session.type("overwrite")
        session.update { it.toggleTextTool().createTextBox(300f, 300f) }
        assertTrue(session.state.value!!.selectedPage!!.document.outlines.isEmpty())

        session.update { it.updateSelectedPage(title = "Still renameable") }
        session.update { it.selectPage(series) }
        runCurrent()
        session.flush()

        assertTrue(store.saves.isEmpty())
        assertEquals(raw, store.body(limits))
        assertEquals(listOf(limits to "Still renameable"), store.renames)
    }

    @Test
    fun aNewPageIsCreatedInStorageAndOpened() = runTest {
        val session = started()
        session.type("before the new page")

        session.addPage()
        runCurrent()

        val created = store.pageIdsIn(sections[0]).last()
        val state = session.state.value!!
        assertEquals(created, state.selectedPageId)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals(PageDoc.empty().outlines.map { it::class }, state.selectedPage!!.document.outlines.map { it::class })
        assertEquals(listOf(limits, series, created), state.selectedSection!!.pages.map { it.id })
        assertEquals("before the new page", store.saves.single().second.text(), "the page left was saved first")
    }

    @Test
    fun aFailedSaveIsReportedAndTheEditIsKeptForTheNextAttempt() = runTest {
        val session = started()
        store.saveFailure = IllegalStateException("disk full")

        session.type("precious")
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS + 1)
        runCurrent()

        val error = session.state.value!!.storageError
        assertNotNull(error)
        assertTrue("disk full" in error, error)

        store.saveFailure = null
        session.flush()

        assertEquals("precious", store.saves.single().second.text())
        assertNull(session.state.value!!.storageError)
    }

    @Test
    fun flushWritesAPendingEditWithoutWaitingForTheDelay() = runTest {
        val session = started()
        session.type("closing")

        session.flush()

        assertEquals("closing", store.saves.single().second.text())
    }

    private fun TestScope.session() =
        WorkspaceSession(store, backgroundScope, createdLabel = { "created $it" })

    private fun TestScope.started(): WorkspaceSession = session().also {
        it.start()
        runCurrent()
    }

    /** Replaces the open page's first text box with [text], as typing into it does. */
    /** Types into the page's first text box, focusing it first as the editor does. */
    private fun WorkspaceSession.type(text: String) = update {
        it.focusBody().editSelectedText(text, TextSelection(text.length))
    }

    private fun PageDoc.text(): String =
        outlines.filterIsInstance<Outline.Text>().first().blocks.joinToString("\n") { it.text }

    private fun typed(text: String) =
        PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))))
}
