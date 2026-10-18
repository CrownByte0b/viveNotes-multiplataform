// Ported from viveNotes Android data/OnlineImages.kt (commit 35e7147); the search itself is jvmMain's
// OnlineImageSearch, and a preview is a Compose ImageBitmap rather than an Android Bitmap.
package com.vivenotes.data

import androidx.compose.ui.graphics.ImageBitmap

/** Where a search hit came from. The dialog marks where one source gives way to the next. */
enum class ImageSource { WikimediaCommons, Openverse }

/** One search hit: what the results grid shows, and what is fetched when it is picked. */
data class OnlineImage(
    val id: String,
    val title: String,
    /** What is inserted: the original when it can be decoded at a sensible size, else a rendition. */
    val url: String,
    /** A grid-sized rendition the source serves — when it can; see [OnlineImages.thumbnail]. */
    val thumbnail: String,
    val width: Int,
    val height: Int,
    val source: ImageSource,
)

/** Where the next page of a search starts. Opaque outside the search and its two clients. */
sealed interface SearchCursor {
    /** [offRuns] counts the pages in a row that were mostly off-topic — see `nextAfterCommons`. */
    data class Commons(val offset: Int, val offRuns: Int = 0) : SearchCursor
    data class Openverse(val page: Int) : SearchCursor
}

sealed interface OnlineImageSearchResult {
    /** [next] is handed back to [OnlineImages.search] for what follows; null when nothing does. */
    data class Found(val images: List<OnlineImage>, val next: SearchCursor?) : OnlineImageSearchResult

    /** A quota is spent — per minute or per day. Waiting fixes it; retrying at once does not. */
    data object RateLimited : OnlineImageSearchResult

    /** No network, a timeout, or an answer that was not a result list. */
    data object Unreachable : OnlineImageSearchResult
}

/** Search online and insert — the Picture menu's "Search online". */
interface OnlineImages {
    /** The first page for [query] when [from] is null; otherwise the page [from] points at. */
    suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult

    /** A small decoded preview for the grid, or null when it could not be fetched. */
    suspend fun thumbnail(image: OnlineImage): ImageBitmap?

    /** The bytes to import — [OnlineImage.url] when it can be had, the thumbnail when not. */
    suspend fun download(image: OnlineImage): ByteArray?
}
