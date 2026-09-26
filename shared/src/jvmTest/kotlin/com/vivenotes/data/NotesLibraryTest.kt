package com.vivenotes.data

import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.plainText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesLibraryTest {

    private val root: File = Files.createTempDirectory("notes-library").toFile()
    private val directory = File(root, "not/yet/there")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun notesWrittenBeforeClosingAreThereWhenTheLibraryIsOpenedAgain() = runBlocking<Unit> {
        val pageId = NotesLibrary.open(directory).use { library ->
            library.repository.seedIfEmpty()
            val section = library.repository.observeTree().first().single().liveSections.first()
            val page = library.repository.observePages(section.id).first().single()
            library.repository.saveDoc(page.id, typed("still here"))
            page.id
        }
        assertTrue(File(directory, "notes.db").isFile, "the database is not where the notes belong")

        NotesLibrary.open(directory).use { library ->
            library.repository.seedIfEmpty()

            assertEquals(listOf("My Notebook"), library.repository.observeTree().first().map { it.notebook.name })
            val load = library.repository.loadDoc(pageId)
            assertIs<PageLoad.Loaded>(load)
            assertEquals("still here", load.doc.plainText())
        }
    }

    @Test
    fun maintenanceBacksUpTheNotesAndPurgesExpiredDeletions() = runBlocking<Unit> {
        NotesLibrary.open(directory).use { library ->
            val repository = library.repository
            val sectionId = repository.createSection(repository.createNotebook("Notebook"), "Section")
            val kept = repository.createPage(sectionId, "Kept")
            val expired = repository.createPage(sectionId, "Deleted long ago")
            repository.deletePage(expired)
            library.database.execute("UPDATE pages SET deletedAt = 1 WHERE id = ?", expired)

            library.maintain()

            val backups = File(directory, DatabaseBackupManager.DIRECTORY).listFiles().orEmpty()
            assertEquals(1, backups.count { it.extension == "db" }, "no backup was taken")
            assertNull(library.database.pageDao().byId(expired))
            assertEquals("Kept", repository.pageById(kept)?.title)
        }
    }

    private fun typed(text: String) =
        PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of(text)))))
}
