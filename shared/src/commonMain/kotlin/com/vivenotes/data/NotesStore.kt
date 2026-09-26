package com.vivenotes.data

import com.vivenotes.data.db.NotebookWithSections
import com.vivenotes.data.db.PageEntity
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

    /** Creates a page holding `PageDoc.empty()` and returns its id. */
    suspend fun createPage(sectionId: String, title: String = ""): String

    suspend fun renamePage(id: String, title: String)

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
