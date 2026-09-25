package com.vivenotes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.vivenotes.ui.shell.WorkspaceScreen
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState

/** Shared root used by every future ViveNotes application entry point. */
@Composable
@Preview
fun App() {
    var workspace by remember { mutableStateOf(WorkspaceState.demo()) }

    ViveNotesTheme {
        WorkspaceScreen(
            state = workspace,
            onStateChange = { workspace = it },
        )
    }
}
