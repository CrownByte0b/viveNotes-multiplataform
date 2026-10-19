package com.vivenotes.search

import com.vivenotes.localization.Strings
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopContentSearch(
    private val repository: NotesRepository,
    private val attachments: AttachmentStore,
    private val pool: SearchProcessPool = SearchProcessPool(),
    private val log: DebugLog = DebugLog(),
) : ContentSearchService, AutoCloseable {
    private data class Projection(val request: WorkerRequest, val reply: WorkerReply)
    private val projected = ConcurrentHashMap<String, Projection>()

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
            val row = bodies[page.id]
            val command = WorkerRequest(SearchOperation.Project, pageId = page.id, sectionId = page.sectionId,
                title = if (live) request.liveTitle else page.title,
                body = if (live) JsonDocumentCodec.encodeToString(request.liveDocument!!) else row?.docJson,
                codec = if (live) JsonDocumentCodec.id else row?.format ?: JsonDocumentCodec.id)
            projected[page.id]?.takeIf { it.request == command }?.reply ?: pool.execute(command).also {
                check(!it.failed) { Strings.document_worker_failed }
                projected[page.id] = Projection(command, it)
            }
        }
        val placements = entries.flatMap { it.images }
        val ids = placements.mapTo(mutableSetOf()) { it.attachmentId }
        val cached = repository.imageTextFor(ids).filterValues { it.engine == IMAGE_ENGINE }
        val units = entries.flatMap { it.units } + placements.flatMap { placement ->
            cached[placement.attachmentId]?.takeIf { it.status == ImageTextStatus.Read }
                ?.let { imageUnits(placement, it.text.lines()) }.orEmpty()
        }
        var candidates = match(units, request.query)
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
            searching = completed < ids.size,
            imagesDone = completed, imagesTotal = ids.size, failedImages = failed,
            truncated = candidates.size > MAX_HITS, unreadablePages = unreadable,
        )
        send(result())
        if (pending.isNotEmpty()) log.event("search") { "OCR started: images=${pending.size}, processes=${pool.parallelism}" }
        val updates = Mutex()
        parallelMap(pending.toList()) { id ->
            val started = TimeSource.Monotonic.markNow()
            val reading = try {
                val file = attachments.fileFor(id)?.takeIf { it.isFile }
                if (file == null) WorkerReply(failed = true)
                else pool.execute(WorkerRequest(SearchOperation.Ocr, imagePath = file.absolutePath))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WorkerReply(failed = true)
            }
            val status = when {
                reading.failed -> ImageTextStatus.Failed
                reading.text.isBlank() -> ImageTextStatus.Empty
                else -> ImageTextStatus.Read
            }
            repository.storeImageText(AttachmentTextEntity(id, reading.text,
                if (reading.text.isEmpty()) 0 else reading.text.lines().size,
                reading.confidence, IMAGE_ENGINE, status, started.elapsedNow().inWholeMilliseconds,
                System.currentTimeMillis()))
            val newUnits = placements.filter { it.attachmentId == id }.flatMap { imageUnits(it, reading.text.lines()) }
            val hits = match(newUnits, request.query)
            updates.withLock {
                candidates = ranked(candidates + hits)
                completed++
                if (reading.failed) failed++
                send(result())
            }
        }
        log.event("search") { "query complete: hits=${candidates.size.coerceAtMost(MAX_HITS)}, images=${ids.size}, failed=$failed" }
    }

    private suspend fun match(units: List<ContentUnit>, query: String): List<ContentHit> {
        if (units.isEmpty()) return emptyList()
        // Even one page with many text boxes spreads matching across multiple processes.
        val size = ((units.size + pool.parallelism - 1) / pool.parallelism).coerceIn(1, 128)
        return ranked(parallelMap(units.chunked(size)) { chunk ->
            pool.execute(WorkerRequest(SearchOperation.Match, query = query, units = chunk)).also {
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
        log.event("search") { "failed images queued for retry" }
    }

    override fun close() = pool.close()

    companion object {
        const val IMAGE_ENGINE = "ppocrv6-small/5435fd74-d73e0058-b5f2bfe2/bgr-db/1"
    }
}
