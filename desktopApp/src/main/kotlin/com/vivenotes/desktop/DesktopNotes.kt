package com.vivenotes.desktop

import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.NotesLibrary
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.formatCreated
import com.vivenotes.workspace.formatUpdated
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.EditorDefaults
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
    private val editorStore: EditorDefaultsFile? = null,
    private val keyStore: KeyBindingsFile? = null,
    val thumbnails: DesktopVideoThumbnails? = null,
    private val log: DebugLog = DebugLog(),
) {
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    var editorDefaults by mutableStateOf(editorStore?.load() ?: EditorDefaults())
        private set
    val session = WorkspaceSession(library.repository, CoroutineScope(scope.coroutineContext + sessionJob), ::formatCreated,
        updatedLabel = { formatUpdated(it) }, editorDefaults = editorDefaults, log = log)

    fun updateEditorDefaults(defaults: EditorDefaults) {
        val value = defaults.normalized()
        if (value == editorDefaults) return
        editorStore?.save(value)
        editorDefaults = value
        log.event("settings") { "editor defaults saved" }
    }
    var interfaceSettings by mutableStateOf(interfaceStore?.load() ?: InterfaceSettings())
        private set

    fun updateInterfaceSettings(settings: InterfaceSettings) {
        val value = settings.normalized()
        interfaceStore?.save(value)
        interfaceSettings = value
        log.event("settings") { "interface preferences saved" }
    }

    var keyBindings by mutableStateOf(keyStore?.load() ?: KeyBindings.Default)
        private set

    /** Written at once: a shortcut changes one press at a time, never a stream of them. */
    fun updateKeyBindings(bindings: KeyBindings) {
        if (bindings == keyBindings) return
        keyStore?.save(bindings)
        keyBindings = bindings
        log.event("settings") { "keyboard shortcuts saved" }
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
            log.event("settings") { "view preferences saved" }
        }
    }
    private var maintenance: Job? = null

    /** Pictures for a window: its file dialog opens over [owner]. */
    fun pictures(owner: Frame?): PictureLibrary =
        DesktopPictures(library.attachments) { choosePictureFile(owner) }

    /** `.vive` export and import for a window: their file dialogs open over [owner]. */
    fun notebookFiles(owner: Frame?): NotebookFiles = DesktopNotebookFiles(
        library.transfers,
        chooseSave = { name, directory -> chooseNotebookDestination(owner, name, directory) },
        chooseOpen = { directory -> chooseNotebookSource(owner, directory) },
    )
    private var closing: Job? = null

    /** Starts reading the notes, and the upkeep. Only once AWT's toolkit has been chosen. */
    fun start() {
        log.event("app") { "session starting" }
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
        log.event("app") { "closing" }
        closing = scope.launch {
            try {
                if (viewSave?.isActive == true) {
                    viewSave?.cancel()
                    viewStore?.save(viewSettings)
                    log.event("settings") { "view preferences saved" }
                }
                session.flush()
            } finally {
                sessionJob.cancel()
                maintenance?.cancelAndJoin()
                library.close()
                log.event("app") { "closed" }
                done()
            }
        }
    }

    companion object {
        val VIEW_SAVE_DELAY = 500.milliseconds

        fun open(profile: DesktopProfile = DesktopProfile.fromProperty(), log: DebugLog = DebugLog()): DesktopNotes {
            val directories = profile.directories()
            return DesktopNotes(NotesLibrary.open(directories.data, directories.cache, log), MainScope(),
                interfaceStore = InterfaceSettingsFile(File(directories.config, "interface.properties")),
                viewStore = ViewSettingsFile(File(directories.config, "view.properties")),
                editorStore = EditorDefaultsFile(File(directories.config, "editor.properties")),
                keyStore = KeyBindingsFile(File(directories.config, "keyboard.properties")),
                thumbnails = DesktopVideoThumbnails(File(directories.data, "video_thumbnails")), log = log)
        }
    }
}
