package com.vivenotes.search

import com.vivenotes.ai.imageOf
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.PictureTextHighlighter
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ocr.ImageLineLayout
import com.vivenotes.model.ocr.ImageTextLayout
import com.vivenotes.model.search.imageUnits
import com.vivenotes.model.search.ImagePlacement
import com.vivenotes.model.search.searchContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class PictureTextHighlighterTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun layout(text: String, y: Float = .2f) = ImageTextLayout.encode(text.lines().map { line ->
        ImageLineLayout(floatArrayOf(.1f, y, .9f, y, .9f, y + .1f, .1f, y + .1f),
            FloatArray(line.length + 1) { it.toFloat() / line.length })
    })

    private suspend fun picture(library: NotesLibrary) = imageOf(64, 64) { _, _ -> -1 }.use { image ->
        library.attachments.import(image.encodeToData()!!.use { it.bytes })!!.attachmentId
    }

    @Test fun decodedLayoutsAnswerEveryOccurrenceAndReloadOnlyAfterVersionChanges() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val id = picture(library)
            val repo = library.repository
            fun row(text: String, y: Float) = AttachmentTextEntity(id, text, text.lines().size, .9f,
                DesktopContentSearch.IMAGE_ENGINE, ImageTextStatus.Read, 0, 0, layout(text, y))
            repo.storeImageText(row("Quarterly totals\nGrand total", .2f))
            val highlighter = PictureTextHighlighter(repo)
            assertEquals(2, highlighter.forQuery(setOf(id), "total", 1).getValue(id).count)
            assertEquals(1, highlighter.forQuery(setOf(id), "grand", 1).getValue(id).count)
            assertTrue(highlighter.forQuery(setOf(id), "zebra", 1).isEmpty())
            assertTrue(highlighter.forQuery(setOf(id), "", 1).isEmpty())
            val hit = searchContent(imageUnits(ImagePlacement("page", "section", "frame", id), listOf("Grand total")), "total").single()
            val focus = highlighter.forLine(id, 1, hit.spans, 1)!!
            assertEquals(.1f + .8f * 6 / 11, focus.left, .001f)
            assertEquals(.2f, focus.top, .001f)
            assertNull(highlighter.forLine(id, 7, hit.spans, 1))

            repo.storeImageText(row("replacement", .7f))
            assertTrue(highlighter.forQuery(setOf(id), "replacement", 1).isEmpty())
            assertEquals(.7f, highlighter.forQuery(setOf(id), "replacement", 2).getValue(id).top, .001f)
            assertTrue(highlighter.forQuery(emptySet(), "replacement", 2).isEmpty())
        }
    }

    @Test fun absentAndUnplacedAndUnreadableLayoutsStillAllowOrdinarySearch() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val id = picture(library)
            val highlighter = PictureTextHighlighter(library.repository)
            assertTrue(highlighter.forQuery(setOf(id), "read", 0).isEmpty())
            library.repository.storeImageText(AttachmentTextEntity(id, "read", 1, 1f, "test", ImageTextStatus.Read, 0, 0))
            assertTrue(highlighter.forQuery(setOf(id), "read", 1).isEmpty())
            assertEquals("read", library.repository.imageTextFor(setOf(id)).getValue(id).text)
            library.repository.storeImageText(AttachmentTextEntity(id, "read", 1, 1f, "test", ImageTextStatus.Read, 0, 0, byteArrayOf(99)))
            assertTrue(highlighter.forQuery(setOf(id), "read", 2).isEmpty())
        }
    }

    @Test fun oldTextRemainsSearchableWhileItsWorkerReplacesItAndStoresPositions() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val repo = library.repository
            val notebook = repo.createNotebook("Notebook")
            val section = repo.createSection(notebook, "Section")
            val page = repo.createPage(section, "Page")
            val id = picture(library)
            val document = PageDoc(outlines = listOf(Outline.Image(id = "frame", attachmentId = id, height = 64f)))
            repo.saveDoc(page, document)
            repo.storeImageText(AttachmentTextEntity(id, "old total", 1, .9f, "old-engine", ImageTextStatus.Read, 0, 0))
            val began = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val bytes = layout("new total")
            DesktopContentSearch(repo, library.attachments, SearchProcessPool(2)) { request ->
                when (request.operation) {
                    SearchOperation.Ocr -> { began.complete(Unit); release.await(); WorkerReply(text = "new total", confidence = .9f, imageLayout = bytes) }
                    SearchOperation.Match -> WorkerReply(hits = searchContent(request.units, request.query))
                    else -> error("live projection expected")
                }
            }.use { service ->
                val updates = Channel<SearchResults>(Channel.UNLIMITED)
                val job = launch {
                    service.search(SearchRequest(notebook, "total", page, section, "Page", document)).collect { updates.send(it) }
                }
                try {
                    withTimeout(10_000) { began.await() }
                    val initial = withTimeout(10_000) { var result = updates.receive(); while (result.hitCount == 0) result = updates.receive(); result }
                    assertTrue(initial.searching)
                    assertEquals("old total", initial.pages.single().hits.single().unit.text)
                    release.complete(Unit)
                    val finished = withTimeout(10_000) { var result = updates.receive(); while (result.searching) result = updates.receive(); result }
                    assertEquals(1, finished.hitCount)
                    val hit = finished.pages.single().hits.single()
                    assertEquals("new total", hit.unit.text)
                    assertContentEquals(bytes, repo.imageTextLayoutsFor(listOf(id)).getValue(id).layout)
                    assertEquals(DesktopContentSearch.IMAGE_ENGINE, repo.imageTextFor(setOf(id)).getValue(id).engine)
                    assertEquals(1, service.pictureHighlight(hit)!!.count)
                } finally { release.complete(Unit); job.cancelAndJoin() }
            }
        }
    }

    @Test fun newDatabaseReadingsRefreshTheOpenPagesHighlightsWithoutAQueryChange() = runBlocking {
        NotesLibrary.open(temporary.newFolder()).use { library ->
            val id = picture(library)
            DesktopContentSearch(library.repository, library.attachments, SearchProcessPool(2)).use { service ->
                val updates = Channel<Map<String, com.vivenotes.model.ocr.PictureHighlight>>(Channel.UNLIMITED)
                val job = launch { service.pictureHighlights(setOf(id), "total").collect { updates.send(it) } }
                try {
                    assertTrue(withTimeout(10_000) { updates.receive() }.isEmpty())
                    library.repository.storeImageText(AttachmentTextEntity(id, "total", 1, .9f,
                        DesktopContentSearch.IMAGE_ENGINE, ImageTextStatus.Read, 0, 0, layout("total")))
                    val boxes = withTimeout(10_000) { var result = updates.receive(); while (result.isEmpty()) result = updates.receive(); result }
                    assertEquals(1, boxes.getValue(id).count)
                    assertTrue(service.pictureHighlights(setOf(id), "").first().isEmpty())
                    assertTrue(service.pictureHighlights(emptySet(), "total").first().isEmpty())
                } finally { job.cancelAndJoin() }
            }
        }
    }
}
