package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.SettingsSymbols
import com.vivenotes.ui.keyboard.isModifier
import com.vivenotes.ui.keyboard.toChord
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.KeyChord
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.ShortcutKey

/** Semantics identifiers for the shortcut dialogs. */
object ShortcutDialogTags {
    const val Capture = "hardware-shortcut-capture"
    const val Captured = "hardware-shortcut-captured"
    const val Message = "hardware-shortcut-message"
    const val Replace = "hardware-shortcut-replace"
    const val Cancel = "hardware-shortcut-cancel"
    const val ResetAll = "hardware-reset-all-dialog"
    const val ConfirmResetAll = "hardware-reset-all-confirm"
    const val CancelResetAll = "hardware-reset-all-cancel"
}

/**
 * Asks for [action]'s new keys, as GNOME Settings does: the next chord pressed becomes the shortcut,
 * Esc cancels, and Backspace disables it. A chord another command already uses is held until
 * Replace (or Enter) confirms taking it; one that needs a modifier to be a shortcut is refused.
 * [onSet] receives the chord, or null to disable.
 */
@Composable
internal fun ShortcutCaptureDialog(
    action: ShortcutAction,
    bindings: KeyBindings,
    onSet: (KeyChord?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(action) { mutableStateOf<KeyChord?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val held = pending
    val conflict = held?.takeIf { it.usable }?.let { bindings.conflict(action, it) }

    fun capture(chord: KeyChord) {
        when {
            // Its own keys again: nothing changes, and a zoom keeps its numpad and Shift spellings.
            chord in bindings.chords(action) -> onDismiss()
            chord.usable && bindings.conflict(action, chord) == null -> onSet(chord)
            else -> pending = chord
        }
    }

    DesktopDialogFrame(
        title = "Set Shortcut",
        icon = SettingsSymbols.Keyboard,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        modifier = Modifier.testTag(ShortcutDialogTags.Capture),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .focusable()
                    // Every key is the answer, so none may reach anything else. Esc never gets here:
                    // the dialog frame takes it first and cancels.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key.isModifier()) return@onPreviewKeyEvent true
                        val chord = event.toChord() ?: return@onPreviewKeyEvent true
                        when {
                            chord == KeyChord(ShortcutKey.Backspace) -> onSet(null)
                            chord == KeyChord(ShortcutKey.Enter) && conflict != null -> onSet(held)
                            else -> capture(chord)
                        }
                        true
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Press the new keys for “${action.label}”.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Keycap(held?.label ?: "…", large = true,
                    modifier = Modifier.padding(vertical = 4.dp).testTag(ShortcutDialogTags.Captured))
                Text(
                    text = when {
                        held != null && !held.usable ->
                            "${held.label} can’t be a shortcut on its own. Add Ctrl, Alt or Super."
                        conflict != null ->
                            "${held?.label} is already used for ${conflict.label}. Replacing it removes it from ${conflict.label}."
                        else -> "Now: ${bindings.primary(action)?.label ?: "Disabled"}. " +
                            "Esc cancels; Backspace disables the shortcut."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (held != null && !held.usable) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 32.dp).testTag(ShortcutDialogTags.Message),
                )
            }
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(ShortcutDialogTags.Cancel)) { Text("Cancel") }
            if (conflict != null) {
                Button(onClick = { onSet(held) }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(ShortcutDialogTags.Replace)) { Text("Replace") }
            }
        },
    )
}

/** Confirms Reset All: it throws away every change at once, and there is no undoing it. */
@Composable
internal fun ResetAllShortcutsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DesktopDialogFrame(
        title = "Reset All Shortcuts?",
        icon = SettingsSymbols.RestartAlt,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ShortcutDialogTags.ResetAll),
        content = {
            Text("Every keyboard shortcut goes back to its default, and your changes are lost.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            OutlinedButton(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.focusRequester(focus).testTag(ShortcutDialogTags.CancelResetAll)) { Text("Cancel") }
            Button(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalDesktopColors.current.destructive,
                    contentColor = Color.White,
                ),
                modifier = Modifier.testTag(ShortcutDialogTags.ConfirmResetAll),
            ) { Text("Reset All") }
        },
    )
}
