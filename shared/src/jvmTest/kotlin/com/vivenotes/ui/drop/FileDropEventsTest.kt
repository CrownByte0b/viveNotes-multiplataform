package com.vivenotes.ui.drop

import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a drag from a file manager carries, as AWT hands it over, read into [DroppedFile]s. */
class FileDropEventsTest {
    private val folder = Files.createTempDirectory("file-drop").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun aFileListIsReadInItsOrderWithoutFolders() {
        val photo = file("Photo Two.JPG", 3)
        val notes = file("notes.txt", 1)
        val inner = File(folder, "inner").apply { mkdir() }

        val dragged = transfer(DataFlavor.javaFileListFlavor to listOf(photo, inner, notes))
        assertTrue(dragged.carriesFiles())
        assertEquals(listOf(photo, notes), dragged.files())
    }

    @Test
    fun aUriListStandsInWhenThereIsNoFileList() {
        val photo = file("Photo Two.JPG", 3)
        val sunset = file("sunset.png", 2)
        val uris = "# dragged from a file manager\r\n${photo.toURI()}\r\nfile://localhost${sunset.toURI().rawPath}\r\n" +
            "https://example.org/remote.png\r\nfile://elsewhere/shared.png\r\nnot a uri\r\n"

        val dragged = transfer(DataFlavor("text/uri-list;class=java.lang.String") to uris)
        assertTrue(dragged.carriesFiles())
        assertEquals(listOf(photo, sunset), dragged.files(), "local files only, spaces decoded")
    }

    @Test
    fun plainTextIsNotFiles() {
        val dragged = StringSelection("/home/someone/picture.png")
        assertFalse(dragged.carriesFiles())
        assertEquals(emptyList(), dragged.files())
    }

    @Test
    fun dataThatFailsToArriveIsNoFiles() {
        val failing = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = throw IOException("the source went away")
        }
        assertTrue(failing.carriesFiles(), "while dragging, only the kind of data is known")
        assertEquals(emptyList(), failing.files())
    }

    @Test
    fun aDroppedFileIsReadWhenAskedAndOnlyUpToItsLimit() = runBlocking {
        val photo = file("photo.png", 10)
        val dropped = droppedFile(photo)
        assertEquals("photo.png", dropped.name)
        assertEquals("png", dropped.extension)

        assertContentEquals(photo.readBytes(), dropped.read(maxBytes = 10), "exactly the limit is still read")
        assertNull(dropped.read(maxBytes = 9), "a longer file is not read at all")
        photo.delete()
        assertNull(dropped.read(maxBytes = 10), "a file gone since the drop")
    }

    @Test
    fun readingStopsAtTheLimitRatherThanLoadingTheWholeFile() {
        val big = file("big.png", 4096)
        assertNull(readAtMost(big, 100))
        assertEquals(4096, readAtMost(big, 4096)?.size)
        assertNull(readAtMost(File(folder, "missing.png"), 100))
        assertNull(readAtMost(folder, 100), "a folder is not a file")
    }

    private fun file(name: String, size: Int) = File(folder, name).apply { writeBytes(ByteArray(size) { it.toByte() }) }

    private fun transfer(vararg data: Pair<DataFlavor, Any>): Transferable = object : Transferable {
        override fun getTransferDataFlavors() = data.map { it.first }.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = data.any { it.first == flavor }
        override fun getTransferData(flavor: DataFlavor): Any =
            data.firstOrNull { it.first == flavor }?.second ?: throw UnsupportedFlavorException(flavor)
    }
}
