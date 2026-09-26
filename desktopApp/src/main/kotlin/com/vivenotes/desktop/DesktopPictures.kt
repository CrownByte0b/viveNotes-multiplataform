package com.vivenotes.desktop

import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Pictures on desktop: the platform's file dialog to choose one, and the attachment store to keep
 * it. [chooseFile] runs on the UI thread, where a modal dialog belongs; reading and re-encoding the
 * file happen off it.
 */
internal class DesktopPictures(
    private val store: AttachmentStore,
    private val chooseFile: () -> File?,
) : PictureLibrary {

    override suspend fun choose(): ImportedPicture? {
        val file = chooseFile() ?: return null
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return null
        return store.import(bytes)
    }

    override suspend fun bytes(attachmentId: String): ByteArray? = store.bytes(attachmentId)
}

/** Extensions Skia decodes, offered in the Insert picture dialog. */
internal val PictureExtensions = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "wbmp")

/**
 * Asks for one picture with the system dialog: GTK on Linux, under X11 and under JBR's native
 * Wayland toolkit alike, and the native dialog on Windows. Null when the user cancels.
 */
internal fun choosePictureFile(owner: Frame?): File? {
    val dialog = FileDialog(owner, "Insert picture", FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in PictureExtensions }
    // Windows ignores filename filters but reads a pattern list from the file name field.
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        dialog.file = PictureExtensions.joinToString(";") { "*.$it" }
    }
    try {
        dialog.isVisible = true
        return dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}
