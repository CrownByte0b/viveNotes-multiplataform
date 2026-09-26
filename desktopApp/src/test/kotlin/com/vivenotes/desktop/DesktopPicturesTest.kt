package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
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

/** A chosen file, through the notes library's attachment store, as the Picture button uses it. */
class DesktopPicturesTest {

    private val directory: File = Files.createTempDirectory("desktop-pictures").toFile()
    private val library = NotesLibrary.open(File(directory, "notes"))

    @AfterTest
    fun tearDown() {
        library.close()
        directory.deleteRecursively()
    }

    @Test
    fun aChosenPictureIsStoredBesideTheNotesAndReadBack() = runBlocking {
        val file = File(directory, "photo.png").apply {
            writeBytes(Surface.makeRasterN32Premul(30, 10).use {
                it.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
            })
        }
        val pictures = DesktopPictures(library.attachments) { file }

        val imported = assertNotNull(pictures.choose())

        assertEquals(30 to 10, imported.pixelWidth to imported.pixelHeight)
        val stored = File(library.directory, "attachments/${imported.attachmentId}")
        assertTrue(stored.isFile, "the picture must live in the notes directory")
        assertContentEquals(stored.readBytes(), pictures.bytes(imported.attachmentId))
    }

    @Test
    fun cancellingOrChoosingSomethingElseStoresNothing() = runBlocking {
        assertNull(DesktopPictures(library.attachments) { null }.choose())
        val text = File(directory, "notes.txt").apply { writeText("not a picture") }
        assertNull(DesktopPictures(library.attachments) { text }.choose())
        assertNull(DesktopPictures(library.attachments) { File(directory, "gone.png") }.choose())
        assertTrue(File(library.directory, "attachments").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theDialogOffersOnlyPicturesSkiaCanRead() {
        listOf("png", "jpg", "jpeg", "webp", "gif", "bmp").forEach { assertTrue(it in PictureExtensions, it) }
    }
}
