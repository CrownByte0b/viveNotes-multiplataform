package com.vivenotes.workspace

import com.vivenotes.data.PageLoad
import com.vivenotes.data.InkSource
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import com.vivenotes.richtext.TextSelection
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun debugLogTracksPageLoadSaveAndStorageFailureWithoutDocumentText() = runTest {
        val lines = mutableListOf<String>()
        val session = WorkspaceSession(store, backgroundScope, { "created $it" },
            log = DebugLog(enabled = true, output = lines::add))
        session.start()
        runCurrent()
        assertTrue(lines.any { it.contains("workspace ready: 1 notebooks") })
        assertTrue(lines.any { it.contains("page loaded (editable)") })

        store.saveFailure = IllegalStateException("disk unavailable")
        session.type("private document text")
        advanceTimeBy(WorkspaceSession.AUTOSAVE_DELAY_MILLIS)
        runCurrent()
        assertTrue(lines.any { it.contains("failed: IllegalStateException: disk unavailable") })
        assertTrue(lines.none { it.contains("private document text") })

        store.saveFailure = null
        session.flush()
        assertTrue(lines.any { it.contains("page saved") })
    }

    @Test
    fun debugLogReportsUnreadablePageCauseWithoutLoggingItsRawDocument() = runTest {
        val lines = mutableListOf<String>()
        store.storeBody(limits, PageLoad.Unreadable("private raw document", IllegalStateException("bad json")))
        val session = WorkspaceSession(store, backgroundScope, { "created $it" },
            log = DebugLog(enabled = true, output = lines::add))

        session.start()
        runCurrent()

        assertTrue(lines.any { it.contains("page decode failed: IllegalStateException: bad json") })
        assertTrue(lines.none { it.contains("private raw document") })
    }

    @Test
    fun newStoredPagesUseTheCurrentPaperDefault() = runTest {
        val session = WorkspaceSession(store, backgroundScope, { "created $it" },
            editorDefaults = EditorDefaults(paper = PaperSize.B5, ruleLines = RuleLines.Wide))
        session.start()
        runCurrent()
        session.addPage()
        runCurrent()
        assertEquals(PaperSize.B5, session.state.value!!.selectedPage!!.document.style.paper)
        assertEquals(PaperSize.B5, store.saves.last().second.style.paper)
        assertEquals(RuleLines.Wide, store.saves.last().second.style.ruleLines)

        session.update { it.setEditorDefaults(EditorDefaults(paper = PaperSize.A4)) }
        session.addPage()
        runCurrent()
        assertEquals(PaperSize.A4, session.state.value!!.selectedPage!!.document.style.paper)
    }

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
    fun inkLoadsWithTheOpenPageAndNeverSchedulesDocumentAutosave() = runTest {
        val reads = mutableListOf<String>()
        val source = object : InkSource {
            override suspend fun loadInk(pageId: String): InkPage {
                reads += pageId
                return InkPage(pageId, listOf(VisibleInkStroke("ink", "marker", 1, 6f,
                    0xFF000000.toInt(), false, listOf(InkSample(10f, 20f)))))
            }
        }
        val session = WorkspaceSession(store, backgroundScope, { "created $it" }, inkSource = source)
        session.start()
        runCurrent()
        assertEquals(listOf(limits), reads)
        assertEquals("ink", session.state.value!!.selectedPage!!.ink!!.strokes.single().id)

        session.update { it.selectPage(series) }
        runCurrent()
        assertEquals(listOf(limits, series), reads)
        assertNull(session.state.value!!.selectedSection!!.pages.first { it.id == limits }.ink)
        session.flush()
        assertTrue(store.saves.isEmpty())
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

    @Test
    fun renamesAreShownAtOnceAndStored() = runTest {
        val session = started()
        val notebook = session.state.value!!.selectedNotebookId

        session.rename(NavigationItem.Notebook(notebook), "  Analysis ")
        session.rename(NavigationItem.Section(sections[1]), "Integration")
        session.rename(NavigationItem.Page(series), "Power series")
        session.rename(NavigationItem.Page(limits), "Limits, again")
        session.rename(NavigationItem.Section(sections[0]), "   ")
        val shown = session.state.value!!
        assertEquals("Analysis", shown.selectedNotebook!!.name)
        assertEquals(listOf("Limits, again", "Power series"), shown.selectedSection!!.pages.map { it.title })
        runCurrent()

        assertEquals(
            listOf(notebook to "Analysis", sections[1] to "Integration", series to "Power series", limits to "Limits, again"),
            store.renames,
            "each rename stored once — the open page's through its title — and a blank one not at all",
        )
        val stored = session.state.value!!
        assertEquals(listOf("Chapter 1", "Integration"), stored.selectedNotebook!!.sections.map { it.name })
        assertEquals(listOf("Limits, again", "Power series"), stored.selectedSection!!.pages.map { it.title })
    }

    /** Android's order: the open page is saved first, since the delete decides from what is stored. */
    @Test
    fun deletingTheOpenPageSavesItThenDeletesItAndOpensTheNextPage() = runTest {
        val session = started()
        session.type("last words")

        session.delete(NavigationItem.Page(limits))
        runCurrent()

        assertEquals(listOf("save:$limits", "delete:$limits"), store.writes)
        assertEquals("last words", store.saves.single().second.text())
        val state = session.state.value!!
        assertEquals(series, state.selectedPageId)
        assertEquals("series", state.selectedPage!!.body)
        assertEquals(listOf(series), state.selectedSection!!.pages.map { it.id })
    }

    @Test
    fun aListReadBeforeTheDeleteLandsDoesNotBringThePageBack() = runTest {
        val session = started()
        val gate = CompletableDeferred<Unit>()
        store.deleteGate = gate

        session.delete(NavigationItem.Page(series))
        store.touchPage(series, "Series, still stored")
        runCurrent()
        assertEquals(listOf(limits), session.state.value!!.selectedSection!!.pages.map { it.id })

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(series), store.deletes)
        assertEquals(listOf(limits), session.state.value!!.selectedSection!!.pages.map { it.id })
    }

    @Test
    fun deletingTheOpenSectionOpensTheNextSectionsFirstPage() = runTest {
        val session = started()

        session.delete(NavigationItem.Section(sections[0]))
        runCurrent()

        assertEquals(listOf(sections[0]), store.deletes)
        val state = session.state.value!!
        assertEquals(listOf(sections[1]), state.selectedNotebook!!.sections.map { it.id })
        assertEquals(integrals, state.selectedPageId)
        assertEquals("integrals", state.selectedPage!!.body)
    }

    @Test
    fun deletingTheOpenNotebookOpensTheNextNotebook() = runTest {
        val cells = store.notebook("Biology", "Cells" to listOf("Meiosis" to typed("meiosis")))[0]
        val session = started()
        val calculus = session.state.value!!.selectedNotebookId

        session.delete(NavigationItem.Notebook(calculus))
        runCurrent()

        assertEquals(listOf(calculus), store.deletes)
        val state = session.state.value!!
        assertEquals(listOf("Biology"), state.notebooks.map { it.name })
        assertEquals(cells, state.selectedSectionId)
        assertEquals("meiosis", state.selectedPage!!.body)
    }

    @Test
    fun aFailedDeleteIsReported() = runTest {
        val session = started()
        store.deleteFailure = IllegalStateException("read-only disk")

        session.delete(NavigationItem.Page(series))
        runCurrent()

        val error = session.state.value!!.storageError
        assertNotNull(error)
        assertTrue("Series could not be deleted" in error && "read-only disk" in error, error)
    }

    @Test
    fun aNotebookFoldsAtOnceAndItsDisclosureIsStored() = runTest {
        val session = started()
        val calculus = session.state.value!!.notebooks.single().id

        session.setNotebookExpanded(calculus, false)
        assertEquals(false, session.state.value!!.notebooks.single().expanded, "shown before storage has it")
        runCurrent()

        assertEquals(listOf(calculus to false), store.expansions)
        assertEquals(false, session.state.value!!.notebooks.single().expanded)
        assertEquals(sections[0], session.state.value!!.selectedSectionId, "folding opens and closes nothing")
        assertEquals(limits, session.state.value!!.selectedPageId)

        session.setNotebookExpanded(calculus, false)
        runCurrent()
        assertEquals(1, store.expansions.size, "an unchanged disclosure is not written again")
    }

    /** Disclosure is a row field like any other: another writer's change arrives with the tree. */
    @Test
    fun aNotebookFoldedInStorageIsShownFolded() = runTest {
        val session = started()
        val calculus = session.state.value!!.notebooks.single().id

        store.setNotebookExpanded(calculus, false)
        runCurrent()

        assertEquals(false, session.state.value!!.notebooks.single().expanded)
    }

    /** Android's `createSection`: stored with a page of its own, then opened on that page. */
    @Test
    fun aNewSectionIsStoredWithAPageAndOpensOnIt() = runTest {
        val session = started()
        session.type("left behind")
        val calculus = session.state.value!!.notebooks.single().id

        session.createSection(calculus, "  Chapter 3 ")
        runCurrent()

        val state = session.state.value!!
        val section = state.notebooks.single().sections.last()
        assertEquals("Chapter 3", section.name)
        assertEquals(section.id, state.selectedSectionId)
        val page = store.pageIdsIn(section.id).single()
        assertEquals(page, state.selectedPageId)
        assertEquals(PageContent.Loaded, state.selectedPage!!.content)
        assertEquals("left behind", store.saves.single().second.text(), "the page left was saved")
    }

    /** Android's `createNotebook`: a blank name is the default, and its first section opens, empty. */
    @Test
    fun aNewNotebookIsStoredWithANewSectionAndOpensOnIt() = runTest {
        val session = started()

        session.createNotebook("   ")
        runCurrent()

        val state = session.state.value!!
        val notebook = state.notebooks.last()
        assertEquals(NEW_NOTEBOOK_NAME, notebook.name)
        assertEquals(listOf(NEW_SECTION_NAME), notebook.sections.map { it.name })
        assertEquals(notebook.id, state.selectedNotebookId)
        assertEquals(notebook.sections.single().id, state.selectedSectionId)
        assertEquals("", state.selectedPageId)
        assertTrue(store.pageIdsIn(notebook.sections.single().id).isEmpty())
    }

    /**
     * A dragged order goes to storage and comes back with the next read. The session changes nothing
     * first: the pane holds the dragged order meanwhile, and a read in between must not undo it.
     */
    @Test
    fun aDraggedOrderIsStoredAndArrivesFromStorage() = runTest {
        val session = started()
        val calculus = session.state.value!!.notebooks.single().id

        session.reorderPages(sections[0], listOf(series, limits))
        session.reorderSections(calculus, listOf(sections[1], sections[0]))
        assertEquals(listOf(limits, series), session.state.value!!.selectedSection!!.pages.map { it.id })
        runCurrent()

        val state = session.state.value!!
        assertEquals(listOf(series, limits), state.selectedSection!!.pages.map { it.id })
        assertEquals(listOf(sections[1], sections[0]), state.notebooks.single().sections.map { it.id })
        assertEquals(sections[0], state.selectedSectionId)
        assertEquals(listOf(sections[0] to listOf(series, limits), calculus to listOf(sections[1], sections[0])),
            store.reorders)
    }

    @Test
    fun pagesCarryWhenTheyLastChangedForThePageList() = runTest {
        val session = WorkspaceSession(store, backgroundScope, createdLabel = { "created $it" },
            updatedLabel = { "updated $it" })
        session.start()
        runCurrent()
        assertEquals(1_000L, session.state.value!!.selectedPage!!.updatedAt)
        assertEquals("updated 1000", session.state.value!!.selectedPage!!.updatedLabel)

        store.touchPage(series, "Series")
        runCurrent()

        val touched = session.state.value!!.selectedSection!!.pages.first { it.id == series }
        assertEquals(1_001L, touched.updatedAt)
        assertEquals("updated 1001", touched.updatedLabel)
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
