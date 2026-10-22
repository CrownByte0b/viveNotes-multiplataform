package com.vivenotes.data

import com.vivenotes.data.AttachmentStore.Companion.sha256
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The desktop half of Android's `AttachmentStore`, against a real database and directory. */
class AttachmentStoreTest {

    private val root: File = Files.createTempDirectory("attachments").toFile()
    private val directory = File(root, AttachmentStore.DIRECTORY)
    private val db = NotesDatabase.create(File(root, NotesDatabase.FILE_NAME))
    private val store = AttachmentStore(directory, db)

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun importStoresWebpNamedByItsHashAndClaimsAReference() = runBlocking {
        val imported = assertNotNull(store.import(png(40, 20)))

        assertEquals(ImportedPicture(imported.attachmentId, 40, 20), imported)
        val file = File(directory, imported.attachmentId)
        val stored = file.readBytes()
        assertEquals(imported.attachmentId, stored.sha256(), "the name must be the hash of the bytes")
        assertEquals("RIFF", stored.decodeToString(0, 4))
        assertEquals("WEBP", stored.decodeToString(8, 12))
        assertContentEquals(stored, store.bytes(imported.attachmentId))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })

        val row = assertNotNull(db.attachmentDao().byId(imported.attachmentId))
        assertEquals(AttachmentStore.MIME_TYPE, row.mimeType)
        assertEquals(40 to 20, row.pixelWidth to row.pixelHeight)
        assertEquals(stored.size.toLong(), row.byteCount)
        assertEquals(1, row.refCount)
    }

    @Test
    fun theSamePictureTwiceIsOneFileWithTwoReferences() = runBlocking {
        val first = assertNotNull(store.import(png(12, 12)))
        val second = assertNotNull(store.import(png(12, 12)))

        assertEquals(first, second)
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(2, db.attachmentDao().byId(first.attachmentId)?.refCount)
    }

    @Test
    fun concurrentImportsOfTheSamePictureKeepOneCompleteFileAndEveryReference() = runBlocking<Unit> {
        val source = png(512, 256, transparentLeftHalf = true)
        val imported = coroutineScope {
            List(12) { async(Dispatchers.IO) { assertNotNull(store.import(source)) } }.awaitAll()
        }
        assertEquals(1, imported.distinct().size)
        val id = imported.first().attachmentId
        assertEquals(12, db.attachmentDao().byId(id)?.refCount)
        assertEquals(id, assertNotNull(store.bytes(id)).sha256())
        assertEquals(listOf(id), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun concurrentStoresSharePublicationWithoutLosingReferences() = runBlocking<Unit> {
        val otherStore = AttachmentStore(File(directory, "."), db)
        val source = png(512, 256, transparentLeftHalf = true)
        val start = CompletableDeferred<Unit>()
        val imported = coroutineScope {
            val imports = List(12) { index -> async(Dispatchers.IO) {
                start.await()
                assertNotNull((if (index % 2 == 0) store else otherStore).import(source))
            } }
            start.complete(Unit)
            imports.awaitAll()
        }

        assertEquals(1, imported.distinct().size)
        val id = imported.first().attachmentId
        assertEquals(12, db.attachmentDao().byId(id)?.refCount)
        assertEquals(id, assertNotNull(store.bytes(id)).sha256())
        assertEquals(listOf(id), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun aCompletePublicationIsReusedWithoutMovingOverItsReaders() {
        val expected = "complete imported bytes".encodeToByteArray()
        val target = File(root, expected.sha256()).apply { writeBytes(expected) }
        val staged = File(root, "import.part").apply { writeBytes(expected) }

        AttachmentStore.publishImportedFile(staged, target, expected) { _, _ ->
            error("an already published file must not be moved over")
        }

        assertContentEquals(expected, target.readBytes())
        assertTrue(staged.exists(), "the importer owns and cleans only its own stage")
    }

    @Test
    fun aCompetingCompletePublicationCanReportWindowsAccessDeniedOrAlreadyExists() {
        val expected = "complete imported bytes".encodeToByteArray()
        val target = File(root, expected.sha256())
        val staged = File(root, "import.part").apply { writeBytes(expected) }
        val collisions = listOf(
            AccessDeniedException(staged.path, target.path, "another publisher won"),
            FileAlreadyExistsException(staged.path, target.path, "another publisher won"),
        )

        for (collision in collisions) {
            target.delete()
            AttachmentStore.publishImportedFile(staged, target, expected) { _, destination ->
                destination.writeBytes(expected)
                throw collision
            }
            assertContentEquals(expected, target.readBytes())
            assertEquals(target.name, target.readBytes().sha256())
            assertTrue(staged.exists())
        }
    }

    @Test
    fun accessDeniedWithoutACompletePublishedFileStillFails() {
        val expected = "complete imported bytes".encodeToByteArray()
        val target = File(root, expected.sha256())
        val staged = File(root, "import.part").apply { writeBytes(expected) }
        val failure = AccessDeniedException(staged.path, target.path, "directory denied")

        val thrown = assertFailsWith<AccessDeniedException> {
            AttachmentStore.publishImportedFile(staged, target, expected) { _, _ -> throw failure }
        }

        assertSame(failure, thrown)
        assertFalse(target.exists())
        assertContentEquals(expected, staged.readBytes())
    }

    @Test
    fun aSameLengthMismatchedPublicationDoesNotHideAccessDenied() {
        val expected = "complete imported bytes".encodeToByteArray()
        val different = expected.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val target = File(root, expected.sha256())
        val staged = File(root, "import.part").apply { writeBytes(expected) }
        val failure = AccessDeniedException(staged.path, target.path, "another publisher won")

        val thrown = assertFailsWith<AccessDeniedException> {
            AttachmentStore.publishImportedFile(staged, target, expected) { _, destination ->
                destination.writeBytes(different)
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertContentEquals(different, target.readBytes())
        assertContentEquals(expected, staged.readBytes())
    }

    @Test
    fun anExistingMismatchedPublicationIsNotReplacedOrRepaired() {
        val expected = "complete imported bytes".encodeToByteArray()
        val different = "another incomplete file".encodeToByteArray()
        val target = File(root, expected.sha256()).apply { writeBytes(different) }
        val staged = File(root, "import.part").apply { writeBytes(expected) }

        assertFailsWith<FileAlreadyExistsException> {
            AttachmentStore.publishImportedFile(staged, target, expected) { _, _ ->
                error("a conflicting file must not be replaced")
            }
        }

        assertContentEquals(different, target.readBytes())
        assertContentEquals(expected, staged.readBytes())
    }

    @Test
    fun unrelatedMoveErrorsPropagateEvenWhenACompleteTargetAppears() {
        val expected = "complete imported bytes".encodeToByteArray()
        val target = File(root, expected.sha256())
        val staged = File(root, "import.part").apply { writeBytes(expected) }
        val failure = IOException("unrelated move failure")

        val thrown = assertFailsWith<IOException> {
            AttachmentStore.publishImportedFile(staged, target, expected) { _, destination ->
                destination.writeBytes(expected)
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertContentEquals(expected, target.readBytes())
        assertContentEquals(expected, staged.readBytes())
    }

    @Test
    fun simultaneousDistinctDownloadsAnnounceEveryArrivalAndRemoveTheirStages() = runBlocking<Unit> {
        directory.mkdirs()
        val ids = coroutineScope {
            List(64) { index -> async(Dispatchers.IO) {
                val payload = "download-$index".encodeToByteArray()
                val id = payload.sha256()
                val staged = store.syncBytes.stagingFor(id)
                staged.writeBytes(payload)
                assertTrue(store.syncBytes.publish(staged, id))
                assertContentEquals(payload, store.syncBytes.fileFor(id).readBytes())
                id
            } }.awaitAll()
        }
        assertEquals(64L, store.arrivals.value)
        assertEquals(ids.toSet(), directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun aLargePictureIsKeptAtTheMaximumDimensionAndItsAspect() = runBlocking {
        val imported = assertNotNull(store.import(png(3000, 1500)))

        assertEquals(AttachmentStore.MAX_DIMENSION, imported.pixelWidth)
        assertEquals(1024, imported.pixelHeight)
        Image.makeFromEncoded(store.bytes(imported.attachmentId)!!).use { decoded ->
            assertEquals(AttachmentStore.MAX_DIMENSION to 1024, decoded.width to decoded.height)
        }
    }

    @Test
    fun transparencySurvivesTheReencode() = runBlocking {
        val imported = assertNotNull(store.import(png(16, 16, transparentLeftHalf = true)))

        val pixels = Bitmap.makeFromImage(Image.makeFromEncoded(store.bytes(imported.attachmentId)!!))
        assertEquals(0, Color.getA(pixels.getColor(2, 8)), "a transparent region came back filled")
        assertEquals(255, Color.getA(pixels.getColor(13, 8)))
    }

    @Test
    fun bytesThatAreNotAPictureStoreNothing() = runBlocking {
        assertNull(store.import("not a picture".encodeToByteArray()))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(db.attachmentDao().allIds().isEmpty())
    }

    @Test
    fun onlyAHashCanNameAFile() = runBlocking<Unit> {
        File(root, "secret").writeText("outside the attachments")
        assertNull(store.bytes("../secret"))
        assertNull(store.fileFor("../secret"))
        assertNull(store.bytes("0".repeat(64)), "a missing file reads as nothing")
        assertFailsWith<IllegalArgumentException> { store.syncBytes.fileFor("../secret") }
    }

    /**
     * Sync's view of the store, Android's `AttachmentStore` as `AttachmentBytes`: a download is
     * staged under its own suffix, published by rename, and announced so a page showing a broken
     * frame reads it again.
     */
    @Test
    fun aPublishedDownloadIsAnnouncedAndNeverReplacesTheBytesAlreadyHere() {
        val id = "a".repeat(64)
        val bytes = store.syncBytes
        val staged = bytes.stagingFor(id)
        assertEquals("$id.sync", staged.name, "never the import's .part, so the two cannot tear each other")
        directory.mkdirs()
        staged.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(bytes.publish(staged, id))
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.fileFor(id).readBytes())
        assertFalse(staged.exists())
        assertEquals(1L, store.arrivals.value)

        // A second copy of a digest already here is already these bytes: dropped, and not news.
        bytes.stagingFor(id).writeBytes(byteArrayOf(9))
        assertTrue(bytes.publish(bytes.stagingFor(id), id))
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.fileFor(id).readBytes())
        assertFalse(bytes.stagingFor(id).exists())
        assertEquals(1L, store.arrivals.value)
    }

    private fun png(width: Int, height: Int, transparentLeftHalf: Boolean = false): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(Color.TRANSPARENT)
            val left = if (transparentLeftHalf) width / 2f else 0f
            surface.canvas.drawRect(Rect.makeLTRB(left, 0f, width.toFloat(), height.toFloat()),
                Paint().apply { color = Color.makeRGB(30, 120, 220) })
            surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
}
