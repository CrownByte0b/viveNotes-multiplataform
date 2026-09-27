package com.vivenotes.desktop

import com.vivenotes.data.AppDirectories
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.PictureLibrary
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.formatCreated
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.workspace.ViewSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlin.time.Duration.Companion.milliseconds

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
    private val interfaceStore: InterfaceSettingsFile? = null,
    private val viewStore: ViewSettingsFile? = null,
) {
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    val session = WorkspaceSession(library.repository, CoroutineScope(scope.coroutineContext + sessionJob), ::formatCreated)
    var interfaceSettings by mutableStateOf(interfaceStore?.load() ?: InterfaceSettings())
        private set

    fun updateInterfaceSettings(settings: InterfaceSettings) {
        val value = settings.normalized()
        interfaceStore?.save(value)
        interfaceSettings = value
    }

    var viewSettings by mutableStateOf(viewStore?.load() ?: ViewSettings())
        private set
    private var viewSave: Job? = null

    /**
     * Shown at once, written [VIEW_SAVE_DELAY] later: Ctrl+wheel reports a zoom per notch, and one
     * gesture should be one write. [close] writes one still pending.
     */
    fun updateViewSettings(settings: ViewSettings) {
        val value = settings.normalized()
        if (value == viewSettings) return
        viewSettings = value
        val store = viewStore ?: return
        viewSave?.cancel()
        viewSave = scope.launch {
            delay(VIEW_SAVE_DELAY)
            store.save(value)
        }
    }
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
                if (viewSave?.isActive == true) {
                    viewSave?.cancel()
                    viewStore?.save(viewSettings)
                }
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
        val VIEW_SAVE_DELAY = 500.milliseconds

        fun open(directory: File = AppDirectories.data()): DesktopNotes =
            DesktopNotes(NotesLibrary.open(directory), MainScope(),
                interfaceStore = InterfaceSettingsFile(File(AppDirectories.config(), "interface.properties")),
                viewStore = ViewSettingsFile(File(AppDirectories.config(), "view.properties")))
    }
}
