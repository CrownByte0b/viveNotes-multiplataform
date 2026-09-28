package com.vivenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.PictureLibrary
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.ribbon.settings.InterfaceSettings
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.ViewSettings
import com.vivenotes.workspace.EditorDefaults
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.WorkspaceState

/** Semantics identifiers for the application root. */
object AppTestTags {
    const val Opening = "app-opening"
}

/**
 * The ViveNotes workspace over its stored notes: what every application window shows. [pictures]
 * is the platform's picture storage and chooser; without it pictures cannot be inserted or shown.
 * [notebookFiles] likewise carries the File tab's `.vive` export and import.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App(
    session: WorkspaceSession,
    pictures: PictureLibrary? = null,
    interfaceSettings: InterfaceSettings = InterfaceSettings(),
    onInterfaceSettingsChange: (InterfaceSettings) -> Unit = {},
    viewSettings: ViewSettings = ViewSettings(),
    onViewSettingsChange: (ViewSettings) -> Unit = {},
    keyBindings: KeyBindings = KeyBindings.Default,
    onKeyBindingsChange: (KeyBindings) -> Unit = {},
    onEditorDefaultsChange: (EditorDefaults) -> Unit = {},
    /** The platform's `.vive` file dialogs; without them notebooks cannot be exported or imported. */
    notebookFiles: NotebookFiles? = null,
    thumbnails: VideoThumbnailSource? = null,
) {
    val state by session.state.collectAsState()
    val fileActions = remember(session, notebookFiles) { notebookFiles?.let(session::fileActions) }
    ViveNotesTheme {
        val workspace = state
        if (workspace == null) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(
                    Modifier.testTag(AppTestTags.Opening).semantics { contentDescription = "Opening your notebooks" },
                )
            }
        } else {
            WorkspaceScreen(state = workspace, onStateChange = session::update, navigation = session, pictures = pictures,
                thumbnails = thumbnails,
                interfaceSettings = interfaceSettings, onInterfaceSettingsChange = onInterfaceSettingsChange,
                viewSettings = viewSettings, onViewSettingsChange = onViewSettingsChange,
                onEditorDefaultsChange = onEditorDefaultsChange,
                keyBindings = keyBindings, onKeyBindingsChange = onKeyBindingsChange, fileActions = fileActions)
        }
    }
}

/** The in-memory sample workspace, for previews and the dormant web template. Nothing is stored. */
@Composable
@Preview
fun SampleApp() {
    var workspace by remember { mutableStateOf(WorkspaceState.demo()) }

    ViveNotesTheme {
        WorkspaceScreen(state = workspace, onStateChange = { workspace = it(workspace) },
            interfaceSettings = InterfaceSettings())
    }
}
