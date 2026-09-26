package com.vivenotes.ui.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.nameOf

internal const val UntitledPage = "Untitled page"

/** The rename or delete a navigation menu asked for, held until its dialog closes. */
internal class NavigationRequests {
    var renaming: NavigationItem? by mutableStateOf(null)
    var deleting: NavigationItem? by mutableStateOf(null)
}

/** What the menus and dialogs call [this]. */
internal val NavigationItem.noun: String
    get() = when (this) {
        is NavigationItem.Notebook -> "notebook"
        is NavigationItem.Section -> "section"
        is NavigationItem.Page -> "page"
    }

/**
 * The dialogs behind the navigation menus. A rename asks for the new name; a delete asks first,
 * because it takes everything inside with it. A request for something no longer in [state] — gone
 * while the menu was open — is dropped.
 */
@Composable
internal fun NavigationDialogs(
    state: WorkspaceState,
    requests: NavigationRequests,
    onRename: (NavigationItem, String) -> Unit,
    onDelete: (NavigationItem) -> Unit,
) {
    requests.renaming?.let { item ->
        val current = state.nameOf(item)
        if (current == null) {
            SideEffect { requests.renaming = null }
        } else {
            RenameDialog(
                noun = item.noun,
                current = current,
                onDismiss = { requests.renaming = null },
                onConfirm = { name ->
                    requests.renaming = null
                    onRename(item, name)
                },
            )
        }
    }
    requests.deleting?.let { item ->
        val name = state.nameOf(item)
        if (name == null) {
            SideEffect { requests.deleting = null }
        } else {
            DeleteDialog(
                item = item,
                name = name.ifBlank { UntitledPage },
                onDismiss = { requests.deleting = null },
                onConfirm = {
                    requests.deleting = null
                    onDelete(item)
                },
            )
        }
    }
}

/**
 * Asks for a new name. The field opens on the current name, all of it selected, so replacing it is
 * one keystroke and fixing a typo does not mean retyping the rest (Android's `NameEntryDialog`).
 * A blank name cannot be confirmed: there is no sensible default for something already named.
 */
@Composable
private fun RenameDialog(
    noun: String,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank()
    DesktopDialogFrame(
        title = "Rename $noun",
        icon = ContextSymbols.Edit,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onDismiss = onDismiss,
        backdropTag = NavigationTestTags.Backdrop,
        modifier = Modifier.testTag(NavigationTestTags.Dialog),
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    // Enter confirms on every desktop, whatever the platform makes of the IME action.
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && valid) onConfirm(value.text)
                        true
                    }
                    .testTag(NavigationTestTags.NameField),
            )
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(NavigationTestTags.Cancel),
            ) { Text("Cancel") }
            Button(
                onClick = { onConfirm(value.text) },
                enabled = valid,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(NavigationTestTags.ConfirmRename),
            ) { Text("Rename") }
        },
    )
}

/**
 * Confirms a delete. The title names what is going, which is what makes a right-click on the wrong
 * row recoverable; the body says what goes with it.
 */
@Composable
private fun DeleteDialog(
    item: NavigationItem,
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DesktopDialogFrame(
        title = "Delete $name?",
        icon = ObjectSymbols.Delete,
        iconTint = MaterialTheme.colorScheme.error,
        onDismiss = onDismiss,
        backdropTag = NavigationTestTags.Backdrop,
        modifier = Modifier.testTag(NavigationTestTags.Dialog),
        content = {
            Text(
                when (item) {
                    is NavigationItem.Notebook -> "This notebook, its sections and all of their pages will be deleted."
                    is NavigationItem.Section -> "This section and all of its pages will be deleted."
                    is NavigationItem.Page -> "This page will be deleted."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.focusRequester(focus).testTag(NavigationTestTags.Cancel),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalDesktopColors.current.destructive,
                    contentColor = Color.White,
                ),
                modifier = Modifier.testTag(NavigationTestTags.ConfirmDelete),
            ) { Text("Delete") }
        },
    )
}
