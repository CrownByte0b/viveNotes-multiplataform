// Ported from viveNotes Android data/OnlineImages.kt (commit 35e7147); previews are decoded by Skia
// into Compose bitmaps, and the cache is a small byte-bounded LRU in place of android.util.LruCache.
package com.vivenotes.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/** One source's answer for one page, before the two sources are stitched together. */
internal sealed interface SourceResult {
    /**
     * [onTopic] is how many [images] name the query in their own words — see
     * [WikimediaCommonsClient.isOnTopic]. Openverse does not measure it and reports them all.
     */
    data class Page(val images: List<OnlineImage>, val hasMore: Boolean, val onTopic: Int) : SourceResult
    data object RateLimited : SourceResult
    data object Unreachable : SourceResult
}

/** The desktop's "Search online", identifying itself to both services as this build's [version]. */
fun onlineImageSearch(version: String, log: DebugLog = DebugLog()): OnlineImages =
    OnlineImageSearch(PictureHttp(PictureHttp.userAgent(version)), log = log)

/**
 * Wikimedia Commons first, Openverse once Commons stops being about the query.
 *
 * Commons leads because it is where the diagrams are — a search for "meiosis" there is page after
 * page of labelled figures, and it renders its SVGs to PNG, which Openverse's results cannot offer.
 * Openverse follows because its photographs are broader. It is asked to leave Wikimedia out, so the
 * hand-over repeats nothing.
 *
 * Neither source needs a key. Bing's search APIs were retired in August 2025 and Google's Custom
 * Search JSON API is closed to new customers — and either one's key, shipped in an app, would be a
 * key anyone could lift.
 *
 * Licensing is left to the person inserting the picture: nothing about the source is written onto
 * the page.
 */
