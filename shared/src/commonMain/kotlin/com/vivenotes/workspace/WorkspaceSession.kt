package com.vivenotes.workspace

import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.InkSource
import com.vivenotes.data.NotebookImportResult
import com.vivenotes.data.NotesStore
import com.vivenotes.data.PageLoad
import com.vivenotes.data.PageRevisionLoad
import com.vivenotes.data.DeletedItem
import com.vivenotes.data.viveFileName
import com.vivenotes.richtext.TextSelection
import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
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
import kotlinx.coroutines.flow.first
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
    /** A page's `createdAt` as the page header shows it. */
    private val createdLabel: (Long) -> String,
    private val autosaveDelayMillis: Long = AUTOSAVE_DELAY_MILLIS,
    /** A page's `updatedAt` as the page list shows it, worked out when its row arrives. */
    private val updatedLabel: (Long) -> String = { "" },
    private val inkSource: InkSource? = store as? InkSource,
    private val editorDefaults: EditorDefaults = EditorDefaults(),
    private val log: DebugLog = DebugLog(),
) : NavigationActions {
    private val current = MutableStateFlow<WorkspaceState?>(null)

    /** Null until storage has been seeded and its notebooks read once. */
    val state: StateFlow<WorkspaceState?> = current.asStateFlow()

    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val openSection = MutableStateFlow<String?>(null)

    /** What storage holds for each open body: autosave writes only a page that differs from it. */
    private val stored = mutableMapOf<String, PageDoc>()
    private val loading = mutableSetOf<String>()

    /** Deleted here and perhaps not yet in storage: a list read meanwhile must not bring them back. */
    private val deleting = mutableSetOf<String>()
    private var autosave: Job? = null
    private var started = false

    /** A section just created, to open once the tree lists it: storage's flows trail its writes. */
    private var opening: String? = null

    /** The page an import opens, as its section and id, once that section's list shows it. */
    private var openingPage: Pair<String, String>? = null

    /** Imports finished so far. A page edit queued before one must not be written after it. */
    private var restores = 0

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        check(!started) { "A session starts once" }
        started = true
        log.event("session") { "opening workspace" }
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
    override fun addPage() {
        val sectionId = current.value?.selectedSectionId?.takeIf { it.isNotEmpty() } ?: return
        saveOpenPage()
        enqueue {
            val page = attempt("A new page could not be created") {
                val id = store.createPage(sectionId)
                val defaults = current.value?.editorDefaults ?: editorDefaults
                if (defaults.pageStyle() != com.vivenotes.model.PageStyle()) {
                    store.saveDoc(id, PageDoc.empty().copy(style = defaults.pageStyle()))
                }
                store.pageById(id)
            } ?: return@enqueue
            log.event("storage") { "page created" }
            update { state -> state.withPageAdded(sectionId, page).selectPage(page.id) }
        }
    }

    /**
     * Renames a notebook, section or page: shown at once, then stored. The open page's title is
     * already stored by [react] as it changes, like typing into the title.
     */
    override fun rename(item: NavigationItem, name: String) {
        val state = current.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || state.nameOf(item) == null || state.nameOf(item) == trimmed) return
        update { it.rename(item, trimmed) }
        if (item is NavigationItem.Page && item.id == state.selectedPageId) return
        enqueue {
            attempt("${trimmed} could not be renamed") {
                when (item) {
                    is NavigationItem.Notebook -> store.renameNotebook(item.id, trimmed)
                    is NavigationItem.Section -> store.renameSection(item.id, trimmed)
                    is NavigationItem.Page -> store.renamePage(item.id, trimmed)
                }
            }
        }
    }

    /**
     * Deletes a notebook, section or page with everything in it: gone from the panes at once, then
     * from storage. The open page is saved first, as Android does, because storage decides from
     * what it holds whether there was anything worth keeping.
     */
    override fun delete(item: NavigationItem) {
        val name = current.value?.nameOf(item) ?: return
        saveOpenPage()
        deleting += item.id
        update { it.delete(item) }
        enqueue {
            val deleted = attempt("${name.ifBlank { "Untitled page" }} could not be deleted") {
                when (item) {
                    is NavigationItem.Notebook -> store.deleteNotebook(item.id)
                    is NavigationItem.Section -> store.deleteSection(item.id)
                    is NavigationItem.Page -> store.deletePage(item.id)
                }
            }
            if (deleted != null) log.event("storage") { "${item::class.simpleName} deleted" }
            deleting -= item.id
            if (current.value?.filePane?.pane == FilePane.DeletedItems) refreshFilePane(FilePane.DeletedItems)
        }
    }

    /** Folds a notebook open or shut: shown at once, then stored on this device only. */
    override fun setNotebookExpanded(id: String, expanded: Boolean) {
        val notebook = current.value?.notebooks?.firstOrNull { it.id == id } ?: return
        if (notebook.expanded == expanded) return
        update { it.setNotebookExpanded(id, expanded) }
        enqueue { attempt("${notebook.name} could not be ${if (expanded) "expanded" else "collapsed"}") {
            store.setNotebookExpanded(id, expanded)
        } }
    }

    /**
     * Creates a notebook with a [NEW_SECTION_NAME] section in storage, then opens that section once
     * the tree lists it. Like Android, the new section starts without a page.
     */
    override fun createNotebook(name: String) {
        val named = notebookName(name)
        enqueue {
            val sectionId = attempt("$named could not be created") {
                store.createSection(store.createNotebook(named), NEW_SECTION_NAME)
            } ?: return@enqueue
            log.event("storage") { "notebook created" }
            openWhenListed(sectionId)
        }
    }

    /** Creates a section with one page in storage, then opens it once the tree lists it (Android). */
    override fun createSection(notebookId: String, name: String) {
        val named = sectionName(name)
        enqueue {
            val sectionId = attempt("$named could not be created") {
                store.createSection(notebookId, named).also { section ->
                    val id = store.createPage(section)
                    val defaults = current.value?.editorDefaults ?: editorDefaults
                    if (defaults.pageStyle() != com.vivenotes.model.PageStyle()) {
                        store.saveDoc(id, PageDoc.empty().copy(style = defaults.pageStyle()))
                    }
                }
            } ?: return@enqueue
            log.event("storage") { "section created" }
            openWhenListed(sectionId)
        }
    }

    /**
     * Stores a dragged order. The pane keeps showing that order until the tree agrees, so nothing
     * is changed here first — a tree read in between would only put the old order back meanwhile.
     */
    override fun reorderSections(notebookId: String, orderedIds: List<String>) {
        enqueue { attempt("The sections could not be reordered") { store.reorderSections(notebookId, orderedIds) } }
    }

    /** Stores a dragged page order; see [reorderSections]. */
    override fun reorderPages(sectionId: String, orderedIds: List<String>) {
        enqueue { attempt("The pages could not be reordered") { store.reorderPages(sectionId, orderedIds) } }
    }

    /** The File tab's `.vive` commands, over one window's file dialogs. */
    fun fileActions(files: NotebookFiles): FileActions = object : FileActions {
        override fun openPane(pane: FilePane) = this@WorkspaceSession.openFilePane(pane)
        override fun closePane() = update { it.copy(filePane = FilePaneState()) }
        override fun selectRevision(id: String) = this@WorkspaceSession.selectRevision(id)
        override fun restoreRevision() = this@WorkspaceSession.restoreSelectedRevision()
        override fun restoreDeletedItem(item: DeletedItem) = this@WorkspaceSession.restoreDeleted(item)
        override fun reopenNotebook(id: String) = this@WorkspaceSession.reopenNotebook(id)
        override fun closeNotebook() {
            val notebook = currentNotebook() ?: return
            saveOpenPage()
            enqueue {
                if (openPageHasUnsavedChanges()) return@enqueue
                attempt("${notebook.name} could not be closed") { store.closeNotebook(notebook.id) }
            }
        }
        override fun deleteNotebook() {
            val notebook = currentNotebook() ?: return
            saveOpenPage()
            enqueue {
                if (openPageHasUnsavedChanges()) return@enqueue
                delete(NavigationItem.Notebook(notebook.id))
            }
        }
        override fun exportNotebook() = exportNotebook(files)
        override fun importNotebook() = importNotebook(files)
        override fun dismissTransfer() = update { state ->
            if (state.notebookTransfer.running) state else state.copy(notebookTransfer = NotebookTransferState())
        }
    }

    private fun currentNotebook(): NotebookSummary? = current.value?.let { state ->
        state.notebooks.firstOrNull { notebook -> notebook.sections.any { it.id == state.selectedSectionId } }
    }

    private fun openFilePane(pane: FilePane) {
        if (pane == FilePane.VersionHistory && current.value?.selectedPage == null) return
        if (pane == FilePane.VersionHistory) saveOpenPage()
        update { it.copy(filePane = FilePaneState(pane = pane, loading = true)) }
        enqueue { refreshFilePane(pane) }
    }

    private suspend fun refreshFilePane(pane: FilePane) {
        if (current.value?.filePane?.pane != pane) return
        when (pane) {
            FilePane.VersionHistory -> {
                val pageId = current.value?.selectedPageId?.takeIf { it.isNotEmpty() } ?: return
                val rows = attempt("Version history could not be read") { store.revisionHistory(pageId) }
                update { state -> if (state.filePane.pane == pane && state.selectedPageId == pageId)
                    state.copy(filePane = state.filePane.copy(loading = false, revisionPageId = pageId,
                        revisions = rows.orEmpty(), dateLabels = rows.orEmpty().associate { it.createdAt to createdLabel(it.createdAt) },
                        error = if (rows == null) "Version history could not be read." else null))
                    else state }
            }
            FilePane.DeletedItems -> {
                val rows = attempt("Deleted items could not be read") { store.observeDeletedItems().first() }
                update { state -> if (state.filePane.pane == pane)
                    state.copy(filePane = state.filePane.copy(loading = false, deletedItems = rows.orEmpty(),
                        dateLabels = rows.orEmpty().associate { it.deletedAt to createdLabel(it.deletedAt) },
                        error = if (rows == null) "Deleted items could not be read." else null)) else state }
            }
            FilePane.ClosedNotebooks -> {
                val rows = attempt("Closed notebooks could not be read") { store.observeClosedNotebooks().first() }
                update { state -> if (state.filePane.pane == pane)
                    state.copy(filePane = state.filePane.copy(loading = false, closedNotebooks = rows.orEmpty(),
                        dateLabels = rows.orEmpty().mapNotNull { row -> row.notebook.closedAt?.let { it to createdLabel(it) } }.toMap(),
                        error = if (rows == null) "Closed notebooks could not be read." else null)) else state }
            }
        }
    }

    private fun selectRevision(id: String) {
        val pane = current.value?.filePane ?: return
        val pageId = pane.revisionPageId ?: return
        if (pane.pane != FilePane.VersionHistory || pane.revisions.none { it.id == id } || pane.busy) return
        update { it.copy(filePane = it.filePane.copy(selectedRevisionId = id, preview = null, loading = true,
            error = null, message = null)) }
        enqueue {
            val result = attempt("This version could not be read") { store.loadRevision(pageId, id) }
            if (result is PageRevisionLoad.Unreadable) log.failure("storage", "version decode", result.cause)
            update { state -> if (state.filePane.pane != FilePane.VersionHistory ||
                state.filePane.selectedRevisionId != id || state.selectedPageId != pageId) state else
                state.copy(filePane = state.filePane.copy(loading = false,
                    preview = (result as? PageRevisionLoad.Loaded)?.doc,
                    error = if (result is PageRevisionLoad.Loaded) null else "This version is unavailable or unreadable.")) }
        }
    }

    private fun restoreSelectedRevision() {
        val pane = current.value?.filePane ?: return
        val pageId = pane.revisionPageId ?: return
        val id = pane.selectedRevisionId ?: return
        if (pane.pane != FilePane.VersionHistory || pane.preview == null || pane.busy ||
            current.value?.selectedPageId != pageId) return
        saveOpenPage()
        update { it.copy(filePane = it.filePane.copy(busy = true, error = null, message = null)) }
        enqueue {
            if (openPageHasUnsavedChanges()) {
                update { state -> if (state.filePane.pane == FilePane.VersionHistory)
                    state.copy(filePane = state.filePane.copy(busy = false,
                        error = "The current page could not be saved, so this version was not restored.")) else state }
                return@enqueue
            }
            val result = attempt("This version could not be restored") { store.restoreRevision(pageId, id) }
            if (result is PageRevisionLoad.Loaded) {
                log.event("storage") { "page version restored" }
                restores++
                autosave?.cancel()
                stored.remove(pageId)
                current.value = current.value?.updatePage(pageId) { page ->
                    page.copy(document = UNREAD_DOCUMENT, ink = null, content = PageContent.Unloaded)
                }
                load(pageId)
            }
            val rows = attempt("Version history could not be read") { store.revisionHistory(pageId) }
            update { state -> if (state.filePane.pane == FilePane.VersionHistory)
                state.copy(filePane = state.filePane.copy(busy = false, loading = false,
                revisions = rows.orEmpty(), selectedRevisionId = null, preview = null,
                dateLabels = rows.orEmpty().associate { it.createdAt to createdLabel(it.createdAt) },
                message = if (result is PageRevisionLoad.Loaded) "Version restored. Your previous page is in history." else null,
                error = if (result is PageRevisionLoad.Loaded) null else "This version is unavailable or unreadable.")) else state }
        }
    }

    /** A failed queued save restores [stored] to its old value; destructive File actions stop here. */
    private fun openPageHasUnsavedChanges(): Boolean = current.value?.selectedPage?.let { page ->
        page.editable && page.document != stored[page.id]
    } ?: false

    private fun restoreDeleted(item: DeletedItem) {
        if (current.value?.filePane?.pane != FilePane.DeletedItems || current.value?.filePane?.busy == true) return
        update { it.copy(filePane = it.filePane.copy(busy = true, error = null, message = null)) }
        enqueue {
            val restored = attempt("${item.name} could not be restored") { store.restoreDeletedItem(item.key) }
            if (restored == true) log.event("storage") { "deleted item restored" }
            val rows = attempt("Deleted items could not be read") { store.observeDeletedItems().first() }
            update { state -> if (state.filePane.pane == FilePane.DeletedItems)
                state.copy(filePane = state.filePane.copy(busy = false, deletedItems = rows.orEmpty(),
                dateLabels = rows.orEmpty().associate { row -> row.deletedAt to createdLabel(row.deletedAt) },
                message = if (restored == true) "${item.name} was restored." else null,
                error = if (restored == true) null else "${item.name} is no longer available to restore.")) else state }
        }
    }

    private fun reopenNotebook(id: String) {
        val pane = current.value?.filePane ?: return
        if (pane.pane != FilePane.ClosedNotebooks || pane.busy ||
            pane.closedNotebooks.none { it.notebook.id == id && it.contentOnDevice }) return
        update { it.copy(filePane = it.filePane.copy(busy = true, error = null, message = null)) }
        enqueue {
            val reopened = attempt("This notebook could not be reopened") { store.reopenNotebook(id) }
            if (reopened != null) {
                log.event("storage") { "notebook reopened" }
                store.observeTree().first().firstOrNull { it.notebook.id == id }?.liveSections?.firstOrNull()?.let {
                    openWhenListed(it.id)
                }
            }
            val rows = attempt("Closed notebooks could not be read") { store.observeClosedNotebooks().first() }
            update { state -> if (state.filePane.pane == FilePane.ClosedNotebooks)
                state.copy(filePane = state.filePane.copy(busy = false, closedNotebooks = rows.orEmpty(),
                message = if (reopened != null) "Notebook reopened." else null,
                error = if (reopened != null) null else "This notebook could not be reopened.")) else state }
        }
    }

    /**
     * Android's `exportCurrentNotebook`: once the user has chosen where, the open page is saved and
     * the notebook holding the open section is written. The transfer runs in the store queue, so
     * the save lands first, and the dialog it shows keeps the workspace still meanwhile.
     */
    private fun exportNotebook(files: NotebookFiles) {
        val state = current.value ?: return
        if (state.notebookTransfer.running) return
        val notebook = state.notebooks.firstOrNull { entry ->
            entry.sections.any { it.id == state.selectedSectionId }
        } ?: return
        scope.launch {
            val destination = files.chooseExportDestination(viveFileName(notebook.name)) ?: return@launch
            if (!beginTransfer()) return@launch
            log.event("transfer") { "export started" }
            saveOpenPage()
            enqueue {
                val outcome = try {
                    val result = files.export(notebook.id, destination)
                    log.event("transfer") { "export complete" }
                    NotebookTransferState(message = "${result.notebookName} was exported as a .vive notebook.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.failure("transfer", "export", failure)
                    NotebookTransferState(error = failure.message ?: "The notebook could not be exported.")
                }
                update { it.copy(notebookTransfer = outcome) }
            }
        }
    }

    /**
     * Android's `importNotebook`: the open page is saved, the chosen file restored, and what it held
     * opened — the notebook's first section at its first page.
     */
    private fun importNotebook(files: NotebookFiles) {
        if (current.value?.notebookTransfer?.running != false) return
        scope.launch {
            val source = files.chooseImportSource() ?: return@launch
            if (!beginTransfer()) return@launch
            log.event("transfer") { "import started" }
            saveOpenPage()
            enqueue {
                val result = try {
                    files.import(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.failure("transfer", "import", failure)
                    update {
                        it.copy(notebookTransfer = NotebookTransferState(
                            error = failure.message ?: "The notebook could not be imported.",
                        ))
                    }
                    return@enqueue
                }
                openImported(result)
                log.event("transfer") { "import complete" }
                val message = when {
                    result.created -> "${result.notebookName} was imported."
                    result.restored -> "${result.notebookName} was restored from the .vive notebook."
                    else -> "${result.notebookName} was updated from the .vive notebook."
                }
                update { it.copy(notebookTransfer = NotebookTransferState(message = message)) }
            }
        }
    }

    /** Marks a transfer as under way, unless one already is. */
    private fun beginTransfer(): Boolean {
        val state = current.value ?: return false
        if (state.notebookTransfer.running) return false
        update { it.copy(notebookTransfer = NotebookTransferState(running = true)) }
        return true
    }

    /**
     * Opens what an import restored, without writing the page that was open over it.
     *
     * That page was saved before the import began, and the archive may since have replaced it, so
     * its body is dropped unsaved and read again when it opens — Android opens the imported page
     * without persisting the editor it leaves. Structural undo goes too: a snapshot taken before the
     * import would put the replaced document back.
     */
    private suspend fun openImported(result: NotebookImportResult) {
        restores++
        autosave?.cancel()
        val before = current.value ?: return
        val open = before.selectedPageId
        update { state ->
            val forgotten = if (open.isEmpty()) state else state.updatePage(open) {
                it.copy(document = UNREAD_DOCUMENT, ink = null, content = PageContent.Unloaded)
            }
            forgotten.copy(
                selectedPageId = "",
                editorSelection = TextSelection(0),
                typingMarks = emptySet(),
                editorComposition = null,
                focusedTextOutlineId = null,
                selectedTextOutlineIds = emptySet(),
                selectedObjectIds = emptySet(),
                structuralUndo = emptyList(),
                structuralRedo = emptyList(),
            )
        }
        val sectionId = result.firstSectionId ?: before.selectedSectionId.takeIf { it.isNotEmpty() } ?: return
        // The list held for that section predates the import, and an open page keeps the title it
        // lists; storage's rows as they stand now replace it before anything opens.
        attempt("This section's pages could not be read") { store.observePages(sectionId).first() }?.let { rows ->
            update { state ->
                state.withSectionPages(sectionId, rows.filterNot { it.id in deleting }
                    .map { summaryOf(it, cached = null, open = false) })
            }
        }
        val pageId = if (result.firstSectionId != null) result.firstPageId else open.takeIf { it.isNotEmpty() }
        openingPage = pageId?.let { sectionId to it }
        openWhenListed(sectionId)
    }

    /** Writes whatever is still waiting to be saved, and returns once storage has it. */
    suspend fun flush() {
        if (!started) return
        log.event("session") { "flushing pending changes" }
        saveOpenPage()
        val drained = CompletableDeferred<Unit>()
        enqueue { drained.complete(Unit) }
        drained.await()
        log.event("session") { "flush complete" }
    }

    /** Reloads an open page after a remote sync commit, preserving edits made during the reload. */
    fun refreshOpenPageFromStorage() {
        val page = current.value?.selectedPage ?: return
        if (!page.editable || openPageHasUnsavedChanges()) return
        val pageId = page.id
        val shown = page.document
        enqueue {
            val loaded = attempt("This page could not be refreshed") { store.loadDoc(pageId) }
            val open = current.value?.selectedPage
            if (loaded is PageLoad.Loaded && open?.id == pageId &&
                open.document == shown && !openPageHasUnsavedChanges()) {
                stored[pageId] = loaded.doc
                current.value = current.value?.updatePage(pageId) {
                    it.copy(document = loaded.doc, content = PageContent.Loaded)
                }
                if (inkSource != null) {
                    attempt("This page's ink could not be read") { inkSource.loadInk(pageId) }
                        ?.let { acceptInk(pageId, it) }
                }
            }
        }
    }

    private fun react(before: WorkspaceState, after: WorkspaceState) {
        if (after.selectedSectionId != before.selectedSectionId) {
            openSection.value = after.selectedSectionId.ifEmpty { null }
        }
        val left = before.selectedPageId
        if (left.isNotEmpty() && left != after.selectedPageId) {
            log.event("session") { "page changed" }
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
            val generation = restores
            enqueue {
                if (generation != restores) return@enqueue
                attempt("The page title could not be saved") { store.renamePage(open.id, open.title) }
            }
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
        val generation = restores
        enqueue {
            // An import finished since this was asked for: the archive replaced the page, and this
            // edit — made while the import ran — must not be written over it.
            if (generation != restores) return@enqueue
            log.event("storage") { "saving page" }
            val saved = attempt("Changes to ${page.title.ifBlank { "Untitled page" }} could not be saved") {
                store.saveDoc(page.id, doc)
            }
            if (saved != null) log.event("storage") { "page saved" }
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
            if (load != null) {
                log.event("storage") { "page loaded (${if (load is PageLoad.Loaded) "editable" else "unreadable"})" }
                if (load is PageLoad.Unreadable) log.failure("storage", "page decode", load.cause)
                accept(pageId, load)
                if (load is PageLoad.Loaded && inkSource != null) {
                    attempt("This page's ink could not be read") { inkSource.loadInk(pageId) }
                        ?.let { acceptInk(pageId, it) }
                }
            }
        }
    }

    private fun acceptInk(pageId: String, ink: InkPage) {
        val state = current.value ?: return
        if (ink.pageId != pageId || state.selectedPageId != pageId || state.selectedPage?.editable != true) return
        current.value = state.updatePage(pageId) { it.copy(ink = ink) }
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
            it.copy(document = UNREAD_DOCUMENT, ink = null, content = PageContent.Unloaded)
        }
    }

    private fun acceptTree(tree: List<NotebookWithSections>) {
        val known = current.value?.notebooks.orEmpty().flatMap { it.sections }.associateBy { it.id }
        val notebooks = tree.filterNot { it.notebook.id in deleting }.map { entry ->
            NotebookSummary(
                id = entry.notebook.id,
                name = entry.notebook.name,
                colorArgb = entry.notebook.colorArgb,
                sections = entry.liveSections.filterNot { it.id in deleting }.map { section ->
                    SectionSummary(section.id, section.name, section.colorArgb, known[section.id]?.pages.orEmpty())
                },
                expanded = entry.notebook.expanded,
            )
        }
        if (current.value == null) {
            log.event("session") { "workspace ready: ${notebooks.size} notebooks" }
            // Where Android lands on a first launch: the first notebook's first section, whose first
            // page opens once its pages arrive.
            val notebook = notebooks.firstOrNull()
            val section = notebook?.sections?.firstOrNull()
            current.value = WorkspaceState(notebooks, notebook?.id.orEmpty(), section?.id.orEmpty(), "",
                editorDefaults = editorDefaults)
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
        openPendingSection()
    }

    private fun openWhenListed(sectionId: String) {
        opening = sectionId
        // The tree may already have arrived while the store call was finishing.
        openPendingSection()
    }

    private fun openPendingSection() {
        val id = opening ?: return
        if (current.value?.notebooks?.none { notebook -> notebook.sections.any { it.id == id } } != false) return
        opening = null
        // With a page to open, none is open until the section's list shows that page.
        update { state -> state.selectSection(id).let { if (openingPage != null) it.copy(selectedPageId = "") else it } }
        openPendingPage()
    }

    /**
     * Opens [openingPage] once the open section lists it. False while it waits for that list; a
     * wait the user has left for another section is given up.
     */
    private fun openPendingPage(): Boolean {
        val (sectionId, pageId) = openingPage ?: return true
        if (opening != null) return false
        val state = current.value ?: return false
        if (state.selectedSectionId != sectionId) {
            openingPage = null
            return true
        }
        if (state.selectedSection?.pages?.none { it.id == pageId } != false) return false
        openingPage = null
        update { it.selectPage(pageId) }
        return true
    }

    private fun acceptPages(sectionId: String, all: List<PageEntity>) {
        val rows = all.filterNot { it.id in deleting }
        update { state ->
            val open = state.selectedPageId
            val cached = state.sectionById(sectionId)?.pages.orEmpty().associateBy { it.id }
            state.withSectionPages(sectionId, rows.map { summaryOf(it, cached[it.id], open = it.id == open) })
        }
        if (!openPendingPage()) return
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
        ink = cached?.ink,
        content = cached?.content ?: PageContent.Unloaded,
        updatedAt = row.updatedAt,
        updatedLabel = updatedLabel(row.updatedAt),
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
        log.failure("storage", "store operation", error)
        val message = "$failure. ${error.message ?: error::class.simpleName.orEmpty()}".trim()
        current.value = (current.value ?: WorkspaceState(emptyList(), "", "", "",
            editorDefaults = editorDefaults)).copy(storageError = message)
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
