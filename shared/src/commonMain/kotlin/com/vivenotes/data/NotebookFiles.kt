package com.vivenotes.data

/** A successfully imported notebook copy and where the UI should navigate afterward. */
data class NotebookImportResult(
    val notebookId: String,
    val notebookName: String,
    val firstSectionId: String?,
    val firstPageId: String?,
    val created: Boolean,
    /** At least one live notebook, section, or page in the archive replaced a local tombstone. */
    val restored: Boolean,
)

data class NotebookExportResult(val notebookName: String, val byteCount: Long)

/**
 * `.vive` notebook files — one notebook each, the format Android's File tab reads and writes: the
 * platform's file dialogs, and the transfer behind them.
 *
 * Paths are whatever the platform's dialogs return; common code only hands them back. The desktop
 * implements this over its file dialog and `NotebookTransferManager`; tests fake it.
 */
interface NotebookFiles {

    /**
     * Asks where to save a notebook, offering [suggestedName]. Null when the user cancels.
     *
     * Called from the workspace session's thread; an implementation that shows a modal dialog opens
     * it as an event of its own, for the reason [PictureLibrary.choose] gives.
     */
    suspend fun chooseExportDestination(suggestedName: String): String?

    /** Asks for a `.vive` file to import. Null when the user cancels. */
    suspend fun chooseImportSource(): String?

    /** Writes the notebook [notebookId] to [destination]; a failure's message is for the user. */
    suspend fun export(notebookId: String, destination: String): NotebookExportResult

    /** Checks the file at [source] completely, then restores the notebook it holds. */
    suspend fun import(source: String): NotebookImportResult
}

/** A notebook file's extension, as Android's `NotebookTransferManager.EXTENSION`. */
const val VIVE_EXTENSION = ".vive"

/**
 * Android's `viveFileName`: the file name a notebook is offered under, with the characters no
 * platform allows in one replaced.
 */
fun viveFileName(notebookName: String): String {
    val safe = notebookName.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .trim('.')
        .take(100)
        .ifBlank { "Notebook" }
    return "$safe$VIVE_EXTENSION"
}
