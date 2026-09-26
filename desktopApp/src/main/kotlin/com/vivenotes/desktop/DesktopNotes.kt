package com.vivenotes.desktop

import com.vivenotes.data.AppDirectories
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.PictureLibrary
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.formatCreated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * This process's notes, from launch to the last window closing: the library in the platform data
 * directory, the workspace session every window shows, and the daily upkeep Android runs through
 * WorkManager.
 *
 * [scope] must run on the UI thread — [MainScope] is the Swing event thread, where Compose runs too
 * — because the session is confined to the thread that updates it.
 */
internal class DesktopNotes(
    private val library: NotesLibrary,
    private val scope: CoroutineScope,
    private val maintenanceInterval: Duration = 24.hours,
) {
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    val session = WorkspaceSession(library.repository, CoroutineScope(scope.coroutineContext + sessionJob), ::formatCreated)
    private var maintenance: Job? = null

    /** Pictures for a window: its file dialog opens over [owner]. */
    fun pictures(owner: Frame?): PictureLibrary =
        DesktopPictures(library.attachments) { choosePictureFile(owner) }
    private var closing: Job? = null

    /** Starts reading the notes, and the upkeep. Only once AWT's toolkit has been chosen. */
    fun start() {
        session.start()
        // Off the UI thread: a backup copies the whole database.
        maintenance = scope.launch(Dispatchers.IO) {
            while (isActive) {
                library.maintain()
                delay(maintenanceInterval)
            }
        }
    }

    /**
     * Writes what is still pending, stops observing and the upkeep, closes the database, and then
     * runs [done] — the window's own close. A second request while that is under way adds nothing.
     */
    fun close(done: () -> Unit) {
        if (closing != null) return
        closing = scope.launch {
            try {
                session.flush()
            } finally {
                sessionJob.cancel()
                maintenance?.cancelAndJoin()
                library.close()
                done()
            }
        }
    }

    companion object {
        fun open(directory: File = AppDirectories.data()): DesktopNotes =
            DesktopNotes(NotesLibrary.open(directory), MainScope())
    }
}
