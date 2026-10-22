package com.vivenotes.data

import com.vivenotes.localization.Strings
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
    /** Public for the account layer, which builds its sync engine over it, as Android's application does. */
    val database: NotesDatabase,
    val repository: NotesRepository,
    /** Pictures, in the `attachments` directory beside the database. */
    val attachments: AttachmentStore,
    /** `.vive` export and import, Android's portable notebook file. */
    val transfers: NotebookTransferManager,
    private val backups: DatabaseBackupManager,
    private val log: DebugLog,
) : AutoCloseable {

    /** Lets go of pictures nothing can put back on a page; run by [maintain], after the purge. */
    private val attachmentSweep = AttachmentSweep(database, attachments.syncBytes, attachments::heldPictures)

    /**
     * The upkeep Android hands to WorkManager and to its first screen: a verified backup once the
     * newest is a day old, the purge of deletions past their seven-day window, and then the picture
     * sweep — second because the purge is what makes pictures sweepable, as in Android's
     * `DeletionPurgeWorker`. Each step is attempted whatever happens to the others, and a failure
     * only waits for the next pass — all are idempotent, and none is a reason to stop the notes
     * from opening.
     */
    suspend fun maintain() {
        runMaintenance("backup") {
            if (backups.createIfDue() != null) log.event("storage") { "backup created" }
        }
        runMaintenance("deletion purge") { repository.purgeExpiredDeletions() }
        runMaintenance("picture sweep") {
            val sweep = attachmentSweep.sweep()
            if (sweep.released > 0 || sweep.placedAgain > 0 || sweep.filesDeleted > 0) log.event("storage") {
                "pictures released=${sweep.released} placedAgain=${sweep.placedAgain} deleted=${sweep.filesDeleted}"
            }
        }
    }

    override fun close() {
        repository.releaseLoadedInk()
        database.close()
    }

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
            check(directory.isDirectory) { Strings.cannot_create_the_notes_directory_value(directory) }
            // The code copied from the Android app logs through Android's calls; they print where
            // this process's own log does, so `--debug` turns on both and a normal launch neither.
            PortedLogs.sink = log
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
