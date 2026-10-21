package com.vivenotes.performance

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import com.sun.management.ThreadMXBean
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import com.vivenotes.model.newTable
import com.vivenotes.model.search.FuzzyMatcher
import com.vivenotes.pdf.PdfAtom
import com.vivenotes.pdf.PdfBounds
import com.vivenotes.pdf.groupContent
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.canvas.previewProjection
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Opt-in engineering measurements; no timing assertions and no production profile access. */
object PerformanceBaseline {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected report path and fork number" }
        val runner = BaselineMeasurements()
        measurePureWork(runner)
        measureComputePerformance(runner)
        measureInkPerformance(runner)
        measureStorage(runner)
        val report = buildJsonObject {
            put("schema", "vive-desktop-performance/1")
            put("capturedAt", Instant.now().toString())
            put("fork", args[1].toInt())
            put("runtime", System.getProperty("java.runtime.version"))
            put("vendor", System.getProperty("java.vendor"))
            put("vm", System.getProperty("java.vm.name"))
            put("os", System.getProperty("os.name"))
            put("osVersion", System.getProperty("os.version"))
            put("arch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            put("vmArgs", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("runtimeArtifacts", JsonArray(System.getProperty("java.class.path").split(File.pathSeparator)
                .map(::File).filter { it.isFile && it.extension == "jar" }.sortedBy { it.name }.map { file ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            digest.update(bytes, 0, count)
                        }
                    }
                    buildJsonObject {
                        put("name", file.name)
                        put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                        put("projectArtifact", file.absoluteFile.toPath().startsWith(File(System.getProperty("user.dir")).absoluteFile.toPath()))
                    }
                }))
            put("timingScope", "warm operation wall time; fixture setup/validation excluded; batch averages, not individual-event tail latency")
            put("allocationScope", "current thread only for synchronous cases; unavailable for coroutine/SQLite cases; excludes native memory")
            put("storageScope", "fresh file-backed WAL SQLite under shared/build/performance-tmp; warm OS cache, not fsync/power-loss or cold-disk throughput")
            put("cases", JsonArray(runner.results))
        }
        val output = File(args[0])
        output.absoluteFile.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("Performance baseline: ${output.absolutePath} (${runner.results.size} cases)")
    }

    private fun measurePureWork(runner: BaselineMeasurements) {
        for (count in listOf(100, 500, 2_000)) {
            val doc = performanceDocument(count)
            val state = performanceWorkspace(doc).copy(focusedTextOutlineId = "text-${count - 1}")
            check(doc.outlines.all { state.richTextFor(it.id) != null })
            runner.measure("canvas.resolve_text_buffers", count, documentHash(doc)) {
                doc.outlines.sumOf { state.richTextFor(it.id)!!.blocks.size }
            }
        }
        for (count in listOf(20, 100, 200)) {
            val (doc, ids) = performanceTable(count)
            val state = performanceWorkspace(doc).copy(focusedTextOutlineId = ids.last())
            check(ids.all { state.richTextFor(it) != null })
            runner.measure("canvas.resolve_table_buffers", count, documentHash(doc)) {
                ids.sumOf { state.richTextFor(it)!!.blocks.size }
            }
        }
        for (characters in listOf(10_000, 100_000, 1_000_000)) {
            val doc = performanceDocument(2, characters / 2)
            val state = performanceWorkspace(doc)
            check(state.moveTextBox("text-1", 1f, 1f, false).selectedPage!!.document != doc)
            runner.measure("canvas.move_with_text_preview", characters, documentHash(doc)) {
                state.moveTextBox("text-1", 1f, 1f, recordHistory = false)
            }
        }
        for (count in listOf(250, 1_000, 4_000)) {
            val text = "a ".repeat(count)
            check(FuzzyMatcher.match("a", text)!!.start == 0)
            runner.measure("search.repeated_substring", count, fingerprint(text)) { FuzzyMatcher.match("a", text) }
            val longWord = "abcdefx" + "z".repeat(count)
            check(FuzzyMatcher.match("abcdefg", longWord) != null)
            runner.measure("search.long_word_typo", count, fingerprint(longWord)) { FuzzyMatcher.match("abcdefg", longWord) }
            val atoms = performanceAtoms(count)
            check(groupContent(atoms, 600f, 800f).size == count)
            runner.measure("pdf.equal_spaced_groups", count, fingerprint("rows:$count:60:10")) {
                groupContent(atoms, 600f, 800f)
            }
            val blocks = List(count) { Block(id = "block-$it", runs = listOf(Run("sample paragraph"))) }
            val buffer = RichTextBuffer(blocks)
            val end = buffer.text.length
            check(buffer.select(TextSelection(end)).selection.start == end)
            runner.measure("richtext.select_caret", count, fingerprint("paragraphs:$count:sample paragraph")) {
                buffer.select(TextSelection(end))
            }
            val (source, ranges) = performancePreview(count)
            check(previewProjection(source, ranges).length == source.length + count)
            runner.measure("richtext.link_preview_projection", count, fingerprint(source.text)) {
                previewProjection(source, ranges)
            }
        }
    }

    private fun measureStorage(runner: BaselineMeasurements) {
        for (count in listOf(100, 1_000)) {
            withPerformanceDatabase { db, repository, pageId, advance ->
                val a = performanceDocument(count)
                val b = a.copy(outlines = a.outlines.mapIndexed { index, outline ->
                    if (index == 0) (outline as Outline.Text).copy(x = 1f) else outline
                })
                runBlocking { repository.saveDoc(pageId, a) }
                var flip = false
                runner.measure("storage.save_coalesced", count, documentHash(a), fixedOps = 1, allocations = false) {
                    flip = !flip
                    runBlocking { repository.saveDoc(pageId, if (flip) b else a) }
                    flip
                }
                check(runBlocking { repository.loadDoc(pageId) } is PageLoad.Loaded)
                runner.measure("storage.load_document", count, documentHash(a), fixedOps = 1, allocations = false) {
                    runBlocking { repository.loadDoc(pageId) }
                }
                val checkpointDocuments = List(64) { revision -> a.copy(outlines = a.outlines.mapIndexed { index, outline ->
                    if (index == 0) (outline as Outline.Text).copy(x = 2f + revision) else outline
                }) }
                var checkpointIndex = 0
                runner.measure("storage.save_checkpoint", count, documentHash(a), fixedOps = 1, allocations = false) {
                    advance()
                    val next = checkpointDocuments[checkpointIndex++]
                    runBlocking { repository.saveDoc(pageId, next) }
                    checkpointIndex
                }
                check(runBlocking { db.pageRevisionDao().history(pageId) }.isNotEmpty())
            }
        }
        for (count in listOf(1_000, 10_000, 40_000)) {
            withPerformanceDatabase { db, repository, pageId, advance ->
                val points = ByteArray(1_024) { (it * 31).toByte() }
                val rows = List(count) { performanceInkRow("seed-$it", pageId, it, points) }
                runBlocking {
                    db.inkStrokeDao().insert(rows)
                    repository.saveDoc(pageId, performanceDocument(1))
                }
                var sequence = 0
                val databaseHash = fingerprint("opaque-1024-byte-points:$count:${fingerprint(points)}:${documentHash(performanceDocument(1))}")
                runner.measure("storage.append_ink_coalesced", count, databaseHash,
                    fixedOps = 1, allocations = false) {
                    runBlocking { repository.addStroke(performanceInkRow("append-${sequence++}", pageId, 0, points)) }
                }
                runner.results.last().let { result -> runner.results[runner.results.lastIndex] = JsonObject(result + mapOf(
                    "initialInkRows" to JsonPrimitive(count), "finalInkRows" to JsonPrimitive(count + sequence))) }
                val initialRows = count + sequence
                runner.measure("storage.append_ink_checkpoint", count, databaseHash,
                    fixedOps = 1, allocations = false) {
                    advance()
                    runBlocking { repository.addStroke(performanceInkRow("append-${sequence++}", pageId, 0, points)) }
                }
                runner.results.last().let { result -> runner.results[runner.results.lastIndex] = JsonObject(result + mapOf(
                    "initialInkRows" to JsonPrimitive(initialRows), "finalInkRows" to JsonPrimitive(count + sequence))) }
                check(runBlocking { repository.inkFor(pageId) }.size == count + sequence)
            }
        }
    }
}

