package com.vivenotes.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.vivenotes.localization.Strings
import com.vivenotes.ui.navigation.NavigationDialogs
import com.vivenotes.ui.navigation.NavigationRequests
import com.vivenotes.ui.ribbon.document.LinkEditorDialog
import com.vivenotes.ui.ribbon.document.LinkEditorRequest
import com.vivenotes.ui.ribbon.document.PictureDialog
import com.vivenotes.ui.ribbon.document.PictureDialogHost
import com.vivenotes.ui.ribbon.file.FileConfirmDialog
import com.vivenotes.ui.ribbon.file.FilePaneTags
import com.vivenotes.ui.ribbon.file.NotebookTransferDialog
import com.vivenotes.ui.ribbon.settings.ResetAllShortcutsDialog
import com.vivenotes.ui.ribbon.settings.ShortcutCaptureDialog
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.KeyBindings
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun WorkspaceDialogs(
    state: WorkspaceState,
    navigationRequests: NavigationRequests,
    navigation: NavigationActions,
    linkEditor: LinkEditorRequest?,
    onDismissLink: () -> Unit,
    pictureDialog: PictureDialog?,
    onDismissPicture: () -> Unit,
    fileActions: FileActions?,
    confirmRevision: Boolean,
    onDismissRevision: () -> Unit,
    notebookConfirmation: String?,
    onDismissNotebook: () -> Unit,
    editingShortcut: ShortcutAction?,
    onDismissShortcut: () -> Unit,
    bindings: KeyBindings,
    onBindingsChange: (KeyBindings) -> Unit,
    confirmResetShortcuts: Boolean,
    onDismissReset: () -> Unit,
) {
    NavigationDialogs(state, navigationRequests, navigation)
    linkEditor?.let { request -> LinkEditorDialog(request, onDismiss = { onDismissLink() }) }
    pictureDialog?.let { dialog -> PictureDialogHost(dialog, onDismiss = { onDismissPicture() }) }
    NotebookTransferDialog(state.notebookTransfer, onDismiss = { fileActions?.dismissTransfer() })
    if (confirmRevision) {
        FileConfirmDialog(Strings.restore_this_version_16b4be,
            Strings.the_saved_version_will_replace_this_page_your_current_version_will,
            Strings.restore, onDismiss = { onDismissRevision() }, onConfirm = {
                onDismissRevision()
                fileActions?.restoreRevision()
            }, tag = FilePaneTags.ConfirmRestore)
    }
    notebookConfirmation?.let { operation ->
        val notebook = state.notebooks.firstOrNull { entry ->
            entry.sections.any { it.id == state.selectedSectionId }
        }
        if (notebook == null) {
            SideEffect { onDismissNotebook() }
        } else {
            FileConfirmDialog(
                title = if (operation == "close") Strings.close_notebook_confirmation(notebook.name) else Strings.delete_notebook_confirmation(notebook.name),
                detail = if (operation == "close")
                    Strings.nothing_is_deleted_the_notebook_leaves_the_panel_until_you_reopen
                else Strings.this_notebook_its_sections_and_all_of_their_pages_will_be_7425e6,
                verb = if (operation == "close") Strings.close else Strings.delete,
                destructive = operation == "delete",
                onDismiss = { onDismissNotebook() },
                onConfirm = {
                    onDismissNotebook()
                    if (operation == "close") fileActions?.closeNotebook() else fileActions?.deleteNotebook()
                },
            )
        }
    }
    editingShortcut?.let { action ->
        ShortcutCaptureDialog(
            action = action,
            bindings = bindings,
            onSet = { chord ->
                onBindingsChange(bindings.rebind(action, chord))
                onDismissShortcut()
            },
            onDismiss = { onDismissShortcut() },
        )
    }
    if (confirmResetShortcuts) {
        ResetAllShortcutsDialog(
            onConfirm = {
                onBindingsChange(bindings.resetAll())
                onDismissReset()
            },
            onDismiss = { onDismissReset() },
        )
    }
}
