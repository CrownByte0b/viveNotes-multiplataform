package com.vivenotes.workspace

import com.vivenotes.localization.Strings
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
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
    private val shapeSettings: ShapeToolSettings = ShapeToolSettings(),
    private val log: DebugLog = DebugLog(),
    private val inkSettings: InkSettings = InkSettings(),
    /**
     * Android's `SyncAccounts.maySeedStarter`: false while this installation is joining an account it
     * has not pulled yet, which must not grow a second starter notebook nobody created. Asked once,
     * before the first read; null on a build with no account feature.
     */
    private val maySeedStarter: (suspend () -> Boolean)? = null,
    /**
     * Android's `SyncAccounts.remoteInk`: the pages sync has written ink into, by generation, so the
     * open page can take strokes another device drew. Null on a build with no account feature.
     */
    private val remoteInk: StateFlow<Map<String, Long>>? = null,
    /** Moving closed notebooks to the cloud and back; null when there is no account feature. */
    private val notebookCloud: NotebookCloud? = null,
) : NavigationActions {
    private val current = MutableStateFlow<WorkspaceState?>(null)

    /** Null until storage has been seeded and its notebooks read once. */
    val state: StateFlow<WorkspaceState?> = current.asStateFlow()

    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val openSection = MutableStateFlow<String?>(null)

    /** What storage holds for each open body: autosave writes only a page that differs from it. */
    private val stored = mutableMapOf<String, PageDoc>()
    private val loading = mutableMapOf<String, Any>()
    private var bodyObservation: Job? = null
    private var bodyObservationPage: String? = null
    private var bodyObservationToken: Any? = null

    /** Deleted here and perhaps not yet in storage: a list read meanwhile must not bring them back. */
    private val deleting = mutableSetOf<String>()
    private var autosave: Job? = null
    private var started = false
    private var inkSaveQueued = false

    /** A section just created, to open once the tree lists it: storage's flows trail its writes. */
    private var opening: String? = null

    /** The page an import opens, as its section and id, once that section's list shows it. */
    private var openingPage: Pair<String, String>? = null

    /** Imports finished so far. A page edit queued before one must not be written after it. */
    private var restores = 0

    /**
     * Bodies sync replaced underneath an open page, per page. A save queued before the replacement
     * was made against the older body and is dropped: the server's version wins, as on Android.
     */
    private val remoteReplacements = mutableMapOf<String, Int>()

    /**
     * For each open page, the bodies storage holds or held because of this session: the one it read
     * when the page opened and the ones it has written since. Storage reports each of them back, the
     * first possibly late — after typing has moved on — so a body that comes back is this session's
     * own unless it is none of these.
     */
    private val ownBodies = mutableMapOf<String, ArrayDeque<PageDoc>>()

    private fun rememberOwnBody(pageId: String, doc: PageDoc) {
        val bodies = ownBodies.getOrPut(pageId) { ArrayDeque() }
        if (doc in bodies) return
        bodies.addLast(doc)
        while (bodies.size > OWN_BODIES_KEPT) bodies.removeFirst()
    }

    /**
     * The [remoteInk] generation the open page's strokes were read at. Taken before the read, so a
     * pull committing during it is absorbed by the next signal rather than counted as shown.
     */
    private var absorbedInkGeneration = 0L

    /** Remote ink that arrived while the page had ink of its own in flight or selected. */
    private var deferredInkAbsorption = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        check(!started) { "A session starts once" }
        started = true
        log.event("session") { "opening workspace" }
        scope.launch { for (task in work) task() }
        scope.launch {
            // Nothing observes the tree until seeding is done: a seeded page's row is written before
            // its body, and a page opened in between would read as empty.
            attempt(Strings.your_notebooks_could_not_be_opened) {
                // Evaluated once rather than watched, as on Android: a device registered but not yet
                // pulled simply does not seed on this launch; waiting would leave it on a spinner.
                if (maySeedStarter?.invoke() != false) store.seedIfEmpty()
            }
            launch {
                store.observeTree()
                    .catch { fail(Strings.your_notebooks_could_not_be_read, it) }
                    .collect(::acceptTree)
            }
            // The page list already follows sync, so a pulled preview could say "xw" while the open
            // canvas stayed blank. The open body is observed on its own and taken over only when it
            // is not this session's own write coming back.
            // The same for the page's ink, which storage cannot report without also reporting every
            // stroke drawn here: sync says which pages it wrote ink into instead.
            remoteInk?.let { signal ->
                launch {
                    signal.collect { pages ->
                        val pageId = current.value?.selectedPageId?.takeIf { it.isNotEmpty() } ?: return@collect
                        absorbRemoteInk(pageId, pages[pageId] ?: 0L)
                    }
                }
            }
            openSection.filterNotNull()
                .flatMapLatest { sectionId -> store.observePages(sectionId).map { sectionId to it } }
                .catch { fail(Strings.this_section_s_pages_could_not_be_read, it) }
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
        if (before.selectedPageId == after.selectedPageId) {
            val oldObjects = before.selectedPage?.document?.outlines.orEmpty().filter { it.isPrimeObject() }.associateBy { it.id }
            val newObjects = after.selectedPage?.document?.outlines.orEmpty().filter { it.isPrimeObject() }
            val oldShapes = oldObjects.values.filterIsInstance<com.vivenotes.model.Outline.Shape>().map { it.id }.toSet()
            after.selectedPage?.document?.outlines.orEmpty()
                .filterIsInstance<com.vivenotes.model.Outline.Shape>()
                .filter { it.id !in oldShapes }
                .forEach { shape -> log.event("shape") { "added ${shape.kind.name}" } }
            val removed = oldObjects.keys - newObjects.map { it.id }.toSet()
            if (removed.isNotEmpty()) log.event("objects") { "deleted ${removed.size} objects" }
            val lockChanges = newObjects.filter { it.id in oldObjects && oldObjects[it.id]?.lockGroup != it.lockGroup }
            if (lockChanges.isNotEmpty()) log.event("objects") {
                "${if (lockChanges.all { it.lockGroup == null }) "unlocked" else "locked"} ${lockChanges.size} objects"
            }
            val backgroundChanges = newObjects.filterIsInstance<com.vivenotes.model.Outline.Image>().filter { picture ->
                (oldObjects[picture.id] as? com.vivenotes.model.Outline.Image)?.let { it.backgroundArgb != picture.backgroundArgb } == true
            }
            if (backgroundChanges.isNotEmpty()) log.event("objects") {
                "${if (backgroundChanges.all { it.backgroundArgb == null }) "cleared" else "set"} background of ${backgroundChanges.size} pictures"
            }
        }
        if (before.selectedObjectIds != after.selectedObjectIds) log.event("objects") {
            if (after.selectedObjectIds.isEmpty()) "selection cleared" else "selected ${after.selectedObjectIds.size} objects"
        }
        if (before.canvasClipboard != after.canvasClipboard) log.event("objects") {
            "copied ${after.canvasClipboard.outlines.size} objects"
        }
        if (before.canvasClipboard.ink !== after.canvasClipboard.ink && after.canvasClipboard.ink != null)
            log.event("ink") { "selection copied" }
        if (before.inkTool != after.inkTool) log.event("ink") { "tool ${after.inkTool?.name ?: "Select"}" }
        if (before.eraserMode != after.eraserMode) log.event("ink") { "eraser mode ${after.eraserMode.name}" }
        if (before.drawPlacement != after.drawPlacement) log.event("draw") { "tool ${after.drawPlacement?.name ?: "Select"}" }
        if (before.textToolArmed != after.textToolArmed) log.event("text") {
            "text box tool ${if (after.textToolArmed) "armed" else "put down"}"
        }
        if (before.activePenIndex != after.activePenIndex) log.event("ink") { "pen preset ${after.activePenIndex + 1}" }
        if (before.rulerVisible != after.rulerVisible) log.event("ink") { "ruler ${if (after.rulerVisible) "shown" else "hidden"}" }
        if (before.inkSettings != after.inkSettings) log.event("ink") { "drawing preferences changed" }
        if (before.selectedInk !== after.selectedInk) log.event("ink") {
            if (after.selectedInk == null) "selection cleared" else "ink selected"
        }
        react(before, after)
    }

    /** Creates a page at the end of the open section, in storage first, and opens it. */
    override fun addPage() {
        val sectionId = current.value?.selectedSectionId?.takeIf { it.isNotEmpty() } ?: return
        saveOpenPage()
        enqueue {
            val page = attempt(Strings.a_new_page_could_not_be_created) {
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

    /** Search can reach a page whose section's page list has not been loaded into the UI yet. */
    fun openSearchPage(sectionId: String, pageId: String) {
        if (current.value?.selectedPageId == pageId) return
        openingPage = sectionId to pageId
        update { it.selectSection(sectionId).copy(selectedPageId = "") }
        openPendingPage()
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
            attempt(Strings.value_could_not_be_renamed(trimmed)) {
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
            val deleted = attempt(Strings.value_could_not_be_deleted(name.ifBlank { Strings.untitled_page })) {
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
        enqueue { attempt(if (expanded) Strings.expand_notebook_failed(notebook.name) else Strings.collapse_notebook_failed(notebook.name)) {
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
            val sectionId = attempt(Strings.value_could_not_be_created(named)) {
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
            val sectionId = attempt(Strings.value_could_not_be_created(named)) {
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
        enqueue { attempt(Strings.the_sections_could_not_be_reordered) { store.reorderSections(notebookId, orderedIds) } }
    }

    /** Stores a dragged page order; see [reorderSections]. */
    override fun reorderPages(sectionId: String, orderedIds: List<String>) {
        enqueue { attempt(Strings.the_pages_could_not_be_reordered) { store.reorderPages(sectionId, orderedIds) } }
    }

    /** The File tab's `.vive` commands, over one window's file dialogs. */
    fun fileActions(files: NotebookFiles, exporter: com.vivenotes.pdf.PdfExportService? = null): FileActions = object : FileActions {
        override val pdfExporter = exporter
        override suspend fun preparePdfExport() {
            flush()
            if (openPageHasUnsavedChanges()) {
                log.event("pdf") { "export stopped: changes could not be saved" }
                throw com.vivenotes.pdf.PdfExportException(Strings.changes_could_not_be_saved_pdf_export_was_stopped)
            }
        }
        override fun openPane(pane: FilePane) = this@WorkspaceSession.openFilePane(pane)
        override fun closePane() = update { it.copy(filePane = FilePaneState()) }
        override fun selectRevision(id: String) = this@WorkspaceSession.selectRevision(id)
        override fun restoreRevision() = this@WorkspaceSession.restoreSelectedRevision()
        override fun restoreDeletedItem(item: DeletedItem) = this@WorkspaceSession.restoreDeleted(item)
        override fun reopenNotebook(id: String) = this@WorkspaceSession.reopenNotebook(id)
        override val cloudConnected: StateFlow<Boolean>? get() = notebookCloud?.connected
        override fun moveNotebookToCloud(id: String) = runCloudArchive(id, move = true)
        override fun bringNotebookBack(id: String) = runCloudArchive(id, move = false)
        override fun closeNotebook() {
            val notebook = currentNotebook() ?: return
            saveOpenPage()
            enqueue {
                if (openPageHasUnsavedChanges()) return@enqueue
                attempt(Strings.value_could_not_be_closed(notebook.name)) { store.closeNotebook(notebook.id) }
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
                val rows = attempt(Strings.version_history_could_not_be_read) { store.revisionHistory(pageId) }
                update { state -> if (state.filePane.pane == pane && state.selectedPageId == pageId)
                    state.copy(filePane = state.filePane.copy(loading = false, revisionPageId = pageId,
                        revisions = rows.orEmpty(), dateLabels = rows.orEmpty().associate { it.createdAt to createdLabel(it.createdAt) },
                        error = if (rows == null) Strings.version_history_could_not_be_read_fabb14 else null))
                    else state }
            }
            FilePane.DeletedItems -> {
                val rows = attempt(Strings.deleted_items_could_not_be_read) { store.observeDeletedItems().first() }
                update { state -> if (state.filePane.pane == pane)
                    state.copy(filePane = state.filePane.copy(loading = false, deletedItems = rows.orEmpty(),
                        dateLabels = rows.orEmpty().associate { it.deletedAt to createdLabel(it.deletedAt) },
                        error = if (rows == null) Strings.deleted_items_could_not_be_read_5eebcd else null)) else state }
            }
            FilePane.ClosedNotebooks -> {
                val rows = attempt(Strings.closed_notebooks_could_not_be_read) { store.observeClosedNotebooks().first() }
                update { state -> if (state.filePane.pane == pane)
                    state.copy(filePane = state.filePane.copy(loading = false, closedNotebooks = rows.orEmpty(),
                        error = if (rows == null) Strings.closed_notebooks_could_not_be_read_59b638 else null)) else state }
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
            val result = attempt(Strings.this_version_could_not_be_read) { store.loadRevision(pageId, id) }
            if (result is PageRevisionLoad.Unreadable) log.failure("storage", "version decode", result.cause)
            update { state -> if (state.filePane.pane != FilePane.VersionHistory ||
                state.filePane.selectedRevisionId != id || state.selectedPageId != pageId) state else
                state.copy(filePane = state.filePane.copy(loading = false,
                    preview = (result as? PageRevisionLoad.Loaded)?.doc,
                    error = if (result is PageRevisionLoad.Loaded) null else Strings.this_version_is_unavailable_or_unreadable)) }
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
                        error = Strings.the_current_page_could_not_be_saved_so_this_version_was)) else state }
                return@enqueue
            }
            val result = attempt(Strings.this_version_could_not_be_restored) { store.restoreRevision(pageId, id) }
            if (result is PageRevisionLoad.Loaded) {
                log.event("storage") { "page version restored" }
                restores++
                current.value = current.value?.let { state -> state.copy(
                    pendingInkEdits = state.pendingInkEdits.filterNot { it.pageId == pageId }) }
                autosave?.cancel()
                stored.remove(pageId)
                current.value = current.value?.updatePage(pageId) { page ->
                    page.copy(document = UNREAD_DOCUMENT, ink = null, content = PageContent.Unloaded)
                }
                load(pageId)
            }
            val rows = attempt(Strings.version_history_could_not_be_read) { store.revisionHistory(pageId) }
            update { state -> if (state.filePane.pane == FilePane.VersionHistory)
                state.copy(filePane = state.filePane.copy(busy = false, loading = false,
                revisions = rows.orEmpty(), selectedRevisionId = null, preview = null,
                dateLabels = rows.orEmpty().associate { it.createdAt to createdLabel(it.createdAt) },
                message = if (result is PageRevisionLoad.Loaded) Strings.version_restored_your_previous_page_is_in_history else null,
                error = if (result is PageRevisionLoad.Loaded) null else Strings.this_version_is_unavailable_or_unreadable)) else state }
        }
    }

    /** A failed queued save restores [stored] to its old value; destructive File actions stop here. */
    private fun openPageHasUnsavedChanges(): Boolean =
        !current.value?.pendingInkEdits.isNullOrEmpty() || (current.value?.selectedPage?.let { page ->
            page.editable && page.document != stored[page.id]
        } ?: false)

    private fun restoreDeleted(item: DeletedItem) {
        if (current.value?.filePane?.pane != FilePane.DeletedItems || current.value?.filePane?.busy == true) return
        update { it.copy(filePane = it.filePane.copy(busy = true, error = null, message = null)) }
        enqueue {
            val restored = attempt(Strings.value_could_not_be_restored(item.name)) { store.restoreDeletedItem(item.key) }
            if (restored == true) log.event("storage") { "deleted item restored" }
            val rows = attempt(Strings.deleted_items_could_not_be_read) { store.observeDeletedItems().first() }
            update { state -> if (state.filePane.pane == FilePane.DeletedItems)
                state.copy(filePane = state.filePane.copy(busy = false, deletedItems = rows.orEmpty(),
                dateLabels = rows.orEmpty().associate { row -> row.deletedAt to createdLabel(row.deletedAt) },
                message = if (restored == true) Strings.value_was_restored(item.name) else null,
                error = if (restored == true) null else Strings.value_is_no_longer_available_to_restore(item.name))) else state }
        }
    }

    private fun reopenNotebook(id: String) {
        val pane = current.value?.filePane ?: return
        if (pane.pane != FilePane.ClosedNotebooks || pane.busy ||
            pane.closedNotebooks.none { it.notebook.id == id && it.contentOnDevice }) return
        update { it.copy(filePane = it.filePane.copy(busy = true, error = null, message = null)) }
        enqueue {
            val reopened = attempt(Strings.this_notebook_could_not_be_reopened) { store.reopenNotebook(id) }
            if (reopened != null) {
                log.event("storage") { "notebook reopened" }
                store.observeTree().first().firstOrNull { it.notebook.id == id }?.liveSections?.firstOrNull()?.let {
                    openWhenListed(it.id)
                }
            }
            // Straight back to the workspace, as Android's shelf does: the notebook is in the
            // navigation now with its first section open. A failure keeps the screen and says so.
            if (reopened != null) {
                update { state -> if (state.filePane.pane == FilePane.ClosedNotebooks) state.copy(filePane = FilePaneState()) else state }
                return@enqueue
            }
            val rows = attempt(Strings.closed_notebooks_could_not_be_read) { store.observeClosedNotebooks().first() }
            update { state -> if (state.filePane.pane == FilePane.ClosedNotebooks)
                state.copy(filePane = state.filePane.copy(busy = false, closedNotebooks = rows.orEmpty(),
                error = Strings.this_notebook_could_not_be_reopened_6c30bb)) else state }
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
                if (!current.value?.pendingInkEdits.isNullOrEmpty()) {
                    update { it.copy(notebookTransfer = NotebookTransferState(error = Strings.ink_changes_could_not_be_saved_export_was_stopped)) }
                    return@enqueue
                }
                val outcome = try {
                    val result = files.export(notebook.id, destination)
                    log.event("transfer") { "export complete" }
                    NotebookTransferState(message = Strings.value_was_exported_as_a_vive_notebook(result.notebookName))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.failure("transfer", "export", failure)
                    NotebookTransferState(error = failure.message ?: Strings.the_notebook_could_not_be_exported)
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
                if (!current.value?.pendingInkEdits.isNullOrEmpty()) {
                    update { it.copy(notebookTransfer = NotebookTransferState(error = Strings.ink_changes_could_not_be_saved_import_was_stopped)) }
                    return@enqueue
                }
                val result = try {
                    files.import(source)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.failure("transfer", "import", failure)
                    update {
                        it.copy(notebookTransfer = NotebookTransferState(
                            error = failure.message ?: Strings.the_notebook_could_not_be_imported,
                        ))
                    }
                    return@enqueue
                }
                openImported(result)
                log.event("transfer") { "import complete" }
                val message = when {
                    result.created -> Strings.value_was_imported(result.notebookName)
                    result.restored -> Strings.value_was_restored_from_the_vive_notebook(result.notebookName)
                    else -> Strings.value_was_updated_from_the_vive_notebook(result.notebookName)
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
                pendingInkEdits = emptyList(),
                selectedPageId = "",
                editorSelection = TextSelection(0),
                typingMarks = emptySet(),
                editorComposition = null,
                focusedTextOutlineId = null,
                selectedTextOutlineIds = emptySet(),
                selectedObjectIds = emptySet(), selectedInk = null,
                structuralUndo = emptyList(),
                structuralRedo = emptyList(),
            )
        }
        val sectionId = result.firstSectionId ?: before.selectedSectionId.takeIf { it.isNotEmpty() } ?: return
        // The list held for that section predates the import, and an open page keeps the title it
        // lists; storage's rows as they stand now replace it before anything opens.
        attempt(Strings.this_section_s_pages_could_not_be_read) { store.observePages(sectionId).first() }?.let { rows ->
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
        saveInk()
        val drained = CompletableDeferred<Unit>()
        enqueue { drained.complete(Unit) }
        drained.await()
        log.event("session") { "flush complete" }
    }

    /**
     * Pictures the editor can still put back on a page — the open page's, every undo and redo step's,
     * and the canvas clipboard's — for Android's `AttachmentSweep`, which must not let them go.
     * Read on the session's thread, like everything else here.
     */
    fun heldPictures(): Set<String> {
        val state = current.value ?: return emptySet()
        fun images(doc: PageDoc?) = doc?.outlines.orEmpty().filterIsInstance<com.vivenotes.model.Outline.Image>()
            .map { it.attachmentId }
        return buildSet {
            addAll(images(state.selectedPage?.document))
            (state.structuralUndo + state.structuralRedo).forEach { snapshot ->
                addAll(images(snapshot.document))
                addAll(images(snapshot.ink?.afterDocument))
            }
            state.canvasClipboard.outlines.filterIsInstance<com.vivenotes.model.Outline.Image>()
                .forEach { add(it.attachmentId) }
        }
    }

    /**
     * Android's `acceptStoredDocument`: makes a body replaced underneath the editor authoritative on
     * the canvas as well as in storage.
     *
     * Sync writes bodies straight into storage. Without this the open page keeps its older blocks, and
     * its next save writes them back as a fresh edit — resurrecting what another device removed. The
     * server's version wins, as plain OCC has it on Android, including over a save of this page
     * still queued: that one was made against the body being replaced, so it is dropped.
     *
     * This session's own reads and writes come back through the same observation, and are recognised
     * as such ([ownBodies]) rather than compared with the editor, so typing that happened while a
     * save was being written is never rolled back.
     */
    private fun acceptStoredDocument(pageId: String, load: PageLoad) {
        val state = current.value ?: return
        val page = state.selectedPage ?: return
        if (page.id != pageId || page.content == PageContent.Unloaded) return
        when (load) {
            is PageLoad.Loaded -> {
                if (page.content == PageContent.Loaded && ownBodies[pageId]?.contains(load.doc) == true) return
                remoteReplacements[pageId] = (remoteReplacements[pageId] ?: 0) + 1
                autosave?.cancel()
                stored[pageId] = load.doc
                ownBodies[pageId] = ArrayDeque(listOf(load.doc))
                log.event("sync") { "open page replaced by a synced body" }
                // A fresh selection, as Android's `showDocument` gives: a caret or a selected object
                // in the old body need not exist in the new one. Undo keeps its steps, which is the
                // trade Android makes too.
                current.value = state.updatePage(pageId) {
                    it.copy(document = load.doc, content = PageContent.Loaded)
                }.copy(
                    editorSelection = TextSelection(0),
                    typingMarks = emptySet(),
                    editorComposition = null,
                    focusedTextOutlineId = null,
                    selectedTextOutlineIds = emptySet(),
                    selectedObjectIds = emptySet(),
                )
            }
            is PageLoad.Unreadable -> {
                // Preserve the stored bytes and block every save, as an unreadable first read does.
                log.failure("sync", "synced page decode", load.cause)
                remoteReplacements[pageId] = (remoteReplacements[pageId] ?: 0) + 1
                autosave?.cancel()
                stored.remove(pageId)
                ownBodies.remove(pageId)
                current.value = state.updatePage(pageId) { it.copy(content = PageContent.Unreadable) }
            }
        }
    }

    /** The generation of remote ink [pageId] has received, or zero on a build with no account. */
    private fun remoteInkGeneration(pageId: String): Long = remoteInk?.value?.get(pageId) ?: 0L

    /**
     * Android's `absorbRemoteInk`: puts ink the server wrote into the open page onto the open page.
     *
     * A re-read, not a delta: erases and lasso moves replay in order and do not commute, so pulled
     * operations cannot be replayed against the new strokes alone. It waits while this page has ink of
     * its own in flight or a selection made from the strokes it would replace, and is tried again
     * once neither holds; the read itself queues behind every earlier ink write.
     */
    private fun absorbRemoteInk(pageId: String, generation: Long) {
        if (generation == absorbedInkGeneration) return
        val state = current.value ?: return
        val page = state.selectedPage ?: return
        if (page.id != pageId || page.content != PageContent.Loaded || !page.inkReady) return
        val source = inkSource ?: return
        if (state.selectedInk != null || state.pendingInkEdits.any { it.pageId == pageId }) {
            deferredInkAbsorption = true
            return
        }
        deferredInkAbsorption = false
        enqueue {
            val seen = remoteInkGeneration(pageId)
            val ink = attempt(Strings.this_page_s_ink_could_not_be_read) { source.loadInk(pageId) } ?: return@enqueue
            val now = current.value ?: return@enqueue
            if (now.selectedPageId != pageId) return@enqueue
            if (now.selectedInk != null || now.pendingInkEdits.any { it.pageId == pageId }) {
                deferredInkAbsorption = true
                return@enqueue
            }
            acceptInk(pageId, ink)
            absorbedInkGeneration = seen
            log.event("sync") { "open page took synced ink" }
        }
    }

    /**
     * Android's `runCloudArchive`: moves a closed notebook's contents to the server, or brings them
     * back, one notebook at a time. It runs in the session's scope rather than the pane's, so closing
     * the pane part-way through — the window where rows are deleted — does not cancel it.
     */
    private fun runCloudArchive(notebookId: String, move: Boolean) {
        val cloud = notebookCloud ?: return
        val pane = current.value?.filePane ?: return
        if (pane.pane != FilePane.ClosedNotebooks || pane.cloudBusyNotebookId != null || pane.busy) return
        update { it.copy(filePane = it.filePane.copy(cloudBusyNotebookId = notebookId, error = null, message = null)) }
        scope.launch {
            val failure = try {
                cloud.move(notebookId, toCloud = move)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.failure("sync", if (move) "move notebook to cloud" else "bring notebook back", error)
                if (move) Strings.closed_notebook_failed else Strings.closed_notebook_restore_failed
            }
            log.event("sync") { if (move) "notebook moved to cloud: ${failure == null}" else "notebook brought back: ${failure == null}" }
            val rows = attempt(Strings.closed_notebooks_could_not_be_read) { store.observeClosedNotebooks().first() }
            update { state ->
                if (state.filePane.pane != FilePane.ClosedNotebooks) {
                    state.copy(filePane = state.filePane.copy(cloudBusyNotebookId = null))
                } else {
                    state.copy(filePane = state.filePane.copy(cloudBusyNotebookId = null,
                        closedNotebooks = rows ?: state.filePane.closedNotebooks, error = failure))
                }
            }
        }
    }

    private fun react(before: WorkspaceState, after: WorkspaceState) {
        if (after.pendingInkEdits != before.pendingInkEdits || after.selectedPageId != before.selectedPageId) saveInk()
        if (deferredInkAbsorption && after.selectedInk == null &&
            after.pendingInkEdits.none { it.pageId == after.selectedPageId }) {
            after.selectedPageId.takeIf { it.isNotEmpty() }?.let { absorbRemoteInk(it, remoteInkGeneration(it)) }
        }
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
                attempt(Strings.the_page_title_could_not_be_saved) { store.renamePage(open.id, open.title) }
            }
        }
        if (open.document !== previous.document) scheduleAutosave()
    }

    private fun saveInk() {
        if (inkSaveQueued || current.value?.pendingInkEdits.isNullOrEmpty()) return
        val writer = inkSource as? com.vivenotes.data.InkWriter ?: return
        inkSaveQueued = true
        val generation = restores
        enqueue {
            try {
                while (generation == restores) {
                    val pending = current.value?.pendingInkEdits?.firstOrNull() ?: break
                    val saved = attempt(Strings.this_page_s_ink_changes_could_not_be_saved) {
                        writer.applyInkEdit(pending.pageId, pending.edit)
                    }
                    if (saved == null) break
                    current.value = current.value?.let { it.copy(
                        pendingInkEdits = it.pendingInkEdits.acknowledgeFirstInk(pending.id),
                    ) }
                    log.event("ink") { when (pending.edit) {
                        is com.vivenotes.data.InkEdit.AddStroke -> "stroke saved"
                        is com.vivenotes.data.InkEdit.EraseStrokes -> "whole-stroke erase saved"
                        is com.vivenotes.data.InkEdit.RestoreStrokes -> "stroke restore saved"
                        is com.vivenotes.data.InkEdit.PartialErase ->
                            if (pending.edit.active) "partial erase saved" else "partial erase undone"
                        is com.vivenotes.data.InkEdit.Move -> if (pending.edit.active) "ink move saved" else "ink move undone"
                        is com.vivenotes.data.InkEdit.SetStrokeColors -> "ink colours saved"
                        is com.vivenotes.data.InkEdit.SetStrokeGroups -> "ink groups saved"
                        is com.vivenotes.data.InkEdit.Batch -> "ink selection saved"
                    } }
                }
            } finally { inkSaveQueued = false }
        }
    }

    private fun scheduleAutosave() {
        autosave?.cancel()
        autosave = scope.launch {
            delay(autosaveDelayMillis)
            saveOpenPage()
        }
    }

    private fun saveOpenPage() {
        saveInk()
        autosave?.cancel()
        current.value?.selectedPage?.let(::save)
    }

    private fun save(page: PageSummary) {
        if (!page.editable) return
        val doc = page.document
        val previous = stored[page.id]
        if (previous == doc) return
        stored[page.id] = doc
        rememberOwnBody(page.id, doc)
        val generation = restores
        val replaced = remoteReplacements[page.id]
        enqueue {
            // An import finished since this was asked for: the archive replaced the page, and this
            // edit — made while the import ran — must not be written over it.
            if (generation != restores) return@enqueue
            // Likewise a body sync stored since: the server's version wins over this older edit.
            if (replaced != remoteReplacements[page.id]) return@enqueue
            log.event("storage") { "saving page" }
            val saved = attempt(Strings.changes_to_value_could_not_be_saved(page.title.ifBlank { Strings.untitled_page })) {
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
        if (pageId in loading) return
        val request = Any()
        loading[pageId] = request
        enqueue {
            if (loading[pageId] !== request) return@enqueue
            if (current.value?.selectedPageId != pageId) {
                loading.remove(pageId)
                return@enqueue
            }
            bodyObservation?.cancel()
            val token = Any()
            bodyObservationToken = token
            bodyObservationPage = pageId
            val first = CompletableDeferred<PageLoad?>()
            var latest: PageLoad? = null
            bodyObservation = scope.launch {
                try {
                    store.observeDoc(pageId).collect { load ->
                        if (bodyObservationToken !== token || current.value?.selectedPageId != pageId) return@collect
                        latest = load
                        if (first.isCompleted) acceptStoredDocument(pageId, load) else first.complete(load)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!first.isCompleted) first.completeExceptionally(failure)
                    else fail(Strings.this_page_could_not_be_opened, failure)
                } finally {
                    // A navigation cancelling an initial read must not strand the ordered queue.
                    first.complete(null)
                }
            }
            val load = attempt(Strings.this_page_could_not_be_opened) { first.await(); latest }
            if (loading[pageId] !== request) return@enqueue
            if (loading[pageId] === request) loading.remove(pageId)
            if (load != null) {
                log.event("storage") { "page loaded (${if (load is PageLoad.Loaded) "editable" else "unreadable"})" }
                if (load is PageLoad.Unreadable) log.failure("storage", "page decode", load.cause)
                accept(pageId, load)
                if (load is PageLoad.Loaded && inkSource != null) {
                    val seen = remoteInkGeneration(pageId)
                    attempt(Strings.this_page_s_ink_could_not_be_read) { inkSource.loadInk(pageId) }
                        ?.let {
                            acceptInk(pageId, it)
                            if (current.value?.selectedPageId == pageId) absorbedInkGeneration = seen
                        }
                }
            }
        }
    }

    private fun acceptInk(pageId: String, ink: InkPage) {
        val state = current.value ?: return
        if (ink.pageId != pageId || state.selectedPageId != pageId || state.selectedPage?.editable != true) return
        val pending = state.pendingInkEdits.filter { it.pageId == pageId }.map { it.edit }
        val snapshot = if (pending.isEmpty()) ink else (inkSource as? com.vivenotes.data.InkWriter)
            ?.overlayPendingInk(ink, pending) ?: ink
        current.value = state.updatePage(pageId) { it.copy(ink = snapshot, inkReady = true) }
            .let { if (state.selectedPageId == pageId) it.copy(selectedInk = null) else it }
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
                    ownBodies[pageId] = ArrayDeque(listOf(load.doc))
                    it.copy(document = load.doc, content = PageContent.Loaded, inkReady = inkSource == null)
                }
                is PageLoad.Unreadable -> it.copy(content = PageContent.Unreadable)
            }
        }
    }

    /** A page that is no longer open gives up its body; the next opening reads storage again. */
    private fun unload(pageId: String) {
        loading.remove(pageId)
        if (bodyObservationPage == pageId) {
            bodyObservationToken = null
            bodyObservationPage = null
            bodyObservation?.cancel()
            bodyObservation = null
        }
        stored.remove(pageId)
        ownBodies.remove(pageId)
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
                editorDefaults = editorDefaults, shapeSettings = shapeSettings, inkSettings = inkSettings.normalized(), eraserMode = inkSettings.eraser.mode)
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
        inkReady = cached?.inkReady ?: (inkSource == null),
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
            editorDefaults = editorDefaults, shapeSettings = shapeSettings, inkSettings = inkSettings.normalized(), eraserMode = inkSettings.eraser.mode)).copy(storageError = message)
    }

    companion object {
        /** Android's `NotesViewModel.AUTOSAVE_DELAY_MS`: a burst of typing is one write. */
        const val AUTOSAVE_DELAY_MILLIS = 400L

        /** How many of a page's own written bodies are recognised when storage reports them back. */
        private const val OWN_BODIES_KEPT = 8

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
