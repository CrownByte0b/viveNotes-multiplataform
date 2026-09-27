package com.vivenotes.desktop

import com.vivenotes.data.NotesLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The File tab's `.vive` dialogs on desktop, over a real notes library. */
class DesktopNotebookFilesTest {

    private val directory: File = Files.createTempDirectory("desktop-notebook-files").toFile()
    private val library = NotesLibrary.open(File(directory, "notes"), cacheDirectory = File(directory, "cache"))

    @AfterTest
    fun tearDown() {
        library.close()
        directory.deleteRecursively()
    }

    @Test
    fun aNameThatEndsInViveIsKeptAsChosen() {
        assertEquals(File(directory, "Biology.vive"), withViveExtension(File(directory, "Biology.vive")))
        assertEquals(File(directory, "Biology.VIVE"), withViveExtension(File(directory, "Biology.VIVE")))
    }

    /** The dialog confirmed replacing only the typed name; the name with `.vive` added is never overwritten. */
    @Test
    fun anAddedExtensionTakesTheNextFreeNameRatherThanReplaceAFile() {
        assertEquals(File(directory, "Biology.vive"), withViveExtension(File(directory, "Biology")))
        File(directory, "Biology.vive").writeText("an earlier export")
        File(directory, "Biology (1).vive").writeText("another")

        assertEquals(File(directory, "Biology (2).vive"), withViveExtension(File(directory, "Biology")))
    }

    @Test
    fun cancellingEitherDialogChoosesNothing() = runBlocking {
        val files = DesktopNotebookFiles(library.transfers, chooseSave = { _, _ -> null }, chooseOpen = { null })

        assertNull(files.chooseExportDestination("Biology.vive"))
        assertNull(files.chooseImportSource())
    }

    @Test
    fun eachDialogStartsWhereTheLastOneEnded() = runBlocking {
        val exports = File(directory, "exports").apply { mkdirs() }
        val seen = mutableListOf<File?>()
        val files = DesktopNotebookFiles(
            library.transfers,
            chooseSave = { name, folder -> seen += folder; File(exports, name) },
            chooseOpen = { folder -> seen += folder; File(exports, "Biology.vive") },
        )

        assertEquals(File(exports, "Biology.vive").path, files.chooseExportDestination("Biology.vive"))
        files.chooseImportSource()

        assertEquals(listOf(null, exports), seen)
    }

    @Test
    fun theChosenFilesAreWrittenAndReadByTheLibrarysTransfers() = runBlocking {
        library.repository.seedIfEmpty()
        val notebook = library.repository.observeTree().first().single().notebook
        val exports = File(directory, "exports").apply { mkdirs() }
        val files = DesktopNotebookFiles(
            library.transfers,
            chooseSave = { name, _ -> File(exports, name.removeSuffix(".vive")) },
            chooseOpen = { File(exports, "My Notebook.vive") },
        )

        val destination = files.chooseExportDestination("My Notebook.vive")!!
        val exported = files.export(notebook.id, destination)
        val imported = files.import(files.chooseImportSource()!!)

        assertEquals(File(exports, "My Notebook.vive").path, destination, "the extension is added")
        assertTrue(File(destination).length() == exported.byteCount)
        assertEquals(notebook.id, imported.notebookId)
        assertTrue(!imported.created, "the same library already holds it")
        assertTrue(File(directory, "cache").isDirectory, "staging happens in the cache directory")
    }
}
