package com.vivenotes.desktop

import com.vivenotes.data.NotebookExportResult
import com.vivenotes.data.NotebookFiles
import com.vivenotes.data.NotebookImportResult
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.VIVE_EXTENSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * `.vive` files on desktop: the platform's file dialogs to choose one, and the transfer manager to
 * write or restore it. Each dialog opens as a Swing event of its own, for the reason
 * [DesktopPictures] gives, and starts in the folder the last one ended in.
 */
internal class DesktopNotebookFiles(
    private val transfers: NotebookTransferManager,
    private val chooseSave: (suggestedName: String, directory: File?) -> File?,
    private val chooseOpen: (directory: File?) -> File?,
) : NotebookFiles {

    private var directory: File? = null

    override suspend fun chooseExportDestination(suggestedName: String): String? =
        withContext(Dispatchers.Swing) { chooseSave(suggestedName, directory) }
            ?.let(::withViveExtension)
            ?.also { directory = it.absoluteFile.parentFile }
            ?.path

    override suspend fun chooseImportSource(): String? =
        withContext(Dispatchers.Swing) { chooseOpen(directory) }
            ?.also { directory = it.absoluteFile.parentFile }
            ?.path

    override suspend fun export(notebookId: String, destination: String): NotebookExportResult =
        transfers.exportNotebook(notebookId, File(destination))

    override suspend fun import(source: String): NotebookImportResult = transfers.importNotebook(File(source))
}

/**
 * The file an export writes: [chosen] itself when it ends in `.vive`, else that name with the
 * extension added. The dialog confirmed replacing only the name as typed, so an added extension
 * never lands on a file already there; like Android's document providers, the next free
 * `Name (1).vive` is used instead.
 */
internal fun withViveExtension(chosen: File): File {
    if (chosen.name.endsWith(VIVE_EXTENSION, ignoreCase = true)) return chosen
    val parent = chosen.absoluteFile.parentFile
    var candidate = File(parent, chosen.name + VIVE_EXTENSION)
    var copy = 1
    while (candidate.exists()) candidate = File(parent, "${chosen.name} (${copy++})$VIVE_EXTENSION")
    return candidate
}

/** Asks where to save a notebook: GTK on Linux under X11 and native Wayland, native on Windows. */
internal fun chooseNotebookDestination(owner: Frame?, suggestedName: String, directory: File?): File? =
    showNotebookDialog(FileDialog(owner, "Export Notebook", FileDialog.SAVE), directory) { dialog ->
        dialog.file = suggestedName
    }

/** Asks for a `.vive` file to import. */
internal fun chooseNotebookSource(owner: Frame?, directory: File?): File? =
    showNotebookDialog(FileDialog(owner, "Import Notebook", FileDialog.LOAD), directory) { dialog ->
        dialog.isMultipleMode = false
        // Windows ignores filename filters but reads a pattern list from the file name field.
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) dialog.file = "*$VIVE_EXTENSION"
    }

private fun showNotebookDialog(dialog: FileDialog, directory: File?, configure: (FileDialog) -> Unit): File? {
    dialog.directory = (directory ?: File(System.getProperty("user.home"))).path
    dialog.setFilenameFilter { _, name -> name.endsWith(VIVE_EXTENSION, ignoreCase = true) }
    configure(dialog)
    try {
        dialog.isVisible = true
        return dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}
