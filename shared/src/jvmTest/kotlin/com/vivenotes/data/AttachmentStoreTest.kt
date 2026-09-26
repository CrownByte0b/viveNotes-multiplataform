package com.vivenotes.data

import com.vivenotes.data.AttachmentStore.Companion.sha256
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun onlyAHashCanNameAFile() = runBlocking {
        File(root, "secret").writeText("outside the attachments")
        assertNull(store.bytes("../secret"))
        assertNull(store.fileFor("../secret"))
        assertNull(store.bytes("0".repeat(64)), "a missing file reads as nothing")
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
