package com.vivenotes.workspace

import com.vivenotes.data.DeletedItem
import com.vivenotes.data.db.ClosedNotebook
import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.PageDoc

enum class FilePane { VersionHistory, DeletedItems, ClosedNotebooks }

/** Data for the open File pane, owned by the session so it survives recomposition. */
data class FilePaneState(
    val pane: FilePane? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val revisions: List<PageRevisionSummary> = emptyList(),
    val revisionPageId: String? = null,
    val selectedRevisionId: String? = null,
    val preview: PageDoc? = null,
    val deletedItems: List<DeletedItem> = emptyList(),
    val closedNotebooks: List<ClosedNotebook> = emptyList(),
    val dateLabels: Map<Long, String> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
)

/** What the File tab asks of whoever holds the workspace. */
interface FileActions {
    fun openPane(pane: FilePane)
    fun closePane()
    fun selectRevision(id: String)
    fun restoreRevision()
    fun restoreDeletedItem(item: DeletedItem)
    fun reopenNotebook(id: String)
    fun closeNotebook()
    fun deleteNotebook()

    /** Saves the open notebook as a `.vive` file where the user chooses. */
    fun exportNotebook()

    /** Restores the notebook in a `.vive` file the user chooses, and opens it. */
    fun importNotebook()

    /** Closes the transfer dialog, once its transfer has finished. */
    fun dismissTransfer()
}

/**
 * Android's `NotebookTransferState`: a `.vive` export or import under way, or how the last one
 * ended. The transfer dialog shows while it is [visible].
 */
data class NotebookTransferState(
    val running: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val visible: Boolean get() = running || message != null || error != null
}
