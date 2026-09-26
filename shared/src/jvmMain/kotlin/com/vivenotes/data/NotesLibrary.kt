package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * One open notes database and the upkeep it needs: what a desktop process opens at start and
 * closes at exit. The desktop's stand-in for the Android `NotesApplication` container, for storage.
 */
class NotesLibrary private constructor(
    /** Holds `notes.db`, its WAL files, the backup directory and the pictures. */
    val directory: File,
    internal val database: NotesDatabase,
    val repository: NotesRepository,
    /** Pictures, in the `attachments` directory beside the database. */
    val attachments: AttachmentStore,
    private val backups: DatabaseBackupManager,
) : AutoCloseable {

    /**
     * The upkeep Android hands to WorkManager and to its first screen: a verified backup once the
     * newest is a day old, and the purge of deletions past their seven-day window. Each half is
     * attempted whatever happens to the other, and a failure only waits for the next pass — both
     * are idempotent, and neither is a reason to stop the notes from opening.
     */
    suspend fun maintain() {
        runMaintenance("backup") { backups.createIfDue() }
        runMaintenance("deletion purge") { repository.purgeExpiredDeletions() }
    }

    override fun close() = database.close()

    private suspend fun runMaintenance(name: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            System.err.println("ViveNotes: $name will be retried: $failure")
        }
    }

    companion object {
        fun open(directory: File): NotesLibrary {
            directory.mkdirs()
            check(directory.isDirectory) { "Cannot create the notes directory $directory" }
            val database = NotesDatabase.create(File(directory, NotesDatabase.FILE_NAME))
            return NotesLibrary(
                directory = directory,
                database = database,
                repository = NotesRepository(database),
                attachments = AttachmentStore(File(directory, AttachmentStore.DIRECTORY), database),
                backups = DatabaseBackupManager(database, File(directory, DatabaseBackupManager.DIRECTORY)),
            )
        }
    }
}
