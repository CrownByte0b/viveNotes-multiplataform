package com.vivenotes.search

import com.vivenotes.ai.textImage
import com.vivenotes.data.*
import com.vivenotes.data.db.*
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.*
import com.vivenotes.model.search.ContentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.test.*

class DesktopContentSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun text(id: String, words: String) = Outline.Text(id = id, blocks = listOf(Block.of(words)))
    private suspend fun DesktopContentSearch.finished(request: SearchRequest) = withTimeout(45_000) {
        search(request).first { !it.searching }.also { assertNull(it.error) }
    }

    @Test fun realPicturesAreReadInParallelCachedOnDiskAndNotReadAgainAfterReopening() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val logger = DebugLog(true, log::add)
        lateinit var notebook: String
        lateinit var section: String
        lateinit var ids: List<String>
        NotesLibrary.open(root).use { library ->
            val repo = library.repository
            notebook = repo.createNotebook("Notebook")
            section = repo.createSection(notebook, "Section")
            ids = listOf("Quarterly revenue", "Search revenue").map { words ->
                val bytes = textImage(680, 150, 52f, listOf(Triple(words, 24f, 90f))).use { image ->
                    image.encodeToData()!!.use { it.bytes }
                }
                library.attachments.import(bytes)!!.attachmentId
            }
            val page = repo.createPage(section, "Slides")
            repo.saveDoc(page, PageDoc(outlines = listOf(text("typed", "Quarterly revenue")) +
                ids.mapIndexed { i, id -> Outline.Image(id = "image-$i", attachmentId = id, height = 100f) }))
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2, logger), logger).use { search ->
                val emissions = mutableListOf<SearchResults>()
                withTimeout(45_000) {
                    search.search(SearchRequest(notebook, "revenue", null, section, "", null))
                        .onEach { emissions.add(it) }.first { !it.searching }
                }
                assertTrue(emissions.any { it.hitCount == 1 && it.searching && it.imagesDone == 0 })
                assertTrue(emissions.any { it.imagesDone == 1 })
                val result = emissions.last()
                assertEquals(3, result.hitCount)
                assertEquals(ContentKind.Text, result.pages.single().hits.first().unit.kind)
                assertEquals(0, result.failedImages)
                assertEquals(2, repo.imageTextFor(ids.toSet()).size)
                assertEquals(1, log.count { it.contains("OCR started") })
            }
        }
        NotesLibrary.open(root).use { library ->
            DesktopContentSearch(library.repository, library.attachments, SearchProcessPool(2, logger), logger).use { search ->
                assertEquals(3, search.finished(SearchRequest(notebook, "revenue", null, section, "", null)).hitCount)
                assertEquals(1, log.count { it.contains("OCR started") }, "reopening should reuse persisted OCR")
                assertTrue(log.none { it.contains("Quarterly") || it.contains("revenue") || it.contains("Slides") })
            }
        }
    }

    @Test fun unsavedLiveTextReplacesStorageAndOtherNotebooksAndDeletedPagesAreExcluded() = runBlocking<Unit> {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Selected")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Live")
            repo.saveDoc(page, PageDoc(outlines = listOf(text("old", "storage word"))))
            val deleted = repo.createPage(section, "secret")
            repo.deletePage(deleted)
            val otherSection = repo.createSection(repo.createNotebook("Other"), "Other section")
            repo.createPage(otherSection, "secret")
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)).use { search ->
                val live = SearchRequest(notebook, "secret", page, section, "Live",
                    PageDoc(outlines = listOf(text("live", "unsaved secret"))))
                val result = search.finished(live)
                assertEquals("live", result.pages.single().hits.single().unit.boxId)
                assertEquals(0, search.finished(live.copy(query = "storage")).hitCount)
                assertEquals(0, search.finished(live.copy(query = "  ")).hitCount)
                assertEquals(1, search.finished(live.copy(query = "storage", liveDocument = null)).hitCount)
                repo.closeNotebook(notebook)
                assertEquals(0, search.finished(live).hitCount)
            }
        }
    }

    @Test fun externalDocumentReplacementAndDeletionRefreshAnOpenQuery() = runBlocking<Unit> {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            repo.saveDoc(page, PageDoc(outlines = listOf(text("box", "needle"))))
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)).use { search ->
                val changes = kotlinx.coroutines.channels.Channel<SearchResults>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val collect = launch { search.search(SearchRequest(notebook, "needle", null, section, "", null))
                    .filter { !it.searching }.collect { changes.send(it) } }
                try {
                    assertEquals(1, withTimeout(15_000) { changes.receive() }.hitCount)
                    repo.saveDoc(page, PageDoc(outlines = listOf(text("box", "replaced"))))
                    withTimeout(15_000) { while (changes.receive().hitCount != 0) { } }
                    repo.renamePage(page, "needle")
                    withTimeout(15_000) { while (changes.receive().hitCount != 1) { } }
                    repo.deletePage(page)
                    withTimeout(15_000) { while (changes.receive().hitCount != 0) { } }
                } finally { collect.cancelAndJoin() }
            }
        }
    }

    @Test fun blankFailuresAndOldEnginesHaveExplicitCacheAndRetryBehavior() = runBlocking<Unit> {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val blank = com.vivenotes.ai.imageOf(160, 80) { _, _ -> 0xFFFFFFFF.toInt() }.use { image ->
                library.attachments.import(image.encodeToData()!!.use { it.bytes })!!
            }
            val missing = "a".repeat(64)
            library.database.attachmentDao().insert(AttachmentEntity(missing, "image/webp", 1, 1, 1, createdAt = 1))
            val page = repo.createPage(section, "Pictures")
            repo.saveDoc(page, PageDoc(outlines = listOf(
                Outline.Image(id = "blank", attachmentId = blank.attachmentId, height = 80f),
                Outline.Image(id = "missing", attachmentId = missing, height = 80f))))
            repo.storeImageText(AttachmentTextEntity(blank.attachmentId, "stale needle", 1, 1f,
                "previous-engine", ImageTextStatus.Read, 0, 1))
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)).use { search ->
                val request = SearchRequest(notebook, "needle", null, section, "", null)
                val result = search.finished(request)
                assertEquals(0, result.hitCount, "stale model text must never appear")
                assertEquals(1, result.failedImages)
                val rows = repo.imageTextFor(setOf(blank.attachmentId, missing))
                assertEquals(ImageTextStatus.Empty, rows[blank.attachmentId]!!.status)
                assertEquals(ImageTextStatus.Failed, rows[missing]!!.status)
                assertEquals(DesktopContentSearch.IMAGE_ENGINE, rows[blank.attachmentId]!!.engine)
                assertEquals(1, search.finished(request).failedImages)
                search.retryFailedImages()
                assertEquals(setOf(blank.attachmentId), repo.imageTextFor(setOf(blank.attachmentId, missing)).keys)
            }
        }
    }

    @Test fun trueTruncationIsDistinctFromExactlyTheResultLimit() = runBlocking<Unit> {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)).use { search ->
                suspend fun find(count: Int): SearchResults {
                    repo.saveDoc(page, PageDoc(outlines = List(count) { text("box-$it", "needle") }))
                    return search.finished(SearchRequest(notebook, "needle", null, section, "", null))
                }
                assertFalse(find(120).truncated)
                assertTrue(find(121).truncated)
                assertEquals(120, find(140).hitCount)
            }
        }
    }
}