internal fun performanceDocument(count: Int, charactersPerOutline: Int = 80): PageDoc = PageDoc(
    outlines = List(count) { index ->
        Outline.Text(id = "text-$index", x = 0f, y = 100f + index * 100f,
            blocks = listOf(Block(id = "block-$index", runs = listOf(Run("x".repeat(charactersPerOutline))))))
    },
)

internal fun performanceWorkspace(doc: PageDoc): WorkspaceState = WorkspaceState.demo().let { base ->
    base.updatePage(base.selectedPageId) { it.copy(document = doc) }
}

internal fun performanceTable(cells: Int): Pair<PageDoc, List<String>> {
    require(cells in listOf(20, 100, 200))
    var id = 0
    val table = newTable(columns = 10, rows = cells / 10, newId = { "table-id-${id++}" }).let { table ->
        table.copy(rows = table.rows.map { row -> row.copy(cells = row.cells.map { cell ->
            cell.copy(blocks = listOf(Block(id = "block-${cell.id}", runs = listOf(Run("cell text")))))
        }) })
    }
    return PageDoc(outlines = listOf(table)) to table.rows.flatMap { it.cells.map { cell -> cell.id } }
}

internal fun performanceAtoms(count: Int): List<PdfAtom> = List(count) { index ->
    PdfAtom("atom-$index", PdfBounds(0f, index * 60f, 10f, index * 60f + 10f))
}

internal fun performancePreview(count: Int): Pair<AnnotatedString, List<IntRange>> {
    val text = List(count) { "link\n" }.joinToString("")
    val source = AnnotatedString(text, paragraphStyles = List(count) { index ->
        AnnotatedString.Range(ParagraphStyle(), index * 5, index * 5 + 5)
    })
    return source to List(count) { index -> (index * 5 + 4) until (index * 5 + 4) }
}

