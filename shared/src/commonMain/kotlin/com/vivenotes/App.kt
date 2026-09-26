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
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceSession
import com.vivenotes.workspace.WorkspaceState

/** Semantics identifiers for the application root. */
object AppTestTags {
    const val Opening = "app-opening"
}

/** The ViveNotes workspace over its stored notes: what every application window shows. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App(session: WorkspaceSession) {
    val state by session.state.collectAsState()
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
            WorkspaceScreen(state = workspace, onStateChange = session::update, onAddPage = session::addPage)
        }
    }
}

/** The in-memory sample workspace, for previews and the dormant web template. Nothing is stored. */
@Composable
@Preview
fun SampleApp() {
    var workspace by remember { mutableStateOf(WorkspaceState.demo()) }

    ViveNotesTheme {
        WorkspaceScreen(state = workspace, onStateChange = { workspace = it(workspace) })
    }
}
