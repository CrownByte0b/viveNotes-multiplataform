package com.vivenotes.workspace

import com.vivenotes.data.NotesStore
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A [WorkspaceState] kept in step with a [NotesStore].
 *
 * The Android `NotesViewModel` arrangement, for the parts the desktop has so far. The notebook tree
 * and the open section's pages come from the store's flows. The open page's body is read when it is
 * opened, edited in memory, and written back [AUTOSAVE_DELAY_MILLIS] after the last change, before
 * another page opens, and on [flush]. Only the open page holds a body: a page is read afresh every
 * time it is opened, so a body stored by anything else is the one the next opening shows.
 *
 * Confined to the thread of [scope] — the UI thread in the app — so the UI's changes and the store's
 * arrivals never interleave. Store work runs one task at a time in the order it was asked for,
 * which is what lets a page read after it was saved see the save.
 */
class WorkspaceSession(
    private val store: NotesStore,
    private val scope: CoroutineScope,
    /** A page's `createdAt` as the page list and the page header show it. */
    private val createdLabel: (Long) -> String,
    private val autosaveDelayMillis: Long = AUTOSAVE_DELAY_MILLIS,
) {
    private val current = MutableStateFlow<WorkspaceState?>(null)

    /** Null until storage has been seeded and its notebooks read once. */
    val state: StateFlow<WorkspaceState?> = current.asStateFlow()

    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val openSection = MutableStateFlow<String?>(null)

    /** What storage holds for each open body: autosave writes only a page that differs from it. */
    private val stored = mutableMapOf<String, PageDoc>()
    private val loading = mutableSetOf<String>()
    private var autosave: Job? = null
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        check(!started) { "A session starts once" }
        started = true
        scope.launch { for (task in work) task() }
        scope.launch {
            // Nothing observes the tree until seeding is done: a seeded page's row is written before
            // its body, and a page opened in between would read as empty.
            attempt("Your notebooks could not be opened") { store.seedIfEmpty() }
            launch {
                store.observeTree()
                    .catch { fail("Your notebooks could not be read", it) }
                    .collect(::acceptTree)
            }
            openSection.filterNotNull()
                .flatMapLatest { sectionId -> store.observePages(sectionId).map { sectionId to it } }
                .catch { fail("This section's pages could not be read", it) }
                .collect { (sectionId, rows) -> acceptPages(sectionId, rows) }
        }
    }

    /**
     * Applies a change from the UI to the newest state — exactly once, on the session's thread.
     *
     * A transition rather than a finished state, because storage delivers pages and bodies between
     * frames: a state the UI computed from the last frame would quietly undo them.
     */
    fun update(transform: (WorkspaceState) -> WorkspaceState) {
        val before = current.value ?: return
        val after = transform(before)
        if (after == before) return
        current.value = after
        react(before, after)
    }

    /** Creates a page at the end of the open section, in storage first, and opens it. */
    fun addPage() {
        val sectionId = current.value?.selectedSectionId?.takeIf { it.isNotEmpty() } ?: return
        saveOpenPage()
        enqueue {
            val page = attempt("A new page could not be created") {
                store.pageById(store.createPage(sectionId))
            } ?: return@enqueue
            update { state -> state.withPageAdded(sectionId, page).selectPage(page.id) }
        }
    }

    /** Writes whatever is still waiting to be saved, and returns once storage has it. */
    suspend fun flush() {
        if (!started) return
        saveOpenPage()
        val drained = CompletableDeferred<Unit>()
        enqueue { drained.complete(Unit) }
        drained.await()
    }

    private fun react(before: WorkspaceState, after: WorkspaceState) {
        if (after.selectedSectionId != before.selectedSectionId) {
            openSection.value = after.selectedSectionId.ifEmpty { null }
        }
        val left = before.selectedPageId
        if (left.isNotEmpty() && left != after.selectedPageId) {
            // The departing page as it now stands: leaving it may have just discarded an empty box.
            autosave?.cancel()
            (after.page(left) ?: before.page(left))?.let(::save)
            unload(left)
        }
        val open = after.selectedPage ?: return
        if (open.id != before.selectedPageId) {
            if (open.content == PageContent.Unloaded) load(open.id)
            return
        }
        val previous = before.selectedPage ?: return
        if (open.title != previous.title) {
            enqueue { attempt("The page title could not be saved") { store.renamePage(open.id, open.title) } }
        }
        if (open.document !== previous.document) scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosave?.cancel()
        autosave = scope.launch {
            delay(autosaveDelayMillis)
            saveOpenPage()
        }
    }

    private fun saveOpenPage() {
        autosave?.cancel()
        current.value?.selectedPage?.let(::save)
    }

    private fun save(page: PageSummary) {
        if (!page.editable) return
        val doc = page.document
        val previous = stored[page.id]
        if (previous == doc) return
        stored[page.id] = doc
        enqueue {
            val saved = attempt("Changes to ${page.title.ifBlank { "Untitled page" }} could not be saved") {
                store.saveDoc(page.id, doc)
            }
            // Still unsaved, so the next edit or flush tries again — unless a newer save took over.
            if (saved == null && stored[page.id] === doc) {
                if (previous == null) stored.remove(page.id) else stored[page.id] = previous
            }
        }
    }

    private fun load(pageId: String) {
        if (!loading.add(pageId)) return
        enqueue {
            val load = attempt("This page could not be opened") { store.loadDoc(pageId) }
            loading.remove(pageId)
            if (load != null) accept(pageId, load)
        }
    }

    /** Puts a body just read into its page, if that page is still open and still waiting for it. */
    private fun accept(pageId: String, load: PageLoad) {
        val state = current.value ?: return
        val page = state.page(pageId) ?: return
        if (state.selectedPageId != pageId || page.content != PageContent.Unloaded) return
        current.value = state.updatePage(pageId) {
            when (load) {
                is PageLoad.Loaded -> {
                    stored[pageId] = load.doc
                    it.copy(document = load.doc, content = PageContent.Loaded)
                }
                is PageLoad.Unreadable -> it.copy(content = PageContent.Unreadable)
            }
        }
    }

    /** A page that is no longer open gives up its body; the next opening reads storage again. */
    private fun unload(pageId: String) {
        stored.remove(pageId)
        val state = current.value ?: return
        if (state.page(pageId)?.content == PageContent.Unloaded) return
        current.value = state.updatePage(pageId) {
            it.copy(document = UNREAD_DOCUMENT, content = PageContent.Unloaded)
        }
    }

    private fun acceptTree(tree: List<NotebookWithSections>) {
        val known = current.value?.notebooks.orEmpty().flatMap { it.sections }.associateBy { it.id }
        val notebooks = tree.map { entry ->
            NotebookSummary(
                id = entry.notebook.id,
                name = entry.notebook.name,
                colorArgb = entry.notebook.colorArgb,
                sections = entry.liveSections.map { section ->
                    SectionSummary(section.id, section.name, section.colorArgb, known[section.id]?.pages.orEmpty())
                },
            )
        }
        if (current.value == null) {
            // Where Android lands on a first launch: the first notebook's first section, whose first
            // page opens once its pages arrive.
            val notebook = notebooks.firstOrNull()
            val section = notebook?.sections?.firstOrNull()
            current.value = WorkspaceState(notebooks, notebook?.id.orEmpty(), section?.id.orEmpty(), "")
            openSection.value = section?.id
            return
        }
        update { state ->
            val next = state.copy(notebooks = notebooks)
            when {
                next.selectedSection != null -> next
                else -> notebooks.firstOrNull()?.sections?.firstOrNull()?.let { next.selectSection(it.id) }
                    ?: next.copy(selectedNotebookId = "", selectedSectionId = "", selectedPageId = "")
            }
        }
    }

    private fun acceptPages(sectionId: String, rows: List<PageEntity>) {
        update { state ->
            val open = state.selectedPageId
            val cached = state.sectionById(sectionId)?.pages.orEmpty().associateBy { it.id }
            state.withSectionPages(sectionId, rows.map { summaryOf(it, cached[it.id], open = it.id == open) })
        }
        val state = current.value ?: return
        if (state.selectedSectionId == sectionId && state.selectedPage == null) {
            rows.firstOrNull()?.let { first -> update { it.selectPage(first.id) } }
        }
    }

    /**
     * A page row as the workspace shows it. The open page keeps the title and preview it is being
     * edited to, which storage has not necessarily caught up with, and any page keeps its body.
     */
    private fun summaryOf(row: PageEntity, cached: PageSummary?, open: Boolean) = PageSummary(
        id = row.id,
        title = if (open && cached != null) cached.title else row.title,
        preview = if (cached?.editable == true) cached.preview else row.preview,
        createdLabel = createdLabel(row.createdAt),
        document = cached?.document ?: UNREAD_DOCUMENT,
        content = cached?.content ?: PageContent.Unloaded,
    )

    private fun WorkspaceState.withPageAdded(sectionId: String, row: PageEntity): WorkspaceState {
        val section = sectionById(sectionId) ?: return this
        if (section.pages.any { it.id == row.id }) return this
        return withSectionPages(sectionId, section.pages + summaryOf(row, cached = null, open = false))
    }

    private fun enqueue(task: suspend () -> Unit) {
        work.trySend(task)
    }

    /**
     * Runs one store call, turning a failure into [WorkspaceState.storageError] and a success into
     * clearing it. Null when the call failed.
     */
    private suspend fun <T> attempt(failure: String, call: suspend () -> T): T? = try {
        call().also {
            current.value?.takeIf { it.storageError != null }?.let { current.value = it.copy(storageError = null) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        fail(failure, error)
        null
    }

    private fun fail(failure: String, error: Throwable) {
        val message = "$failure. ${error.message ?: error::class.simpleName.orEmpty()}".trim()
        current.value = (current.value ?: WorkspaceState(emptyList(), "", "", "")).copy(storageError = message)
    }

    companion object {
        /** Android's `NotesViewModel.AUTOSAVE_DELAY_MS`: a burst of typing is one write. */
        const val AUTOSAVE_DELAY_MILLIS = 400L

        /** What an unopened page holds in place of its body — nothing that could be edited. */
        private val UNREAD_DOCUMENT = PageDoc(outlines = emptyList())
    }
}

private fun WorkspaceState.page(id: String): PageSummary? =
    notebooks.asSequence().flatMap { it.sections }.flatMap { it.pages }.firstOrNull { it.id == id }

private fun WorkspaceState.sectionById(id: String): SectionSummary? =
    notebooks.asSequence().flatMap { it.sections }.firstOrNull { it.id == id }

private fun WorkspaceState.withSectionPages(sectionId: String, pages: List<PageSummary>): WorkspaceState =
    copy(notebooks = notebooks.map { notebook ->
        notebook.copy(sections = notebook.sections.map { if (it.id == sectionId) it.copy(pages = pages) else it })
    })
