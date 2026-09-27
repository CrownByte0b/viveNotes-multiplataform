package com.vivenotes.ui.ribbon.file

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.workspace.NotebookTransferState

/**
 * Android's `NotebookTransferDialog`: shown while a `.vive` export or import runs, then with how it
 * ended until OK.
 *
 * While the transfer runs the dialog cannot be dismissed, and it holds the keyboard as its scrim
 * holds the pointer: Android's dialog is modal, and an edit made meanwhile would be to a page the
 * import may be replacing.
 */
@Composable
internal fun NotebookTransferDialog(state: NotebookTransferState, onDismiss: () -> Unit) {
    if (!state.visible) return
    val focus = remember { FocusRequester() }
    DesktopDialogFrame(
        title = when {
            state.running -> "Working with notebook"
            state.error != null -> "Notebook transfer failed"
            else -> "Notebook transfer complete"
        },
        onDismiss = { if (!state.running) onDismiss() },
        modifier = Modifier.testTag(FileRibbonTags.TransferDialog),
        backdropTag = FileRibbonTags.TransferBackdrop,
        content = {
            // Here rather than beside the frame: its content is composed during layout, after an
            // effect outside it would already have asked for a node that did not yet exist.
            LaunchedEffect(state.running) { focus.requestFocus() }
            if (state.running) {
                Row(
                    Modifier.focusRequester(focus).focusable(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Checking and preparing the notebook…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(state.error ?: state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            if (!state.running) {
                Button(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.focusRequester(focus).testTag(FileRibbonTags.TransferOk),
                ) { Text("OK") }
            }
        },
    )
}
