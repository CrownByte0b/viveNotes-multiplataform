package com.vivenotes.ui.ribbon.file

import com.vivenotes.localization.Strings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.DeletedItemKind
import com.vivenotes.model.Outline
import com.vivenotes.model.plainText
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.components.ToolPane
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.FileActions
import com.vivenotes.workspace.FilePane
import com.vivenotes.workspace.FilePaneState

object FilePaneTags {
    const val Pane = "file-pane"
    const val Empty = "file-pane-empty"
    const val Status = "file-pane-status"
    const val Preview = "file-revision-preview"
    const val RestoreRevision = "file-revision-restore"
    const val ConfirmRestore = "file-revision-confirm"
    const val ConfirmNotebook = "file-notebook-confirm"
    fun revision(id: String) = "file-revision-$id"
    fun deleted(id: String) = "file-deleted-$id"
    fun restoreDeleted(id: String) = "file-deleted-restore-$id"
    fun closed(id: String) = "file-closed-$id"
    fun reopen(id: String) = "file-closed-reopen-$id"
}

@Composable
internal fun FilePaneContent(state: FilePaneState, actions: FileActions, onRestoreRevision: () -> Unit) {
    val pane = state.pane ?: return
    ToolPane(title = when (pane) {
        FilePane.VersionHistory -> Strings.version_history
        FilePane.DeletedItems -> Strings.deleted_items
        FilePane.ClosedNotebooks -> Strings.closed_notebooks
    }, onClose = actions::closePane, modifier = Modifier.testTag(FilePaneTags.Pane)) {
        when (pane) {
            FilePane.VersionHistory -> {
                Description(Strings.earlier_saved_versions_of_this_page_newest_first_restoring_a_version)
                when {
                    state.loading && state.selectedRevisionId == null -> Status(Strings.loading_versions)
                    state.revisions.isEmpty() && state.error == null -> Status(Strings.no_earlier_versions_yet, FilePaneTags.Empty)
                    else -> state.revisions.forEach { revision ->
                        val selected = revision.id == state.selectedRevisionId
                        Entry(state.dateLabels[revision.createdAt] ?: Strings.saved_version, Strings.value_bytes(revision.byteCount),
                            selected, !state.busy, FilePaneTags.revision(revision.id)) { actions.selectRevision(revision.id) }
                    }
                }
                if (state.selectedRevisionId != null) {
                    Spacer(Modifier.height(12.dp))
                    when {
                        state.loading -> Status(Strings.loading_preview)
                        state.preview != null -> {
                            val doc = state.preview
                            val text = doc.plainText().trim().ifBlank { Strings.no_text_in_this_version }
                            Entry(text.take(600), Strings.value_placed_objects(doc.outlines.count { it !is Outline.Text }),
                                false, false, FilePaneTags.Preview) { }
                        }
                    }
                    Button(onClick = onRestoreRevision, enabled = state.preview != null && !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.RestoreRevision)) {
                        Text(Strings.restore_this_version)
                    }
                }
            }
            FilePane.DeletedItems -> {
                Description(Strings.deleted_notebooks_sections_and_pages_can_be_restored_for_7_days)
                when {
                    state.loading -> Status(Strings.loading_deleted_items)
                    state.deletedItems.isEmpty() && state.error == null -> Status(Strings.deleted_items_is_empty, FilePaneTags.Empty)
                    else -> state.deletedItems.forEach { item ->
                        val location = when (item.key.kind) {
                            DeletedItemKind.Notebook -> Strings.value_sections_value_pages(item.sectionCount, item.pageCount)
                            DeletedItemKind.Section -> Strings.in_value_value_pages(item.notebookName ?: Strings.notebook_528093, item.pageCount)
                            DeletedItemKind.Page -> Strings.in_value_value(item.sectionName ?: Strings.section, item.notebookName ?: Strings.notebook_528093)
                        }
                        Entry(item.name.ifBlank { Strings.untitled_page },
                            Strings.value_value_deleted_value(when (item.key.kind) {
                                DeletedItemKind.Notebook -> Strings.notebook_kind
                                DeletedItemKind.Section -> Strings.section_kind
                                DeletedItemKind.Page -> Strings.page_kind
                            }, location, state.dateLabels[item.deletedAt].orEmpty()),
                            false, false, FilePaneTags.deleted(item.key.id)) { }
                        OutlinedButton(onClick = { actions.restoreDeletedItem(item) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.restoreDeleted(item.key.id))) {
                            Text(Strings.restore)
                        }
                    }
                }
            }
            FilePane.ClosedNotebooks -> {
                Description(Strings.closed_notebooks_stay_saved_and_can_be_reopened_here)
                when {
                    state.loading -> Status(Strings.loading_closed_notebooks)
                    state.closedNotebooks.isEmpty() && state.error == null -> Status(Strings.no_closed_notebooks, FilePaneTags.Empty)
                    else -> state.closedNotebooks.forEach { row ->
                        Entry(row.notebook.name, Strings.value_sections_value_pages_closed_value(row.sectionCount, row.pageCount, row.notebook.closedAt?.let { state.dateLabels[it] }.orEmpty()),
                            false, false, FilePaneTags.closed(row.notebook.id)) { }
                        OutlinedButton(onClick = { actions.reopenNotebook(row.notebook.id) },
                            enabled = !state.busy && row.contentOnDevice,
                            modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.reopen(row.notebook.id))) {
                            Text(if (row.contentOnDevice) Strings.reopen else Strings.content_unavailable_on_this_device)
                        }
                    }
                }
            }
        }
        state.message?.let { Status(it, FilePaneTags.Status) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp).testTag(FilePaneTags.Status)) }
    }
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun Status(text: String, tag: String = FilePaneTags.Status) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 10.dp).testTag(tag))
}

@Composable
private fun Entry(title: String, detail: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).testTag(tag)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FileConfirmDialog(title: String, detail: String, verb: String, onDismiss: () -> Unit,
    onConfirm: () -> Unit, tag: String = FilePaneTags.ConfirmNotebook, destructive: Boolean = false) {
    DesktopDialogFrame(title = title, onDismiss = onDismiss,
        icon = if (destructive) ObjectSymbols.Delete else NavigationSymbols.Book,
        iconTint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        content = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
        actions = {
            OutlinedButton(onClick = onDismiss) { Text(Strings.cancel) }
            Button(onClick = onConfirm, modifier = Modifier.testTag(tag)) { Text(verb) }
        })
}
