package com.vivenotes.search

import com.vivenotes.ai.*
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.InkTextEntity
import com.vivenotes.data.db.InkTextStatus
import com.vivenotes.ink.desktopGeometry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Image
import java.util.Base64
import kotlin.time.TimeSource

/** Retains a bounded set of page readings across query cancellation, guarded by ink generation. */
internal class SearchInkIndex(
    private val repository: NotesRepository,
    private val scope: CoroutineScope,
    parallelism: Int,
    execute: suspend (WorkerRequest) -> WorkerReply,
) {
    private data class Key(val page: String, val generation: Long, val layout: String)
    private val readings = LinkedHashMap<Key, Deferred<InkTextEntity?>>(16, .75f, true)
    private val queue = Semaphore(parallelism * 2)
    private val pages = Semaphore(2)
    private val recognizer = HandwritingRecognizer(object : InkRecognitionEngine {
        override val parallelism = parallelism
        override suspend fun recognizeText(image: Image): TextRecognitionResult {
            val bytes = image.encodeToData()!!.use { it.bytes }
            val reply = execute(WorkerRequest(SearchOperation.Ocr,
                imageData = Base64.getEncoder().encodeToString(bytes), textLine = true))
            check(!reply.failed) { "Handwriting worker failed" }
            return TextRecognitionResult(reply.text, reply.confidence)
        }
        override suspend fun recognizeFormula(image: Image): FormulaRecognitionResult = error("Not a formula request")
        override suspend fun recognizeImageText(image: Image): ImageTextResult = error("Not a picture request")
    })

    suspend fun reading(pageId: String, layout: InkPageLayout): Deferred<InkTextEntity?> {
        check(scope.isActive)
        val key = Key(pageId, repository.inkTextGeneration(pageId), layout.hash)
        synchronized(readings) { readings[key]?.let { return it } }
        queue.acquire()
        return synchronized(readings) {
            readings[key]?.let { queue.release(); return@synchronized it }
            val job = scope.async(start = CoroutineStart.LAZY) {
                pages.withPermit {
                    val began = TimeSource.Monotonic.markNow()
                    val regions = try {
                        recognizer.recognize(repository.loadInk(pageId).desktopGeometry().projections, layout)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { null }
                    val row = InkTextEntity(pageId, InkTextRegionsCodec.encode(regions.orEmpty()), regions?.size ?: 0,
                        regions.orEmpty().map { it.confidence }.average().takeUnless { it.isNaN() }?.toFloat() ?: 0f,
                        layout.hash, ENGINE, when {
                            regions == null -> InkTextStatus.Failed
                            regions.isEmpty() -> InkTextStatus.Empty
                            else -> InkTextStatus.Read
                        }, began.elapsedNow().inWholeMilliseconds, System.currentTimeMillis())
                    row.takeIf { repository.saveInkText(it, key.generation) }
                }
            }
            readings[key] = job
            job.invokeOnCompletion { cause ->
                queue.release()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryFailures() = synchronized(readings) {
        readings.entries.removeAll { (_, job) -> job.isCompleted &&
            runCatching { job.getCompleted() }.getOrNull().let { it == null || it.status == InkTextStatus.Failed } }
    }

    companion object {
        const val ENGINE = "${DesktopContentSearch.IMAGE_ENGINE}/ink-lines/1"
    }
}
