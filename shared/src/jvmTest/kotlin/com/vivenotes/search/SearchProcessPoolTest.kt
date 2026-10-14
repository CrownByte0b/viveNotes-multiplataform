package com.vivenotes.search

import com.vivenotes.ai.textImage
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.search.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SearchProcessPoolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun matchingAndOcrUseDistinctPersistentProcessesAndCloseThem() = runBlocking<Unit> {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val pool = SearchProcessPool(2, DebugLog(true, log::add))
        val parentPid = ProcessHandle.current().pid()
        val picture = temporary.newFile("picture.png")
        textImage(680, 230, 52f, listOf(Triple("Quarterly revenue", 20f, 80f),
            Triple("Search pictures", 20f, 170f))).use { image ->
            image.encodeToData()!!.use { picture.writeBytes(it.bytes) }
        }
        val units = List(80) { ContentUnit("page", "section", ContentKind.Text, "box-$it", text = "private needle $it") }
        val pids = try {
            val results = listOf(
                async { pool.execute(WorkerRequest(SearchOperation.Match, query = "needle", units = units)) },
                async { pool.execute(WorkerRequest(SearchOperation.Ocr, imagePath = picture.absolutePath)) },
            ).awaitAll()
            assertEquals(80, results[0].hits.size)
            assertTrue(results[1].text.contains("Quarterly revenue", true), results[1].text)
            assertTrue(results[1].text.contains("Search pictures", true), results[1].text)
            val pids = results.map { it.pid }.toSet()
            assertEquals(2, pids.size)
            assertFalse(parentPid in pids)
            val later = List(2) { async { pool.execute(WorkerRequest(SearchOperation.Match, query = "needle", units = units)) } }.awaitAll()
            assertEquals(pids, later.map { it.pid }.toSet())
            val pictures = List(2) { async {
                pool.execute(WorkerRequest(SearchOperation.Ocr, imagePath = picture.absolutePath))
            } }.awaitAll()
            assertEquals(pids, pictures.map { it.pid }.toSet(), "two images must be processed by separate JVMs")
            assertTrue(pictures.all { it.text.contains("revenue", true) })
            assertTrue(log.none { it.contains("private") || it.contains("needle") || it.contains("revenue") })
            pids
        } finally { pool.close() }
        withTimeout(5_000) { while (pids.any { ProcessHandle.of(it).map { p -> p.isAlive }.orElse(false) }) delay(10) }
        assertFailsWith<IllegalStateException> { pool.execute(WorkerRequest(SearchOperation.Match)) }
    }

    @Test fun cancellingAStalledProcessKillsItAndTheSlotStartsAFreshProcess() = runBlocking<Unit> {
        val signal = File(temporary.root, "started")
        val launches = AtomicInteger()
        val processes = Collections.synchronizedList(mutableListOf<Process>())
        val java = File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}")
        val pool = SearchProcessPool(2, DebugLog()) {
            val main = if (launches.getAndIncrement() == 0) StalledWorker::class.java.name else SearchWorker::class.java.name
            ProcessBuilder(java.absolutePath, "-cp", SearchProcessPool.workerClasspath(), main, signal.absolutePath)
                .start().also { processes.add(it) }
        }
        try {
            val stalled = launch { pool.execute(WorkerRequest(SearchOperation.Match)) }
            withTimeout(10_000) { while (!signal.isFile) delay(10) }
            stalled.cancelAndJoin()
            withTimeout(5_000) { while (processes.first().isAlive) delay(10) }
            val replies = List(2) { async { pool.execute(WorkerRequest(SearchOperation.Match)) } }.awaitAll()
            assertTrue(replies.none { it.failed })
            assertEquals(3, launches.get(), "both slots must remain usable after cancellation")
        } finally { pool.close() }
    }

    @Test fun corruptDocumentsKeepTheirSearchableTitleAndAreReportedUnreadable() = runBlocking<Unit> {
        SearchProcessPool(2).use { pool ->
            val reply = pool.execute(WorkerRequest(SearchOperation.Project, pageId = "p", sectionId = "s",
                title = "Title", body = "broken"))
            assertTrue(reply.unreadable)
            assertEquals("Title", reply.units.single().text)
            val unknown = pool.execute(WorkerRequest(SearchOperation.Project, title = "Title", body = "{}", codec = "future/9"))
            assertTrue(unknown.unreadable)
            val doc = PageDoc(outlines = listOf(Outline.Text(id = "t", blocks = listOf(Block.of("Hello")))))
            val good = pool.execute(WorkerRequest(SearchOperation.Project, body = JsonDocumentCodec.encodeToString(doc)))
            assertFalse(good.unreadable)
            assertEquals("Hello", good.units.single().text)
        }
    }

    @Test fun defaultProcessCountIsBoundedAndNeverSerial() {
        assertEquals(2, SearchProcessPool.defaultParallelism(1))
        assertEquals(2, SearchProcessPool.defaultParallelism(4))
        assertEquals(4, SearchProcessPool.defaultParallelism(128))
    }

    @Test fun aWorkerTimeoutIsAFailureAndDoesNotCancelTheSearchCaller() = runBlocking<Unit> {
        val signal = File(temporary.root, "timeout-started")
        val java = File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}")
        val processes = mutableListOf<Process>()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val pool = SearchProcessPool(2, DebugLog(false, lines::add), timeoutMillis = 1000) {
            ProcessBuilder(java.absolutePath, "-cp", SearchProcessPool.workerClasspath(),
                StalledWorker::class.java.name, signal.absolutePath).start().also { processes.add(it) }
        }
        try {
            assertFailsWith<java.io.IOException> { pool.execute(WorkerRequest(SearchOperation.Match)) }
            currentCoroutineContext().ensureActive()
            withTimeout(5_000) { while (processes.any { it.isAlive }) delay(10) }
        } finally { pool.close() }
        assertTrue(lines.isEmpty(), "normal launches must keep diagnostic logging disabled")
    }
}

object StalledWorker {
    @JvmStatic fun main(args: Array<String>) {
        System.`in`.bufferedReader().readLine()
        File(args.single()).writeText("started")
        Thread.sleep(Long.MAX_VALUE)
    }
}