class OnlineImageSearch internal constructor(
    private val http: PictureHttp = PictureHttp(),
    private val commons: suspend (query: String, offset: Int) -> SourceResult =
        WikimediaCommonsClient(http)::search,
    private val openverse: suspend (query: String, page: Int) -> SourceResult =
        OpenverseClient(http)::search,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Which source answered and how; never the words searched for. */
    private val log: DebugLog = DebugLog(),
) : OnlineImages {

    /**
     * Decoded previews by image id, bounded by bytes rather than count.
     *
     * Scrolling back up the grid should not re-download what was on screen a moment ago — Openverse
     * counts each thumbnail against a daily quota — but nor should a long session keep every page of
     * results in memory.
     */
    private val thumbnails = PreviewCache(THUMBNAIL_CACHE_BYTES)

    /** A grid lays out twenty tiles at once; twenty sockets at once is not what that should mean. */
    private val fetches = Semaphore(PARALLEL_FETCHES)

    override suspend fun search(query: String, from: SearchCursor?): OnlineImageSearchResult =
        when (val cursor = from ?: SearchCursor.Commons(offset = 0)) {
            is SearchCursor.Commons -> when (val result = commons(query, cursor.offset)) {
                is SourceResult.Page -> {
                    val next = nextAfterCommons(cursor, result)
                    log.event("pictures") {
                        "commons page offset=${cursor.offset} results=${result.images.size} " +
                            "onTopic=${result.onTopic} next=${next::class.simpleName}"
                    }
                    // A page with nothing to show — Commons had nothing at all, say — would leave
                    // the grid empty and asking for more. Openverse answers in its place.
                    if (result.images.isEmpty() && next is SearchCursor.Openverse) {
                        search(query, next)
                    } else {
                        OnlineImageSearchResult.Found(result.images, next)
                    }
                }
                // Commons being down or busy is no reason to show nothing: Openverse carries on.
                SourceResult.RateLimited, SourceResult.Unreachable -> {
                    log.event("pictures") { "commons ${result::class.simpleName}; asking openverse" }
                    search(query, SearchCursor.Openverse(page = 1))
                }
            }
            is SearchCursor.Openverse -> when (val result = openverse(query, cursor.page)) {
                is SourceResult.Page -> {
                    log.event("pictures") {
                        "openverse page=${cursor.page} results=${result.images.size} more=${result.hasMore}"
                    }
                    OnlineImageSearchResult.Found(
                        images = result.images,
                        next = if (result.hasMore) SearchCursor.Openverse(cursor.page + 1) else null,
                    )
                }
                SourceResult.RateLimited -> OnlineImageSearchResult.RateLimited
                    .also { log.event("pictures") { "openverse rate limited" } }
                SourceResult.Unreachable -> OnlineImageSearchResult.Unreachable
                    .also { log.event("pictures") { "openverse unreachable" } }
            }
        }

    /**
     * The source's own grid rendition first, the original when it has none.
     *
     * Openverse's thumbnail service cannot reach every provider: on 2026-10-03 every Wikimedia hit
     * answered `424 Thumbnail unavailable from provider.`, while the same originals downloaded
     * directly. The original is capped lower here than for an insert, because this is a tile.
     */
    override suspend fun thumbnail(image: OnlineImage): ImageBitmap? {
        thumbnails.get(image.id)?.let { return it }
        val bytes = fetches.withPermit {
            http.image(image.thumbnail, MAX_THUMBNAIL_BYTES)
                ?: http.image(image.url, MAX_PREVIEW_ORIGINAL_BYTES)
        } ?: return null
        val bitmap = withContext(io) { decodePreview(bytes) } ?: return null
        thumbnails.put(image.id, bitmap)
        return bitmap
    }

    /**
     * [OnlineImage.url] first, because a grid thumbnail stretched across half a page looks it. The
     * thumbnail is the fallback for files that are gone, too large, or served as something that is
     * not a picture — a removed Flickr photo, say, answers with an HTML page.
     */
    override suspend fun download(image: OnlineImage): ByteArray? =
        fetches.withPermit {
            http.image(image.url, MAX_ORIGINAL_BYTES) ?: http.image(image.thumbnail, MAX_THUMBNAIL_BYTES)
        }.also { bytes ->
            log.event("pictures") { if (bytes == null) "download failed" else "downloaded ${bytes.size} bytes" }
        }

    /** Decoded at grid size: a tile is ~160 dp, and a 600 px JPEG is 1.4 MB as a bitmap. */
    private fun decodePreview(bytes: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).use { source ->
            val longest = maxOf(source.width, source.height)
            val scale = if (longest > PREVIEW_MAX_DIMENSION) PREVIEW_MAX_DIMENSION.toFloat() / longest else 1f
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(Color.TRANSPARENT)
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }.getOrNull()

    internal companion object {
        /**
         * Commons pages shown before relevance is judged at all.
         *
         * The judgement is crude — words of the query in the title or English description — and
         * misses real hits named another way: "Krebs cycle" finds forty diagrams titled "Citric
         * acid cycle" before any says "Krebs". Commons' own ranking is better than the test, so
         * the first pages are always its.
         */
        const val COMMONS_TRUSTED_PAGES = 3

        /**
         * Consecutive mostly-off-topic pages that end Commons. Two, because one is noise: "red fox"
         * dips to 7 of 20 on its seventh page and is back to 10 on its eighth.
         */
        const val COMMONS_OFF_TOPIC_RUN = 2

        /** Where Commons gives way however on-topic it still looks — ten pages of one source. */
        const val COMMONS_MAX_RESULTS = 200

        /**
         * Where a Commons page leads: more Commons, or Openverse.
         *
         * Commons hands over when it runs out, when [COMMONS_MAX_RESULTS] is reached, or when
         * [COMMONS_OFF_TOPIC_RUN] pages in a row, the last of them past the trusted ones, had fewer
         * than half their hits on topic. Over the eight pages measured on 2026-10-03, that kept
         * "meiosis", "red fox" and "eiffel tower" on Commons throughout, moved "Krebs cycle" to
         * Openverse after its sixth page, and "quadratic formula" — 13, 3 and 4 of 20 on topic —
         * after its third.
         */
        internal fun nextAfterCommons(cursor: SearchCursor.Commons, page: SourceResult.Page): SearchCursor {
            val offTopic = page.images.isNotEmpty() && page.onTopic * 2 < page.images.size
            val offRuns = if (offTopic) cursor.offRuns + 1 else 0
            val nextOffset = cursor.offset + WikimediaCommonsClient.PAGE_SIZE
            val pagesShown = nextOffset / WikimediaCommonsClient.PAGE_SIZE
            val drifted = pagesShown >= COMMONS_TRUSTED_PAGES && offRuns >= COMMONS_OFF_TOPIC_RUN
            return if (!page.hasMore || drifted || nextOffset >= COMMONS_MAX_RESULTS) {
                SearchCursor.Openverse(page = 1)
            } else {
                SearchCursor.Commons(nextOffset, offRuns)
            }
        }

        private const val PARALLEL_FETCHES = 4

        /**
         * A file larger than this is not worth fetching whole. [AttachmentStore] scales whatever
         * arrives down to its own maximum, so past this the extra bytes would only be thrown away —
         * the thumbnail is used instead.
         */
        private const val MAX_ORIGINAL_BYTES = 30 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
        private const val MAX_PREVIEW_ORIGINAL_BYTES = 8 * 1024 * 1024
        internal const val PREVIEW_MAX_DIMENSION = 360
        private const val THUMBNAIL_CACHE_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Android's `LruCache` of decoded previews: least recently used first out, measured in the bytes
 * each bitmap holds. Previews are put from whichever thread decoded them, hence the locking.
 */
internal class PreviewCache(private val maxBytes: Long) {
    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(id: String): ImageBitmap? = entries[id]

    @Synchronized
    fun put(id: String, bitmap: ImageBitmap) {
        entries.put(id, bitmap)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(bitmap)
        val eldest = entries.entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= sizeOf(eldest.next().value)
            eldest.remove()
        }
    }

    @get:Synchronized
    val size: Int get() = entries.size

    private fun sizeOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
