package com.vivenotes.ui.ribbon.file

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
        FilePane.VersionHistory -> "Version History"
        FilePane.DeletedItems -> "Deleted Items"
        FilePane.ClosedNotebooks -> "Closed Notebooks"
    }, onClose = actions::closePane, modifier = Modifier.testTag(FilePaneTags.Pane)) {
        when (pane) {
            FilePane.VersionHistory -> {
                Description("Earlier saved versions of this page, newest first. Restoring a version also keeps the current page in history.")
                when {
                    state.loading && state.selectedRevisionId == null -> Status("Loading versions…")
                    state.revisions.isEmpty() && state.error == null -> Status("No earlier versions yet", FilePaneTags.Empty)
                    else -> state.revisions.forEach { revision ->
                        val selected = revision.id == state.selectedRevisionId
                        Entry(state.dateLabels[revision.createdAt] ?: "Saved version", "${revision.byteCount} bytes",
                            selected, !state.busy, FilePaneTags.revision(revision.id)) { actions.selectRevision(revision.id) }
                    }
                }
                if (state.selectedRevisionId != null) {
                    Spacer(Modifier.height(12.dp))
                    when {
                        state.loading -> Status("Loading preview…")
                        state.preview != null -> {
                            val doc = state.preview
                            val text = doc.plainText().trim().ifBlank { "No text in this version." }
                            Entry(text.take(600), "${doc.outlines.count { it !is Outline.Text }} placed objects",
                                false, false, FilePaneTags.Preview) { }
                        }
                    }
                    Button(onClick = onRestoreRevision, enabled = state.preview != null && !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.RestoreRevision)) {
                        Text("Restore this version")
                    }
                }
            }
            FilePane.DeletedItems -> {
                Description("Deleted notebooks, sections and pages can be restored for 7 days.")
                when {
                    state.loading -> Status("Loading deleted items…")
                    state.deletedItems.isEmpty() && state.error == null -> Status("Deleted Items is empty", FilePaneTags.Empty)
                    else -> state.deletedItems.forEach { item ->
                        val location = when (item.key.kind) {
                            DeletedItemKind.Notebook -> "${item.sectionCount} sections · ${item.pageCount} pages"
                            DeletedItemKind.Section -> "In ${item.notebookName ?: "notebook"} · ${item.pageCount} pages"
                            DeletedItemKind.Page -> "In ${item.sectionName ?: "section"} · ${item.notebookName ?: "notebook"}"
                        }
                        Entry(item.name.ifBlank { "Untitled page" },
                            "${item.key.kind} · $location · Deleted ${state.dateLabels[item.deletedAt].orEmpty()}",
                            false, false, FilePaneTags.deleted(item.key.id)) { }
                        OutlinedButton(onClick = { actions.restoreDeletedItem(item) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.restoreDeleted(item.key.id))) {
                            Text("Restore")
                        }
                    }
                }
            }
            FilePane.ClosedNotebooks -> {
                Description("Closed notebooks stay saved and can be reopened here.")
                when {
                    state.loading -> Status("Loading closed notebooks…")
                    state.closedNotebooks.isEmpty() && state.error == null -> Status("No closed notebooks", FilePaneTags.Empty)
                    else -> state.closedNotebooks.forEach { row ->
                        Entry(row.notebook.name, "${row.sectionCount} sections · ${row.pageCount} pages · Closed ${row.notebook.closedAt?.let { state.dateLabels[it] }.orEmpty()}",
                            false, false, FilePaneTags.closed(row.notebook.id)) { }
                        OutlinedButton(onClick = { actions.reopenNotebook(row.notebook.id) },
                            enabled = !state.busy && row.contentOnDevice,
                            modifier = Modifier.fillMaxWidth().testTag(FilePaneTags.reopen(row.notebook.id))) {
                            Text(if (row.contentOnDevice) "Reopen" else "Content unavailable on this device")
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
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = onConfirm, modifier = Modifier.testTag(tag)) { Text(verb) }
        })
}
