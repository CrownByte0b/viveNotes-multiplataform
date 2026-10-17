package com.vivenotes.desktop

import com.vivenotes.localization.Strings
import com.vivenotes.pdf.PdfExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The Android document picker boundary, implemented with the same desktop dialogs as .vive export. */
internal class DesktopPdfExport(
    private val exporter: PdfExportService,
    private val chooseSave: (String, File?) -> File?,
    private val onUpdate: () -> Unit = {},
) : PdfExportService by exporter {
    private var directory: File? = null
    override suspend fun chooseDestination(suggestedName: String): String? =
        try { withContext(Dispatchers.Swing) { chooseSave(suggestedName, directory) }
            ?.let(::withPdfExtension)
            ?.also { directory = it.absoluteFile.parentFile }
            ?.path
        } finally { onUpdate() }

    override suspend fun write(plan: com.vivenotes.pdf.PdfExportPlan, destination: String,
        onProgress: (Int, Int) -> Unit): Int =
        try { exporter.write(plan, destination, onProgress) } finally { onUpdate() }
}

internal fun withPdfExtension(chosen: File): File {
    if (chosen.name.endsWith(".pdf", ignoreCase = true)) return chosen
    val parent = chosen.absoluteFile.parentFile
    var candidate = File(parent, "${chosen.name}.pdf")
    var copy = 1
    while (candidate.exists()) candidate = File(parent, "${chosen.name} (${copy++}).pdf")
    return candidate
}

internal fun choosePdfDestination(owner: Frame?, suggestedName: String, directory: File?): File? {
    val dialog = FileDialog(owner, Strings.export_pdf, FileDialog.SAVE)
    dialog.directory = (directory ?: File(System.getProperty("user.home"))).path
    dialog.file = suggestedName
    dialog.setFilenameFilter { _, name -> name.endsWith(".pdf", ignoreCase = true) }
    return try { dialog.isVisible = true; dialog.files.firstOrNull() } finally { dialog.dispose() }
}