internal fun performanceInkRow(id: String, pageId: String, seq: Int, points: ByteArray): InkStrokeEntity =
    InkStrokeEntity(id = id, pageId = pageId, seq = seq, brushFamily = "marker", brushVersion = 1,
        sizeDp = 3f, colorArgb = 0xff000000.toInt(), epsilon = 0.1f, stabilization = 0,
        minX = 0f, minY = 0f, maxX = 1f, maxY = 1f, points = points,
        enc = "performance-opaque/1", createdAt = 1_000_000L)

internal fun withPerformanceDatabase(block: (NotesDatabase, NotesRepository, String, () -> Unit) -> Unit) {
    val parent = File(System.getProperty("vivenotes.performance.scratch", "build/performance-tmp")).apply { mkdirs() }
    val directory = Files.createTempDirectory(parent.toPath(), "baseline-").toFile()
    val db = NotesDatabase.create(File(directory, "notes.db"))
    var now = 1_000_000L
    val repository = NotesRepository(db, clock = { now })
    try {
        val page = runBlocking {
            repository.createPage(repository.createSection(repository.createNotebook("Performance fixture"), "Section"), "Page")
        }
        block(db, repository, page) { now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS }
    } finally {
        db.close()
        check(directory.deleteRecursively()) { "Could not remove benchmark database" }
    }
}

internal fun fingerprint(text: String): String = fingerprint(text.toByteArray())

private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun documentHash(doc: PageDoc): String = fingerprint(JsonDocumentCodec.encodeToString(doc))

internal fun percentile(samples: List<Double>, quantile: Double): Double {
    require(samples.isNotEmpty() && samples.all { it.isFinite() && it >= 0.0 })
    require(quantile in 0.0..1.0)
    return samples.sorted()[(ceil(samples.size * quantile).toInt() - 1).coerceIn(0, samples.lastIndex)]
}

internal class BaselineMeasurements(
    private val warmupNanos: Long = 400_000_000L,
    private val minWarmupOps: Int = 8,
    private val samples: Int = 15,
    private val targetBatchNanos: Long = 10_000_000L,
) {
    init { require(warmupNanos >= 0 && minWarmupOps > 0 && samples > 0 && targetBatchNanos > 0) }
    val results = mutableListOf<JsonObject>()
    private val threadBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported
    }?.apply { isThreadAllocatedMemoryEnabled = true }
    @Volatile private var sink: Any? = null

    fun measure(name: String, units: Int, fixtureHash: String, fixedOps: Int? = null,
        allocations: Boolean = true, operation: () -> Any?) {
        val warmupStart = System.nanoTime()
        var warmupOps = 0
        do {
            sink = operation()
            warmupOps++
        } while (warmupOps < minWarmupOps || (fixedOps == null && System.nanoTime() - warmupStart < warmupNanos))
        val warmupElapsed = System.nanoTime() - warmupStart
        val batchOps = fixedOps ?: (targetBatchNanos.toDouble() * warmupOps / warmupElapsed)
            .roundToInt().coerceIn(1, 128)
        require(batchOps > 0)
        val times = ArrayList<Double>(samples)
        val allocated = ArrayList<Double>(samples)
        val gcBefore = gcCounts()
        repeat(samples) {
            val bytesBefore = if (allocations) allocatedBytes() else null
            val start = System.nanoTime()
            repeat(batchOps) { sink = operation() }
            val elapsed = System.nanoTime() - start
            val bytesAfter = if (allocations) allocatedBytes() else null
            times += elapsed.toDouble() / batchOps
            if (bytesBefore != null && bytesAfter != null) allocated += (bytesAfter - bytesBefore).toDouble() / batchOps
        }
        val gcAfter = gcCounts()
        results += buildJsonObject {
            put("name", name); put("units", units); put("fixtureSha256", fixtureHash)
            put("warmupOps", warmupOps); put("warmupNanos", warmupElapsed)
            put("sampleCount", samples); put("operationsPerSample", batchOps)
            put("sampleNanosPerOperation", JsonArray(times.map(::JsonPrimitive)))
            put("medianNanos", percentile(times, 0.5)); put("p95BatchAverageNanos", percentile(times, 0.95))
            if (allocated.isNotEmpty()) {
                put("sampleAllocatedBytesPerOperation", JsonArray(allocated.map(::JsonPrimitive)))
                put("medianAllocatedBytes", percentile(allocated, 0.5))
            }
            put("gcCollections", gcAfter.first - gcBefore.first)
            put("gcMillis", gcAfter.second - gcBefore.second)
        }
        println("$name units=$units medianMs=${percentile(times, 0.5) / 1e6}")
    }

    private fun allocatedBytes(): Long? = threadBean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.takeIf { it >= 0 }
    private fun gcCounts(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().let { beans ->
        beans.sumOf { it.collectionCount.coerceAtLeast(0L) } to beans.sumOf { it.collectionTime.coerceAtLeast(0L) }
    }
}
