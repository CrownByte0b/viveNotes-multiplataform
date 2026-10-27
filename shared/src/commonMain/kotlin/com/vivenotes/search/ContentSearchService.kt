package com.vivenotes.search

import com.vivenotes.model.PageDoc
import com.vivenotes.model.search.ContentHit
import kotlinx.coroutines.flow.Flow

data class SearchRequest(
    val notebookId: String,
    val query: String,
    val livePageId: String?,
    val liveSectionId: String,
    val liveTitle: String,
    val liveDocument: PageDoc?,
)

data class SearchPageResults(
    val pageId: String,
    val title: String,
    val sectionName: String,
    val hits: List<ContentHit>,
)

data class SearchResults(
    val pages: List<SearchPageResults> = emptyList(),
    val searching: Boolean = false,
    val imagesDone: Int = 0,
    val imagesTotal: Int = 0,
    val failedImages: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
    val unreadablePages: Int = 0,
    val inkDone: Int = 0,
    val inkTotal: Int = 0,
    val failedInk: Int = 0,
) {
    val hitCount: Int get() = pages.sumOf { it.hits.size }
}

interface ContentSearchService {
    /** Emits typed/cached results first, then adds picture and handwriting readings as workers finish. */
    fun search(request: SearchRequest): Flow<SearchResults>
    suspend fun retryFailedImages() {}
    suspend fun retryFailedHandwriting() {}
}
