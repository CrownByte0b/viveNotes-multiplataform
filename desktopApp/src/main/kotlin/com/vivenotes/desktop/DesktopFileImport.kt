package com.vivenotes.desktop

import com.vivenotes.fileimport.FileImportService
import com.vivenotes.localization.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Asks for a document to import with the system dialog — GTK on Linux under X11 and native Wayland,
 * the native one on Windows, the portal's in a Flatpak — offering the files Android's picker offers by
 * MIME type: Word (.docx, .doc), OpenDocument text, PowerPoint (.pptx) and PDF. Null when the user
 * cancels. Started as its own Swing event, for the reason [DesktopPictures] gives.
 */
internal fun chooseImportDocument(owner: Frame?, directory: File?): File? {
    val extensions = FileImportService.FILE_EXTENSIONS
    if (FlatpakFileDialogs.enabled) return FlatpakFileDialogs.choose(Strings.import_file_title, extensions)
    val dialog = FileDialog(owner, Strings.import_file_title, FileDialog.LOAD)
    dialog.directory = (directory ?: File(System.getProperty("user.home"))).path
    dialog.isMultipleMode = false
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
    // Windows ignores filename filters but reads a pattern list from the file name field.
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        dialog.file = extensions.joinToString(";") { "*.$it" }
    }
    try {
        dialog.isVisible = true
        return dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}
