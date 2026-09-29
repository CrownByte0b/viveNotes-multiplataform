package com.vivenotes.data

import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.diagnostics.DebugLog
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
    /** `.vive` export and import, Android's portable notebook file. */
    val transfers: NotebookTransferManager,
    private val backups: DatabaseBackupManager,
    private val log: DebugLog,
) : AutoCloseable {

    /**
     * The upkeep Android hands to WorkManager and to its first screen: a verified backup once the
     * newest is a day old, and the purge of deletions past their seven-day window. Each half is
     * attempted whatever happens to the other, and a failure only waits for the next pass — both
     * are idempotent, and neither is a reason to stop the notes from opening.
     */
    suspend fun maintain() {
        runMaintenance("backup") {
            if (backups.createIfDue() != null) log.event("storage") { "backup created" }
        }
        runMaintenance("deletion purge") { repository.purgeExpiredDeletions() }
    }

    override fun close() = database.close()

    private suspend fun runMaintenance(name: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.failure("storage", name, failure)
            System.err.println("ViveNotes: $name will be retried: $failure")
        }
    }

    companion object {
        /**
         * Opens the notes in [directory]. [cacheDirectory] holds only scratch files — the app passes
         * the platform's cache directory; the default keeps a test's inside its own directory.
         */
        fun open(
            directory: File,
            cacheDirectory: File = File(directory, "cache"),
            log: DebugLog = DebugLog(),
        ): NotesLibrary {
            directory.mkdirs()
            check(directory.isDirectory) { "Cannot create the notes directory $directory" }
            val database = NotesDatabase.create(File(directory, NotesDatabase.FILE_NAME))
            log.event("storage") { "database opened" }
            val attachments = AttachmentStore(File(directory, AttachmentStore.DIRECTORY), database)
            return NotesLibrary(
                directory = directory,
                database = database,
                repository = NotesRepository(database),
                attachments = attachments,
                transfers = NotebookTransferManager(database, attachments,
                    File(cacheDirectory, NotebookTransferManager.DIRECTORY)),
                backups = DatabaseBackupManager(database, File(directory, DatabaseBackupManager.DIRECTORY)),
                log = log,
            )
        }
    }
}
