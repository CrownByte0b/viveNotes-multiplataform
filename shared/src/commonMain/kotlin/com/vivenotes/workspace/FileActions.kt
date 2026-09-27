package com.vivenotes.workspace

/** What the File tab asks of whoever holds the workspace. */
interface FileActions {
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
