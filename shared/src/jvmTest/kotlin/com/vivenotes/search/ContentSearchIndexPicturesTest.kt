package com.vivenotes.data

import com.vivenotes.search.*
import kotlinx.coroutines.flow.first

import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.AttachmentTextEntity
import com.vivenotes.data.db.ImageTextStatus
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.search.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The feature the user asked for, end to end minus the model: a word inside a picture is findable,
 * and it is findable **once per place it actually is**.
 *
 * `ContentSearchTest` covers the same rules over the pure model. This covers them over the real
 * query path: stored documents, the cache table, the index's per-page decode, and the live page.
 */
class ContentSearchIndexPicturesTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var index: DesktopContentSearch
    private lateinit var file: File

    private lateinit var notebookId: String
    private lateinit var sectionId: String

    @Before
    fun setUp() = runBlocking {
        file = java.nio.file.Files.createTempDirectory("search-pictures").toFile()
        db = NotesDatabase.create(File(file, "notes.db"))
        repository = NotesRepository(db)
        index = DesktopContentSearch(repository, AttachmentStore(File(file, "attachments"), db), SearchProcessPool(2))

        notebookId = repository.createNotebook("Notebook")
        sectionId = repository.createSection(notebookId, "Section")
        Unit
    }

    @After
    fun tearDown() {
        index.close()
        db.close()
        file.deleteRecursively()
    }

    private suspend fun storePicture(id: String, text: String, status: ImageTextStatus = ImageTextStatus.Read) {
        db.attachmentDao().insert(
            AttachmentEntity(
                id = id,
                mimeType = "image/webp",
                pixelWidth = 800,
                pixelHeight = 600,
                byteCount = 1024,
                refCount = 1,
                createdAt = 1L,
            ),
        )
        repository.storeImageText(
            AttachmentTextEntity(
                attachmentId = id,
                text = text,
                lineCount = text.lines().size,
                confidence = 0.9f,
                engine = DesktopContentSearch.IMAGE_ENGINE,
                status = status,
                durationMs = 1,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun page(title: String, vararg outlines: Outline): String {
        val pageId = repository.createPage(sectionId, title)
        repository.saveDoc(pageId, PageDoc(outlines = outlines.toList()))
        return pageId
    }

    private fun picture(id: String, attachmentId: String, y: Float = 0f) =
        Outline.Image(id = id, y = y, attachmentId = attachmentId, height = 100f)

    @Test
    fun aWordInsideAPictureIsFound() = runBlocking {
        storePicture("sha-one", "Quarterly revenue\nup 14 percent")
        val pageId = page("Slides", picture("frame", "sha-one"))

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        val hit = outcome.pages.single { it.pageId == pageId }.hits.single()
        assertEquals(ContentKind.Image, hit.unit.kind)
        // The line, not the whole picture: the snippet a panel shows is a real line of it.
        assertEquals("Quarterly revenue", hit.unit.text)
        // The outline, so opening the hit can select the picture on the page.
        assertEquals("frame", hit.unit.boxId)
        assertEquals("sha-one", hit.unit.attachmentId)
        assertEquals(1, outcome.imagesTotal)
    }

    @Test
    fun aPicturePlacedTwiceOnOnePageIsOneResult() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        page("Slides", picture("frame-a", "sha-one"), picture("frame-b", "sha-one", y = 400f))

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        assertEquals(1, outcome.hitCount)
        assertEquals("frame-a", outcome.pages.single().hits.single().unit.boxId)
    }

    @Test
    fun theSamePictureOnTwoPagesIsOneResultPerPage() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        val first = page("Slides", picture("frame-a", "sha-one"))
        val second = page("Notes", picture("frame-b", "sha-one"))

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        assertEquals(2, outcome.hitCount)
        assertEquals(
            setOf(first, second),
            outcome.pages.mapTo(mutableSetOf()) { it.pageId },
        )
        // One picture, one row of stored text, two places to go.
        assertEquals(1, outcome.imagesTotal)
    }

    @Test
    fun anUnreadPictureIsReportedButFindsNothing() = runBlocking {
        db.attachmentDao().insert(
            AttachmentEntity(
                id = "sha-unread",
                mimeType = "image/webp",
                pixelWidth = 8,
                pixelHeight = 8,
                byteCount = 8,
                refCount = 1,
                createdAt = 1L,
            ),
        )
        page("Slides", picture("frame", "sha-unread"))

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        assertEquals(0, outcome.hitCount)
        // Reported so the indexer knows to read it — which is how the result appears later.
        assertEquals(1, outcome.imagesTotal)
        assertEquals(1, outcome.failedImages)
    }

    @Test
    fun aPictureReadAsEmptyFindsNothing() = runBlocking {
        storePicture("sha-blank", "", ImageTextStatus.Empty)
        page("Slides", picture("frame", "sha-blank"))

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        assertEquals(0, outcome.hitCount)
    }

    @Test
    fun aTypedLineOutranksTheSameWordInAPicture() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        page(
            "Slides",
            picture("frame", "sha-one"),
            Outline.Text(id = "box", y = 300f, blocks = listOf(Block.of("Quarterly revenue"))),
        )

        val outcome = index.search(SearchRequest(notebookId, "revenue", null, sectionId, "", null)).first { !it.searching }

        val hits = outcome.pages.single().hits
        assertEquals(2, hits.size)
        assertEquals(ContentKind.Text, hits.first().unit.kind)
        assertEquals(ContentKind.Image, hits.last().unit.kind)
    }

    @Test
    fun theOpenPagesPicturesAreSearchedFromLiveStateNotStorage() = runBlocking {
        storePicture("sha-one", "Quarterly revenue")
        // Stored with no pictures at all: the page has just been edited and not yet autosaved.
        val pageId = page("Slides")

        val outcome = index.search(SearchRequest(notebookId, "revenue", pageId, sectionId, "Slides",
            PageDoc(outlines = listOf(picture("frame", "sha-one"))))).first { !it.searching }

        assertEquals(1, outcome.hitCount)
        assertEquals(1, outcome.imagesTotal)
    }
}
