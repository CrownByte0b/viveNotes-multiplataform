package com.vivenotes.search

import com.vivenotes.ai.imageOf
import com.vivenotes.data.NotesLibrary
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.search.ContentKind
import com.vivenotes.model.search.searchContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchIndexingLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun queryChangeRetainsOneImageFlightAndPublishesOnlyCurrentQueryResults() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repository = library.repository
            val notebook = repository.createNotebook("Notebook")
            val section = repository.createSection(notebook, "Section")
            val page = repository.createPage(section, "Page")
            val attachment = imageOf(48, 48) { _, _ -> 0xffffffff.toInt() }.use { image ->
                library.attachments.import(image.encodeToData()!!.use { it.bytes })!!.attachmentId
            }
            val document = PageDoc(outlines = listOf(
                Outline.Text(id = "typed", blocks = listOf(Block.of("old new typed"))),
                Outline.Image(id = "picture", attachmentId = attachment, height = 48f)))
            repository.saveDoc(page, document)
            val began = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            var cancelled = false
            DesktopContentSearch(repository, library.attachments, SearchProcessPool(2)) { command ->
                when (command.operation) {
                    SearchOperation.Ocr -> {
                        calls.incrementAndGet()
                        began.complete(Unit)
                        try { finish.await() } catch (error: kotlinx.coroutines.CancellationException) { cancelled = true; throw error }
                        WorkerReply(text = "new discovery", confidence = 1f)
                    }
                    SearchOperation.Match -> WorkerReply(hits = searchContent(command.units, command.query))
                    SearchOperation.Project -> error("The immutable live document must not be encoded and sent to a worker")
                }
            }.use { search ->
                val request = SearchRequest(notebook, "old", page, section, "Page", document)
                val old = launch { search.search(request).collect {} }
                withTimeout(5_000) { began.await() }
                old.cancelAndJoin()
                assertFalse(cancelled)
                val typed = CompletableDeferred<SearchResults>()
                val next = async {
                    withTimeout(5_000) {
                        search.search(request.copy(query = "new")).first {
                            if (it.searching && it.hitCount == 1) typed.complete(it)
                            !it.searching
                        }
                    }
                }
                assertTrue(withTimeout(5_000) { typed.await() }.searching)
                finish.complete(Unit)
                val result = next.await()
                assertNull(result.error)
                assertEquals(2, result.hitCount)
                assertEquals(ContentKind.Image, result.pages.single().hits.last().unit.kind)
                assertEquals(1, calls.get())
                assertFalse(cancelled)
            }
        }
    }

    @Test
    fun closingServiceCancelsRetainedImageWork() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repository = library.repository
            val notebook = repository.createNotebook("Notebook")
            val section = repository.createSection(notebook, "Section")
            val page = repository.createPage(section, "Page")
            val id = imageOf(48, 48) { _, _ -> 0xffffffff.toInt() }.use { image ->
                library.attachments.import(image.encodeToData()!!.use { it.bytes })!!.attachmentId
            }
            val document = PageDoc(outlines = listOf(Outline.Image(id = "picture", attachmentId = id, height = 48f)))
            val began = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val search = DesktopContentSearch(repository, library.attachments, SearchProcessPool(2)) { command ->
                when (command.operation) {
                    SearchOperation.Match -> WorkerReply()
                    SearchOperation.Project -> error("Unexpected stored projection")
                    SearchOperation.Ocr -> {
                        began.complete(Unit)
                        try { awaitCancellation() } finally { ended.complete(Unit) }
                    }
                }
            }
            try {
                val collect = launch { search.search(SearchRequest(notebook, "needle", page, section, "Page", document)).collect {} }
                withTimeout(5_000) { began.await() }
                collect.cancelAndJoin()
                search.close()
                withTimeout(5_000) { ended.await() }
            } finally { search.close() }
        }
    }
}
