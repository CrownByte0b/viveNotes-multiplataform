package com.vivenotes.search

import com.vivenotes.ai.InkTextRegionsCodec
import com.vivenotes.ai.inkPageLayout
import com.vivenotes.data.db.InkTextStatus
import com.vivenotes.localization.Strings
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.PageDoc
import com.vivenotes.model.migrated
import com.vivenotes.model.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopContentSearch private constructor(
    private val repository: NotesRepository,
    private val attachments: AttachmentStore,
    private val pool: SearchProcessPool,
    private val log: DebugLog,
    private val execute: suspend (WorkerRequest) -> WorkerReply,
) : ContentSearchService, AutoCloseable {
    constructor(repository: NotesRepository, attachments: AttachmentStore,
        pool: SearchProcessPool = SearchProcessPool(), log: DebugLog = DebugLog()) :
        this(repository, attachments, pool, log, pool::execute)

    internal constructor(repository: NotesRepository, attachments: AttachmentStore, pool: SearchProcessPool,
        execute: suspend (WorkerRequest) -> WorkerReply) : this(repository, attachments, pool, DebugLog(), execute)

    private data class Projection(val source: Any, val reply: WorkerReply)
    private data class LiveSource(val pageId: String, val sectionId: String, val title: String, val document: PageDoc)
    private val projected = ConcurrentHashMap<String, Projection>()
    private val matchKeys = SearchChunkKeys()
    private val indexing = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val imageLanes = Semaphore((pool.parallelism - 1).coerceAtLeast(2))
    private val indexingQueue = Semaphore(pool.parallelism * 2)
    private val inkIndex = SearchInkIndex(repository, indexing, pool.parallelism) { command ->
        imageLanes.withPermit { execute(command) }
    }
    private data class ImageKey(val attachment: String, val engine: String = IMAGE_ENGINE)
    private val readings = LinkedHashMap<ImageKey, Deferred<AttachmentTextEntity>>(16, .75f, true)

    override fun search(request: SearchRequest): Flow<SearchResults> {
        if (request.query.isBlank()) return flowOf(SearchResults())
        return repository.observeSearchChanges().transformLatest {
            emitAll(searchSnapshot(request))
        }.catch { error ->
            if (error is CancellationException) throw error
            log.event("search") { "query failed" }
            emit(SearchResults(error = Strings.search_could_not_finish_try_again))
        }.flowOn(Dispatchers.Default)
    }

    private fun searchSnapshot(request: SearchRequest): Flow<SearchResults> = channelFlow {
        send(SearchResults(searching = true))
        val pages = repository.pagesInNotebook(request.notebookId)
        val sections = repository.sectionsInNotebook(request.notebookId).associate { it.id to it.name }
        val bodies = repository.searchBodies(pages.filter { it.id != request.livePageId || request.liveDocument == null }.map { it.id })
            .associateBy { it.pageId }
        projected.keys.retainAll(pages.map { it.id }.toSet())
        val entries = parallelMap(pages) { page ->
            val live = page.id == request.livePageId && request.liveDocument != null
            if (live) {
                val source = LiveSource(page.id, page.sectionId, request.liveTitle, request.liveDocument!!)
                return@parallelMap projected[page.id]?.takeIf { it.source == source }?.reply ?: run {
                    val doc = source.document.migrated()
                    WorkerReply(units = doc.contentUnits(page.id, page.sectionId, request.liveTitle),
                        images = doc.imagePlacements(page.id, page.sectionId), inkLayout = doc.inkPageLayout()).also {
                        projected[page.id] = Projection(source, it)
                    }
                }
            }
            val row = bodies[page.id]
            val command = WorkerRequest(SearchOperation.Project, pageId = page.id, sectionId = page.sectionId,
                title = page.title, body = row?.docJson, codec = row?.format ?: JsonDocumentCodec.id)
            projected[page.id]?.takeIf { it.source == command }?.reply ?: execute(command).also {
                check(!it.failed) { Strings.document_worker_failed }
                projected[page.id] = Projection(command, it)
            }
        }
        val inkPageIds = repository.pagesWithSearchInk(pages.map { it.id })
        val inkPages = pages.zip(entries).filter { (page, _) -> page.id in inkPageIds }
        val inkRows = repository.inkTextFor(inkPageIds)
        val cachedInk = inkPages.mapNotNull { (page, entry) ->
            inkRows[page.id]?.takeIf { it.engine == SearchInkIndex.ENGINE && it.layoutHash == entry.inkLayout.hash }
        }.associateBy { it.pageId }
        val handwriting = inkPages.flatMap { (page, _) ->
            cachedInk[page.id]?.takeIf { it.status == InkTextStatus.Read }?.let {
                inkUnits(page.id, page.sectionId, InkTextRegionsCodec.decode(it.regionsJson))
            }.orEmpty()
        }
        val placements = entries.flatMap { it.images }
        val placementsById = placements.groupBy { it.attachmentId }
        val ids = placementsById.keys
        val cached = repository.imageTextFor(ids).filterValues { it.engine == IMAGE_ENGINE }
        val cachedLines = cached.filterValues { it.status == ImageTextStatus.Read }.mapValues { it.value.text.lines() }
        val units = entries.flatMap { it.units } + handwriting + placements.flatMap { placement ->
            cachedLines[placement.attachmentId]?.let { imageUnits(placement, it) }.orEmpty()
        }
        var candidates = match(units, request.query)
        var inkCompleted = cachedInk.size
        var inkFailed = cachedInk.values.count { it.status == InkTextStatus.Failed }
        var completed = cached.size
        var failed = cached.values.count { it.status == ImageTextStatus.Failed }
        val unreadable = entries.count { it.unreadable }
        val pending = ids - cached.keys
        val titles = pages.associate { page -> page.id to
            if (page.id == request.livePageId) request.liveTitle else page.title }
        fun result() = SearchResults(
            pages = candidates.take(MAX_HITS).groupBy { it.unit.pageId }.map { (pageId, hits) ->
                SearchPageResults(pageId, titles[pageId].orEmpty(), sections[hits.first().unit.sectionId].orEmpty(), hits)
            },
            searching = completed < ids.size || inkCompleted < inkPages.size,
            inkDone = inkCompleted, inkTotal = inkPages.size, failedInk = inkFailed,
            imagesDone = completed, imagesTotal = ids.size, failedImages = failed,
            truncated = candidates.size > MAX_HITS, unreadablePages = unreadable,
        )
        send(result())
        if (pending.isNotEmpty()) log.event("search") { "OCR started: images=${pending.size}, processes=${pool.parallelism}" }
        val updates = Mutex()
        val imageJobs = launch {
            parallelMap(pending.toList()) { id ->
                val reading = imageReading(id).await()
                val lines = reading.text.lines()
                val newUnits = placementsById.getValue(id).flatMap { imageUnits(it, lines) }
                val hits = match(newUnits, request.query)
                updates.withLock {
                    candidates = ranked(candidates + hits)
                    completed++
                    if (reading.status == ImageTextStatus.Failed) failed++
                    send(result())
                }
            }
        }
        if (inkCompleted < inkPages.size) log.event("search") { "handwriting OCR started: pages=${inkPages.size - inkCompleted}" }
        parallelMap(inkPages.filter { (page, _) -> page.id !in cachedInk }) { (page, entry) ->
            val reading = inkIndex.reading(page.id, entry.inkLayout).await()
            val newUnits = reading?.takeIf { it.status == InkTextStatus.Read }?.let {
                inkUnits(page.id, page.sectionId, InkTextRegionsCodec.decode(it.regionsJson))
            }.orEmpty()
            val hits = match(newUnits, request.query)
            updates.withLock {
                candidates = ranked(candidates + hits)
                inkCompleted++
                if (reading?.status == InkTextStatus.Failed) inkFailed++
                send(result())
            }
        }
        imageJobs.join()
        log.event("search") { "handwriting complete: pages=${inkPages.size}, failed=$inkFailed" }
        log.event("search") { "query complete: hits=${candidates.size.coerceAtMost(MAX_HITS)}, images=${ids.size}, failed=$failed" }
    }

    private suspend fun imageReading(id: String): Deferred<AttachmentTextEntity> {
        check(indexing.isActive) { Strings.search_has_been_closed }
        val key = ImageKey(id)
        synchronized(readings) { readings[key]?.let { return it } }
        indexingQueue.acquire()
        return synchronized(readings) {
            readings[key]?.let { indexingQueue.release(); return@synchronized it }
            val job = indexing.async(start = CoroutineStart.LAZY) {
                    imageLanes.withPermit {
                        val started = TimeSource.Monotonic.markNow()
                        val reading = try {
                            val file = attachments.fileFor(id)?.takeIf { it.isFile }
                            if (file == null) WorkerReply(failed = true)
                            else execute(WorkerRequest(SearchOperation.Ocr, imagePath = file.absolutePath))
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { WorkerReply(failed = true) }
                        val status = when {
                            reading.failed -> ImageTextStatus.Failed
                            reading.text.isBlank() -> ImageTextStatus.Empty
                            else -> ImageTextStatus.Read
                        }
                        AttachmentTextEntity(id, reading.text, if (reading.text.isEmpty()) 0 else reading.text.lines().size,
                            reading.confidence, IMAGE_ENGINE, status, started.elapsedNow().inWholeMilliseconds,
                            System.currentTimeMillis()).also { repository.storeImageText(it) }
                    }
            }
            readings[key] = job
            job.invokeOnCompletion { cause ->
                indexingQueue.release()
                if (cause != null) synchronized(readings) { if (readings[key] === job) readings.remove(key) }
            }
            while (readings.size > 128) {
                val done = readings.entries.firstOrNull { it.key != key && it.value.isCompleted }?.key ?: break
                readings.remove(done)
            }
            job.start()
            job
        }
    }

    private suspend fun match(units: List<ContentUnit>, query: String): List<ContentHit> {
        if (units.isEmpty()) return emptyList()
        // Even one page with many text boxes spreads matching across multiple processes.
        val size = ((units.size + pool.parallelism - 1) / pool.parallelism).coerceIn(1, 128)
        return ranked(parallelMap(units.chunked(size)) { chunk ->
            execute(WorkerRequest(SearchOperation.Match, query = query, units = chunk, matchKey = matchKeys.keyFor(chunk))).also {
                check(!it.failed) { Strings.matching_worker_failed }
            }.hits
        }.flatten())
    }

    private fun ranked(hits: List<ContentHit>) = hits.sortedWith(
        compareByDescending<ContentHit> { it.score }.thenBy { it.unit.pageId }
            .thenBy { it.unit.blockIndex }.thenBy { it.unit.boxId },
    ).take(MAX_HITS + 1)

    private suspend fun <T, R> parallelMap(items: List<T>, action: suspend (T) -> R): List<R> = coroutineScope {
        val queue = Channel<IndexedValue<T>>(pool.parallelism)
        val results = arrayOfNulls<Any?>(items.size)
        launch { items.withIndex().forEach { queue.send(it) }; queue.close() }
        List(minOf(pool.parallelism, items.size)) {
            async { for ((index, item) in queue) results[index] = action(item) }
        }.awaitAll()
        @Suppress("UNCHECKED_CAST")
        results.map { it as R }
    }

    override suspend fun retryFailedImages() {
        repository.retryFailedImageText()
        synchronized(readings) {
            readings.entries.removeAll { (_, job) -> job.isCompleted &&
                runCatching { job.getCompleted() }.getOrNull().let { it == null || it.status == ImageTextStatus.Failed } }
        }
        log.event("search") { "failed images queued for retry" }
    }

    override suspend fun retryFailedHandwriting() {
        repository.retryFailedInkText()
        inkIndex.retryFailures()
        log.event("search") { "failed handwriting queued for retry" }
    }

    override fun close() {
        indexing.cancel()
        pool.close()
        synchronized(readings) { readings.clear() }
        projected.clear()
    }

    companion object {
        const val IMAGE_ENGINE = "ppocrv6-small/5435fd74-d73e0058-b5f2bfe2/bgr-db/1"
    }
}
