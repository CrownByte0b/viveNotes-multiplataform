package com.vivenotes.data

import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.PageDoc
import com.vivenotes.model.plainText
import kotlinx.coroutines.flow.Flow

/** Outcome of reading a page body. */
sealed interface PageLoad {
    data class Loaded(val doc: PageDoc) : PageLoad

    /**
     * The stored JSON could not be decoded. The raw text is carried along so it can be recovered
     * or exported; callers must not overwrite the page while in this state.
     */
    data class Unreadable(val rawJson: String, val cause: Throwable) : PageLoad
}

/**
 * The part of note storage the workspace talks to.
 *
 * [NotesRepository] implements it over Room on desktop, and common tests substitute a fake. The
 * shapes are the database rows themselves, so there is no second model to keep in step with the
 * first; every member has the meaning the repository documents for it.
 */
interface NotesStore {

    /** Seeds the starter notebook on first launch. Must finish before anything observes the tree. */
    suspend fun seedIfEmpty()

    /** The rail: live, open notebooks with their sections. */
    fun observeTree(): Flow<List<NotebookWithSections>>

    /** One section's live pages in order. Metadata only — bodies are read by [loadDoc]. */
    fun observePages(sectionId: String): Flow<List<PageEntity>>

    suspend fun pageById(id: String): PageEntity?

    /** Creates an empty notebook at the end of the rail and returns its id. */
    suspend fun createNotebook(name: String): String

    /** Creates an empty section at the end of [notebookId] and returns its id. */
    suspend fun createSection(notebookId: String, name: String): String

    /** Creates a page holding `PageDoc.empty()` and returns its id. */
    suspend fun createPage(sectionId: String, title: String = ""): String

    /** Whether the rail shows the notebook's sections. Local only: never pushed to sync. */
    suspend fun setNotebookExpanded(id: String, expanded: Boolean)

    /** Puts a notebook's sections in [orderedIds]' order; sections it does not name keep theirs, last. */
    suspend fun reorderSections(notebookId: String, orderedIds: List<String>)

    /** Puts a section's pages in [orderedIds]' order; pages it does not name keep theirs, last. */
    suspend fun reorderPages(sectionId: String, orderedIds: List<String>)

    suspend fun renamePage(id: String, title: String)

    suspend fun renameNotebook(id: String, name: String)

    suspend fun renameSection(id: String, name: String)

    /** Tombstones a notebook with everything in it, or flushes one that never held anything. */
    suspend fun deleteNotebook(id: String): DeletionOutcome

    /** Tombstones a section with its pages, or flushes an empty one. */
    suspend fun deleteSection(id: String): DeletionOutcome

    /** Tombstones a page, or flushes one that was never written on. */
    suspend fun deletePage(id: String): DeletionOutcome

    /** Shelves a notebook without deleting its contents, and lists/reopens shelved notebooks. */
    suspend fun closeNotebook(id: String)
    suspend fun reopenNotebook(id: String)
    fun observeClosedNotebooks(): Flow<List<ClosedNotebook>>

    /** Recovery roots remain available for the storage retention period. */
    fun observeDeletedItems(): Flow<List<DeletedItem>>
    suspend fun restoreDeletedItem(key: DeletedItemKey): Boolean

    /** Page checkpoints include document and ink; restoring one saves the previous page first. */
    suspend fun revisionHistory(pageId: String): List<PageRevisionSummary>
    suspend fun loadRevision(pageId: String, revisionId: String): PageRevisionLoad
    suspend fun restoreRevision(pageId: String, revisionId: String): PageRevisionLoad

    /** Never an empty stand-in for content that failed to decode — see [PageLoad.Unreadable]. */
    suspend fun loadDoc(pageId: String): PageLoad

    /** Writes the body and its preview; a body identical to the stored one writes nothing. */
    suspend fun saveDoc(pageId: String, doc: PageDoc)
}

/**
 * The page list's line of preview for [doc]: its first line with text in it, cut to 140 characters.
 *
 * `pages.preview` stores exactly this, so an open page's list entry can follow its typing without
 * changing when the save lands.
 */
fun pagePreview(doc: PageDoc): String = doc.plainText().lineSequence()
    .firstOrNull { it.isNotBlank() }
    .orEmpty()
    .take(140)
