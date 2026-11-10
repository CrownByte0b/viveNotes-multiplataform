package com.vivenotes.ui.ribbon.file

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.fileimport.FileImportService
import com.vivenotes.ui.shell.CanvasPlacement
import com.vivenotes.workspace.WorkspaceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Import ▸ File from the ribbon to the window: the platform's file chooser, then [ImportFileWindow]
 * over the workspace for the file chosen, until it closes. Android's `importFileSource`.
 */
internal class ImportFileRequests {
    /** The document whose pages are being chosen, while the window is open. */
    var source by mutableStateOf<String?>(null)
        private set

    fun choose(importer: FileImportService, scope: CoroutineScope, log: DebugLog) {
        if (source != null) return
        scope.launch {
            val chosen = try {
                importer.chooseSource()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.failure("import", "file chooser", failure)
                null
            }
            log.event("import") { if (chosen == null) "no file chosen" else "file chosen" }
            source = chosen
        }
    }

    fun close() {
        source = null
    }
}

/** The import window for [source], putting what it inserts on the open page through [ImportedPages]. */
@Composable
internal fun ImportFileWindow(
    importer: FileImportService,
    source: String,
    state: () -> WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    pictures: PictureLibrary?,
    placement: CanvasPlacement,
    log: DebugLog,
    onDismiss: () -> Unit,
) {
    val pages = remember(importer, placement, pictures) { ImportedPages(state, onStateChange, pictures, placement, log) }
    ImportFileDialog(
        importer = importer,
        open = { importer.open(source) },
        onInsert = { file, chosen, mode -> pages.insert(importer, file, chosen, mode) },
        onDismiss = onDismiss,
    )
}
