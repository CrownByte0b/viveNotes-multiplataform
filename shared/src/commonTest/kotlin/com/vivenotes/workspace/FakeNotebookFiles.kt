package com.vivenotes.workspace

import com.vivenotes.data.NotebookExportResult
import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.NotebookImportResult
import kotlinx.coroutines.CompletableDeferred

/**
 * [NotebookFiles] without dialogs or files: it answers the dialogs as told, records what it was
 * asked to transfer, and imports by running [onImport] against the test's [FakeNotesStore].
 */
class FakeNotebookFiles(private val store: FakeNotesStore) : NotebookFiles {

    /** What the save dialog returns; null is the user cancelling it. */
    var destination: String? = "/home/ada/Documents/chosen.vive"

    /** What the open dialog returns; null is the user cancelling it. */
    var source: String? = "/home/ada/Downloads/archive.vive"

    val suggestedNames = mutableListOf<String>()
    val exports = mutableListOf<Pair<String, String>>()
    val imports = mutableListOf<String>()

    /** Storage's writes as they stood when the transfer ran: whatever the open page needed first. */
    var writesAtTransfer: List<String>? = null

    /** The name an export reports writing. */
    var exportedName = "Exported"
    var failure: Exception? = null

    /** Holds the transfer itself until completed, so what the workspace does meanwhile can be seen. */
    var gate: CompletableDeferred<Unit>? = null

    /** What an import does to storage, and what it reports. */
    var onImport: suspend () -> NotebookImportResult = { error("no import was set up") }

    override suspend fun chooseExportDestination(suggestedName: String): String? {
        suggestedNames += suggestedName
        return destination
    }

    override suspend fun chooseImportSource(): String? = source

    override suspend fun export(notebookId: String, destination: String): NotebookExportResult {
        gate?.await()
        failure?.let { throw it }
        writesAtTransfer = store.writes.toList()
        exports += notebookId to destination
        return NotebookExportResult(exportedName, 4_096)
    }

    override suspend fun import(source: String): NotebookImportResult {
        gate?.await()
        failure?.let { throw it }
        writesAtTransfer = store.writes.toList()
        imports += source
        return onImport()
    }
}
