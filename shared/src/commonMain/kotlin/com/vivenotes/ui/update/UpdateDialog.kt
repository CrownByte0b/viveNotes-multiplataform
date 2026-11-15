package com.vivenotes.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ai.formatModelBytes
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.SettingsSymbols

object UpdateDialogTags {
    const val Dialog = "update-dialog"
    const val Notes = "update-notes"
    const val Progress = "update-progress"
    const val Reused = "update-reused"
    const val Message = "update-message"
    const val Skip = "update-skip"
    const val Later = "update-later"
    const val Install = "update-install"
    const val Cancel = "update-cancel"
    const val Retry = "update-retry"
}

/**
 * Offers [updates]' newer release over the window, then shows its download and the hand-over to
 * the installer. Nothing while there is nothing to offer.
 */
@Composable
fun UpdateDialog(updates: AppUpdates) {
    val state by updates.state.collectAsState()
    when (val current = state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> AvailableDialog(current.update, updates)
        is UpdateState.Downloading -> DownloadingDialog(current, updates)
        is UpdateState.Installing -> InstallingDialog()
        is UpdateState.Failed -> FailedDialog(current, updates)
    }
}

@Composable
private fun AvailableDialog(update: AvailableUpdate, updates: AppUpdates) {
    DesktopDialogFrame(
        title = Strings.update_available,
        icon = SettingsSymbols.CloudDownload,
        iconTint = MaterialTheme.colorScheme.primary,
        onDismiss = updates::later,
        modifier = Modifier.testTag(UpdateDialogTags.Dialog),
        maxWidth = 480.dp,
        content = {
            Message(Strings.update_available_message(update.version, update.currentVersion), Modifier.takesFocusOnOpen())
            if (update.notes.isNotBlank()) {
                Text(Strings.update_whats_new, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                    Text(update.notes, modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(UpdateDialogTags.Notes),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.size(16.dp))
            Message(Strings.update_restart_note)
        },
        actions = {
            TextButton(onClick = updates::skip, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Skip)) { Text(Strings.update_skip_version) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = updates::later, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Later)) { Text(Strings.update_later) }
            Button(onClick = updates::install, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Install)) { Text(Strings.update_install) }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadingDialog(state: UpdateState.Downloading, updates: AppUpdates) {
    DesktopDialogFrame(
        title = Strings.update_downloading,
        icon = SettingsSymbols.CloudDownload,
        iconTint = MaterialTheme.colorScheme.primary,
        // A stray click beside the dialog must not throw away a download; Cancel does that.
        onDismiss = {},
        modifier = Modifier.testTag(UpdateDialogTags.Dialog),
        maxWidth = 480.dp,
        content = {
            val comparing = state.total <= 0 && state.reused <= 0
            Message(if (comparing) Strings.update_comparing else Strings.update_downloading_message(state.update.version),
                Modifier.takesFocusOnOpen())
            val indicator = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(UpdateDialogTags.Progress)
            val total = state.total
            if (total > 0) {
                LinearWavyProgressIndicator(progress = { (state.bytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = indicator)
                Text(Strings.update_download_progress(formatModelBytes(state.bytes), formatModelBytes(total)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearWavyProgressIndicator(indicator)
            }
            if (state.reused > 0) {
                Text(Strings.update_reused(formatModelBytes(state.reused)),
                    modifier = Modifier.padding(top = 4.dp).testTag(UpdateDialogTags.Reused),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            OutlinedButton(onClick = updates::cancel, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Cancel)) { Text(Strings.cancel) }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallingDialog() {
    DesktopDialogFrame(
        title = Strings.update_installing,
        icon = SettingsSymbols.CloudDownload,
        iconTint = MaterialTheme.colorScheme.primary,
        // The notes are being written and the window is about to close; there is nothing to go back to.
        onDismiss = {},
        modifier = Modifier.testTag(UpdateDialogTags.Dialog),
        maxWidth = 480.dp,
        content = {
            Message(Strings.update_installing_message, Modifier.takesFocusOnOpen())
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).testTag(UpdateDialogTags.Progress))
        },
        actions = {},
    )
}

@Composable
private fun FailedDialog(state: UpdateState.Failed, updates: AppUpdates) {
    DesktopDialogFrame(
        title = Strings.update_failed,
        icon = SettingsSymbols.CloudDownload,
        iconTint = MaterialTheme.colorScheme.error,
        onDismiss = updates::later,
        modifier = Modifier.testTag(UpdateDialogTags.Dialog),
        maxWidth = 480.dp,
        content = {
            Column {
                Message(Strings.update_failed_message(state.update.version), Modifier.takesFocusOnOpen())
                if (state.reason.isNotBlank()) {
                    Text(state.reason, modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        actions = {
            OutlinedButton(onClick = updates::later, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Later)) { Text(Strings.update_later) }
            Button(onClick = updates::install, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(UpdateDialogTags.Retry)) { Text(Strings.retry) }
        },
    )
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.testTag(UpdateDialogTags.Message), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Takes keyboard focus as the dialog opens, so Escape and Tab reach it. Not a button: the offer
 * appears a few seconds after launch, and a Space or Enter meant for the page must not install.
 * About, closing as this opens, can keep focus for a frame, hence the retries.
 */
@Composable
private fun Modifier.takesFocusOnOpen(): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        repeat(FOCUS_ATTEMPTS) {
            if (focus.requestFocus(FocusDirection.Enter)) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    return focusRequester(focus).focusable()
}

private const val FOCUS_ATTEMPTS = 30
