package com.vivenotes.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android `DatabaseBackupManagerTest`, against a real WAL-mode file database. */
class DatabaseBackupManagerTest {

    private lateinit var root: File
    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var backups: DatabaseBackupManager
    private var now = 1_000_000L

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("database-backup-manager-test").toFile()
        db = NotesDatabase.create(File(root, "source.db"))
        repository = NotesRepository(db, clock = { now })
        backups = DatabaseBackupManager(
            database = db,
            directory = File(root, "backups"),
            clock = { now },
            intervalMs = 1_000,
            maxBackups = 2,
        )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun vacuumIntoCreatesAValidatedSnapshotAndRotatesOldCopies() = runBlocking<Unit> {
        val notebookId = repository.createNotebook("nb")
        val sectionId = repository.createSection(notebookId, "sec")
        val pageId = repository.createPage(sectionId)
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("first"))))),
        )

        val first = backups.createIfDue(force = true)
        assertNotNull(first)
        assertEquals("first", textInBackup(first, pageId))
        assertNull(backups.createIfDue(), "a fresh backup did not satisfy its interval")

        now += 1_000
        repository.saveDoc(
            pageId,
            PageDoc(outlines = listOf(Outline.Text(id = "text", blocks = listOf(Block.of("second"))))),
        )
        val second = backups.createIfDue()
        assertEquals("second", textInBackup(second!!, pageId))

        now += 1_000
        val third = backups.createIfDue(force = true)
        assertNotNull(third)
        assertEquals(2, backups.validBackups().size)
        assertEquals(setOf(second.name, third.name), backups.validBackups().map { it.name }.toSet())
    }

    /** An interrupted copy is never mistaken for a backup, and the next pass clears it away. */
    @Test
    fun aLeftoverPendingCopyIsRemovedAndNeverListed() = runBlocking<Unit> {
        val backupDirectory = File(root, "backups").apply { mkdirs() }
        val interrupted = File(backupDirectory, "notes-1-x.db.pending").apply { writeText("half a copy") }

        val made = backups.createIfDue(force = true)

        assertTrue(!interrupted.exists(), "the interrupted copy was left behind")
        assertEquals(listOf(made!!.name), backups.validBackups().map { it.name })
    }

    private fun textInBackup(file: File, pageId: String): String {
        val connection = BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY)
        try {
            return connection.prepare("SELECT docJson FROM page_content WHERE pageId = ?").use { statement ->
                statement.bindText(1, pageId)
                check(statement.step())
                val json = statement.getText(0)
                val marker = "\"text\":\""
                json.substringAfter(marker).substringBefore('"')
            }
        } finally {
            connection.close()
        }
    }
}
