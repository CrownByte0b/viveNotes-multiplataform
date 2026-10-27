package com.vivenotes.search

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vivenotes.ai.*
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.db.InkTextStatus
import com.vivenotes.ink.toEntity
import com.vivenotes.ink.selectionLine
import com.vivenotes.model.*
import com.vivenotes.model.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HandwritingSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    @OptIn(InternalComposeUiApi::class)
    @BeforeTest fun graphics() = registerSkikoComposeImplementation()

    private suspend fun DesktopContentSearch.finished(request: SearchRequest) = withTimeout(15_000) {
        search(request).first { !it.searching }.also { assertNull(it.error) }
    }

    private fun fake(command: WorkerRequest): WorkerReply = when (command.operation) {
        SearchOperation.Match -> WorkerReply(hits = searchContent(command.units, command.query))
        SearchOperation.Project -> error("Use a live document in this fixture")
        SearchOperation.Ocr -> {
            assertTrue(command.textLine)
            assertNotNull(command.imageData)
            WorkerReply(text = "handwritten needle", confidence = .95f)
        }
    }

    @Test fun androidHandwritingNotebookFindsBothProseAndInkTableWordsWhenFixtureIsAvailable() = runBlocking {
        val fixture = System.getenv("VIVE_TEST_HANDWRITING_NOTEBOOK")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue("Optional private Android handwriting fixture", fixture?.isFile == true)
        val root = System.getenv("VIVE_TEST_HANDWRITING_SMOKE_DIR")?.let { java.io.File(it) } ?: temporary.newFolder()
        NotesLibrary.open(root).use { library ->
            val imported = library.transfers.importNotebook(fixture!!)
            val section = imported.firstSectionId!!
            DesktopContentSearch(library.repository, library.attachments, SearchProcessPool(2)).use { service ->
                for (word in listOf("death", "treason")) {
                    val result = withTimeout(60_000) {
                        service.search(SearchRequest(imported.notebookId, word, null, section, "", null))
                            .first { !it.searching }
                    }
                    assertNull(result.error)
                    assertEquals(0, result.failedInk)
                    assertTrue(result.pages.flatMap { it.hits }.any { it.unit.kind == ContentKind.Ink },
                        "Missed handwriting query: $word")
                }
            }
        }
    }

    @Test fun realInkIsReadByTheBundledModelThroughWorkerProcesses() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.saveDoc(page, PageDoc.empty())
            // Vector lettering, passed through the same stored AndroidX Ink codec as pen input.
            val paths = listOf(
                listOf(0f to 0f, 0f to 30f), listOf(18f to 0f, 18f to 30f), listOf(0f to 15f, 18f to 15f),
                listOf(44f to 0f, 26f to 0f, 26f to 30f, 44f to 30f), listOf(26f to 15f, 41f to 15f),
                listOf(52f to 0f, 52f to 30f, 70f to 30f),
                listOf(78f to 0f, 78f to 30f, 96f to 30f),
                listOf(112f to 0f, 104f to 4f, 102f to 15f, 104f to 26f, 112f to 30f,
                    120f to 26f, 122f to 15f, 120f to 4f, 112f to 0f))
            val tool = com.vivenotes.byteink.kit.ViveInkTool(sizeDp = 1.8f)
            paths.forEachIndexed { index, path ->
                val inputs = androidx.ink.strokes.MutableStrokeInputBatch().apply {
                    path.forEachIndexed { i, (x, y) -> add(androidx.ink.brush.InputToolType.STYLUS,
                        x + 20f, y + 100f, i * 16L) }
                }
                repo.addStroke(tool.complete(androidx.ink.strokes.Stroke(tool.brush, inputs),
                    "letter-$index", page, index, index.toLong() + 1).row.toEntity())
            }
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)).use { service ->
                val result = withTimeout(45_000) {
                    service.search(SearchRequest(notebook, "hello", null, section, "", null)).first { !it.searching }
                }
                assertNull(result.error)
                assertEquals(0, result.failedInk)
                val row = repo.inkTextFor(listOf(page)).getValue(page)
                assertEquals(InkTextStatus.Read, row.status)
                assertTrue(result.hitCount > 0, "Bundled model reading: ${row.regionsJson}")
                assertEquals(ContentKind.Ink, result.pages.single().hits.single().unit.kind)
            }
        }
    }

    @Test fun handwritingIsIndexedCachedAcrossReopeningAndInvalidatedByInkEdits() = runBlocking {
        val root = temporary.newFolder()
        val calls = AtomicInteger()
        lateinit var request: SearchRequest
        NotesLibrary.open(root).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            request = SearchRequest(notebook, "needle", page, section, "Page", PageDoc.empty())
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation == SearchOperation.Ocr) calls.incrementAndGet()
                fake(command)
            }.use { service ->
                val result = service.finished(request)
                assertEquals(1, result.inkTotal)
                assertEquals(1, result.inkDone)
                assertEquals(ContentKind.Ink, result.pages.single().hits.single().unit.kind)
                assertEquals(setOf("source"), result.pages.single().hits.single().unit.inkStrokeIds)
                assertEquals(InkTextStatus.Read, repo.inkTextFor(listOf(page)).getValue(page).status)
                assertEquals(1, calls.get())
                assertEquals(1, service.finished(request.copy(query = "handwritten")).hitCount)
                assertEquals(1, calls.get())
                repo.addStroke(selectionLine("second", page, y = 300f))
                assertTrue(repo.inkTextFor(listOf(page)).isEmpty())
                assertTrue(service.finished(request).hitCount > 0)
                assertTrue(calls.get() > 1)
            }
        }
        val savedCalls = calls.get()
        NotesLibrary.open(root).use { library ->
            DesktopContentSearch(library.repository, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation == SearchOperation.Ocr) calls.incrementAndGet()
                fake(command)
            }.use { assertTrue(it.finished(request).hitCount > 0) }
        }
        assertEquals(savedCalls, calls.get())
    }

    @Test fun editingDuringRecognitionDiscardsTheStaleRowAndRefreshesAnOpenQuery() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation != SearchOperation.Ocr) fake(command) else {
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit); release.await()
                        WorkerReply(text = "stale", confidence = .95f)
                    } else WorkerReply(text = "fresh", confidence = .95f)
                }
            }.use { service ->
                val result = async {
                    withTimeout(15_000) {
                        service.search(SearchRequest(notebook, "fresh", page, section, "Page", PageDoc.empty()))
                            .first { !it.searching && it.hitCount > 0 }
                    }
                }
                withTimeout(5_000) { entered.await() }
                repo.addStroke(selectionLine("new", page, y = 300f))
                release.complete(Unit)
                assertNull(result.await().error)
                assertTrue(InkTextRegionsCodec.decode(repo.inkTextFor(listOf(page)).getValue(page).regionsJson)
                    .all { it.text == "fresh" })
            }
        }
    }

    @Test fun queryChangesRetainOneInkFlightAndFailuresCanBeRetried() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            var fail = false
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation != SearchOperation.Ocr) fake(command) else {
                    calls.incrementAndGet(); entered.complete(Unit); release.await()
                    if (fail) WorkerReply(failed = true) else fake(command)
                }
            }.use { service ->
                val request = SearchRequest(notebook, "old", page, section, "Page", PageDoc.empty())
                val first = launch { service.search(request).collect {} }
                withTimeout(5_000) { entered.await() }
                first.cancelAndJoin()
                val next = async { service.finished(request.copy(query = "needle")) }
                release.complete(Unit)
                assertEquals(1, next.await().hitCount)
                assertEquals(1, calls.get())
                // Ink changes invalidate the persisted success before a failing attempt.
                fail = true
                repo.addStroke(selectionLine("new", page))
                val failed = service.finished(request)
                assertEquals(1, failed.failedInk)
                val failedCalls = calls.get()
                assertEquals(1, service.finished(request).failedInk)
                assertEquals(failedCalls, calls.get())
                service.retryFailedHandwriting()
                fail = false
                assertEquals(1, service.finished(request.copy(query = "needle")).hitCount)
                assertTrue(calls.get() > failedCalls)
            }
        }
    }

    @Test fun movingAndErasingInkRefreshesTheOpenQuery() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2), ::fake).use { service ->
                val changes = kotlinx.coroutines.channels.Channel<SearchResults>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val collect = launch {
                    service.search(SearchRequest(notebook, "needle", page, section, "Page", PageDoc.empty()))
                        .filter { !it.searching }.collect { changes.send(it) }
                }
                try {
                    val first = withTimeout(10_000) { changes.receive() }
                    val top = first.pages.single().hits.single().unit.inkBounds!!.top
                    val ink = repo.loadInk(page)
                    val move = ink.geometry!!.selectInRect(ink, 0f, 180f, 130f, 220f)!!.move(0f, 100f)!!
                    repo.applyInkEdit(page, move.edits.single())
                    withTimeout(10_000) {
                        while (true) {
                            val result = changes.receive()
                            if (result.pages.firstOrNull()?.hits?.firstOrNull()?.unit?.inkBounds?.top == top + 100f) break
                        }
                    }
                    repo.applyInkEdit(page, com.vivenotes.data.InkEdit.EraseStrokes(setOf("source")))
                    withTimeout(10_000) {
                        while (true) {
                            val result = changes.receive()
                            if (result.inkTotal == 0 && result.hitCount == 0) break
                        }
                    }
                } finally { collect.cancelAndJoin() }
            }
        }
    }

    @Test fun closingSearchCancelsRetainedHandwritingWork() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            val entered = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val service = DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation != SearchOperation.Ocr) fake(command) else {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { ended.complete(Unit) }
                }
            }
            try {
                val collect = launch {
                    service.search(SearchRequest(notebook, "needle", page, section, "Page", PageDoc.empty())).collect {}
                }
                withTimeout(5_000) { entered.await() }
                collect.cancelAndJoin()
                service.close()
                withTimeout(5_000) { ended.await() }
                assertTrue(repo.inkTextFor(listOf(page)).isEmpty())
            } finally { service.close() }
        }
    }

    @Test fun inkOnlyTableGeometryChangeReindexesAndAnEmptyReadingIsCached() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.addStroke(selectionLine(pageId = page))
            val calls = AtomicInteger()
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { command ->
                if (command.operation == SearchOperation.Ocr) {
                    calls.incrementAndGet(); WorkerReply()
                } else fake(command)
            }.use { service ->
                val request = SearchRequest(notebook, "needle", page, section, "Page", PageDoc.empty())
                assertEquals(0, service.finished(request).hitCount)
                val afterFirst = calls.get()
                assertTrue(afterFirst > 0)
                service.finished(request)
                assertEquals(afterFirst, calls.get())
                assertEquals(InkTextStatus.Empty, repo.inkTextFor(listOf(page)).getValue(page).status)
                val grid = Outline.Table(id = "table", x = 0f, y = 160f, inkOnly = true,
                    columns = listOf(200f), rows = listOf(TableRow(id = "row", minHeight = 80f,
                        cells = listOf(TableCell(id = "cell")))))
                service.finished(request.copy(liveDocument = PageDoc(outlines = listOf(grid))))
                assertTrue(calls.get() > afterFirst)
            }
        }
    }
}
