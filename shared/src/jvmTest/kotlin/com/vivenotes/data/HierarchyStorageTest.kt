package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notebook, section and page writes the workspace and the rail make, over real SQLite.
 *
 * Android covers these through its ViewModel; here they are held directly, since the desktop
 * reaches them through `WorkspaceSession` and the flows below are what it observes.
 */
class HierarchyStorageTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private var now = 1_000_000L

    @BeforeTest
    fun setUp() {
        db = NotesDatabase.inMemory()
        repository = NotesRepository(db, clock = { now })
    }

    @AfterTest
    fun tearDown() = db.close()

    /** Android's first launch exactly: one notebook, two sections, one empty Welcome page. */
    @Test
    fun aFirstLaunchSeedsTheStarterNotebookOnce() = runBlocking<Unit> {
        repository.seedIfEmpty()
        repository.seedIfEmpty()

        val tree = repository.observeTree().first()
        assertEquals(listOf("My Notebook"), tree.map { it.notebook.name })
        val sections = tree.single().liveSections
        assertEquals(listOf("Getting Started", "Ideas"), sections.map { it.name })
        val pages = repository.observePages(sections.first().id).first()
        assertEquals(listOf("Welcome"), pages.map { it.title })
        val welcome = repository.loadDoc(pages.single().id)
        assertIs<PageLoad.Loaded>(welcome)
        assertEquals(PageDoc.empty().outlines.map { it::class }, welcome.doc.outlines.map { it::class })
        assertTrue(repository.observePages(sections.last().id).first().isEmpty())
    }

    /**
     * The seeded notebook is replaceable packaging until someone uses it: `.vive` import and joining
     * an account may discard it only while this marker stands, so the first real edit removes it.
     */
    @Test
    fun theFirstEditMakesTheStarterNotebookTheOwners() = runBlocking<Unit> {
        repository.seedIfEmpty()
        val notebookId = repository.observeTree().first().single().notebook.id
        assertEquals(notebookId, marker())

        val welcome = repository.observePages(
            repository.observeTree().first().single().liveSections.first().id,
        ).first().single()
        repository.renamePage(welcome.id, "Mine now")

        assertNull(marker())
    }

    @Test
    fun renamesChangeTheNameAndTheModifiedTime() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        val pageId = repository.createPage(sectionId, "Page")
        now += 5_000

        repository.renameNotebook(notebookId, "Renamed notebook")
        repository.renameSection(sectionId, "Renamed section")
        repository.renamePage(pageId, "Renamed page")

        val notebook = db.notebookDao().byId(notebookId)!!
        val section = db.sectionDao().byId(sectionId)!!
        val page = repository.pageById(pageId)!!
        assertEquals("Renamed notebook" to now, notebook.name to notebook.updatedAt)
        assertEquals("Renamed section" to now, section.name to section.updatedAt)
        assertEquals("Renamed page" to now, page.title to page.updatedAt)
    }

    /** Opening a notebook in the rail is a scroll position, not an edit worth a network push. */
    @Test
    fun expandingANotebookIsStoredButNeverQueuedForSync() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("Notebook")
        db.syncDao().putState(SyncStateEntity(accountId = "account"))

        repository.setNotebookExpanded(notebookId, expanded = false)

        assertEquals(false, db.notebookDao().byId(notebookId)!!.expanded)
        assertEquals(emptyList(), db.syncDao().outbox(64).map { it.entityId })
        assertEquals(0L, db.long("SELECT applyingRemote FROM sync_state"), "the suppression leaked")
    }

    @Test
    fun closingShelvesANotebookAndReopeningReturnsItToTheRail() = runBlocking<Unit> {
        val kept = repository.createNotebook("Kept")
        val shelved = repository.createNotebook("Shelved")
        repository.createPage(repository.createSection(shelved, "Section"), "Page")

        repository.closeNotebook(shelved)

        assertEquals(listOf(kept), repository.observeTree().first().map { it.notebook.id })
        val shelf = repository.observeClosedNotebooks().first().single()
        assertEquals(shelved to (1 to 1), shelf.notebook.id to (shelf.sectionCount to shelf.pageCount))
        assertTrue(shelf.contentOnDevice)

        repository.reopenNotebook(shelved)

        assertEquals(listOf(kept, shelved), repository.observeTree().first().map { it.notebook.id })
        assertTrue(repository.observeClosedNotebooks().first().isEmpty())
    }

    @Test
    fun savingKeepsThePageListPreviewInStepWithTheBody() = runBlocking<Unit> {
        val pageId = repository.createPage(repository.createSection(repository.createNotebook("N"), "S"))
        val long = "x".repeat(200)

        repository.saveDoc(pageId, typed("", "  ", long, "later line"))

        assertEquals(long.take(140), repository.pageById(pageId)!!.preview)
    }

    /** An autosave of an unchanged body writes nothing — no row, no stamp, no checkpoint. */
    @Test
    fun savingTheStoredBodyAgainWritesNothing() = runBlocking<Unit> {
        val pageId = repository.createPage(repository.createSection(repository.createNotebook("N"), "S"))
        val doc = typed("same")
        repository.saveDoc(pageId, doc)
        val stamp = repository.pageById(pageId)!!.updatedAt
        val history = repository.revisionHistory(pageId)
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS

        repository.saveDoc(pageId, doc)

        assertEquals(stamp, repository.pageById(pageId)!!.updatedAt)
        assertEquals(history, repository.revisionHistory(pageId))
    }

    /**
     * The open page follows bodies written underneath it — by sync, or an import — but only its
     * own: saving a different page must not re-deliver this one's older body.
     */
    @Test
    fun aPagesBodyIsObservedAndOnlyItsOwnWritesReemit() = runBlocking<Unit> {
        val sectionId = repository.createSection(repository.createNotebook("N"), "S")
        val watched = repository.createPage(sectionId)
        val other = repository.createPage(sectionId)
        val emissions = Collections.synchronizedList(mutableListOf<PageLoad>())
        val collector = launch(Dispatchers.IO) { repository.observeDoc(watched).collect { emissions += it } }
        waitFor { emissions.size == 1 }

        repository.saveDoc(other, typed("elsewhere"))
        repository.saveDoc(watched, typed("here"))
        waitFor { emissions.size >= 2 }
        collector.cancel()

        assertEquals(2, emissions.size, "saving another page re-emitted this one: $emissions")
        val text = (emissions.last() as PageLoad.Loaded).doc.outlines.filterIsInstance<Outline.Text>().single()
        assertEquals("here", text.blocks.single().text)
    }

    /** Waits for [condition], then one more beat so that a spurious extra emission would show. */
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
        delay(100)
    }

    private suspend fun marker(): String? = db.localMetadataDao().value(NotesRepository.REPLACEABLE_STARTER_KEY)

    private fun typed(vararg lines: String) = PageDoc(
        outlines = listOf(Outline.Text(id = "text", blocks = lines.map(Block::of))),
    )
}
