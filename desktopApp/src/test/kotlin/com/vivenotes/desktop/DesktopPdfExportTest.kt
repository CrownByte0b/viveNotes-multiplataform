package com.vivenotes.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.vivenotes.pdf.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopPdfExportTest {
    private val renderer = object : PdfExportService {
        override suspend fun defaultOptionsFor(pageId: String?) = PdfExportOptions()
        override suspend fun plan(request: PdfExportRequest): PdfExportPlan = error("not used")
        override suspend fun preview(plan: PdfExportPlan, sheetIndex: Int, maxWidthPx: Int): ImageBitmap? = null
        override suspend fun chooseDestination(suggestedName: String): String? = null
        override suspend fun write(plan: PdfExportPlan, destination: String, onProgress: (Int, Int) -> Unit) = 0
        override suspend fun release() {}
    }

    @Test
    fun savePickerRemembersTheFolderAndCancellationDoesNotChangeIt() = runBlocking {
        val directory = Files.createTempDirectory("vive-pdf-dialog").toFile()
        try {
            var picked: File? = File(directory, "notes")
            val seen = mutableListOf<Pair<String, File?>>()
            var repaints = 0
            val service = DesktopPdfExport(renderer, chooseSave = { name, folder ->
                assertTrue(javax.swing.SwingUtilities.isEventDispatchThread())
                seen += name to folder
                picked
            }, onUpdate = { repaints++ })
            assertEquals(File(directory, "notes.pdf").path, service.chooseDestination("Draft.pdf"))
            picked = null
            assertNull(service.chooseDestination("Section.pdf"))
            picked = File(directory, "changed.PDF")
            assertEquals(picked.path, service.chooseDestination("New.pdf"))
            assertEquals(listOf("Draft.pdf" to null, "Section.pdf" to directory, "New.pdf" to directory), seen)
            assertEquals(3, repaints, "native Wayland repaints after save selection or cancellation")
            val options = PdfExportOptions()
            val plan = PdfExportPlan(options, PdfPaper.of(options.paper, options.orientation), emptyList(), emptyList(), "Notes.pdf")
            service.write(plan, "unused")
            assertEquals(4, repaints, "completion repaints without waiting for another pointer event")
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun addingTheExtensionNeverOverwritesAFileThePickerDidNotConfirm() {
        val directory = Files.createTempDirectory("vive-pdf-extension").toFile()
        try {
            File(directory, "notes.pdf").writeText("existing")
            File(directory, "notes (1).pdf").writeText("existing copy")
            assertEquals(File(directory, "notes (2).pdf"), withPdfExtension(File(directory, "notes")))
            val exact = File(directory, "notes.pdf")
            assertEquals(exact, withPdfExtension(exact))
            assertEquals("existing", exact.readText())
        } finally { directory.deleteRecursively() }
    }
}
