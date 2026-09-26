package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.ImportedPicture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
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

    /**
     * Regression: the dialog was opened inside the event whose coroutine asked for it — Compose's
     * frame, inside Swing's paint — and its modal loop re-entered both.
     */
    @Test
    fun theDialogOpensAsItsOwnSwingEventNotInsideTheOneThatAskedForIt() = runBlocking {
        var insideCaller = false
        var openedInsideCaller: Boolean? = null
        var openedOnUiThread = false
        val pictures = DesktopPictures(library.attachments) {
            openedInsideCaller = insideCaller
            openedOnUiThread = SwingUtilities.isEventDispatchThread()
            null
        }
        val done = CompletableDeferred<ImportedPicture?>()
        SwingUtilities.invokeLater {
            // Stands in for a Compose frame: a coroutine that starts and runs inside one UI event.
            insideCaller = true
            CoroutineScope(Dispatchers.Unconfined).launch { done.complete(pictures.choose()) }
            insideCaller = false
        }
        assertNull(withTimeout(10_000) { done.await() })
        assertEquals(false, openedInsideCaller, "the dialog ran inside the caller's event")
        assertTrue(openedOnUiThread, "Swing dialogs belong on the UI thread")
    }

    @Test
    fun theDialogOffersOnlyPicturesSkiaCanRead() {
        listOf("png", "jpg", "jpeg", "webp", "gif", "bmp").forEach { assertTrue(it in PictureExtensions, it) }
    }
}
