package com.vivenotes.pdf

import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.RuleLines
import com.vivenotes.model.Run
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfStartupTest {
    @Test
    fun exportsTextBeforeAnyComposeWindowExists() {
        val directory = Files.createTempDirectory("vive-pdf-startup").toFile()
        val log = File(directory, "process.log")
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/$javaName").absolutePath,
            "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
            "-cp", System.getProperty("java.class.path"), PdfStartupProcess::class.java.name,
            directory.absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "PDF startup timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(log.readText().contains("cold PDF export passed"), log.readText())
        } finally {
            process.destroyForcibly().waitFor()
            directory.deleteRecursively()
        }
    }
}

/** A fresh JVM prevents an earlier UI test from hiding missing backend initialization. */
object PdfStartupProcess {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val directory = File(args.single())
        val db = NotesDatabase.create(File(directory, "notes.db"))
        try {
            val repository = NotesRepository(db)
            val exporter = PdfExporter(repository)
            try {
                val section = repository.createSection(repository.createNotebook("Book"), "Section")
                val page = repository.createPage(section, "Cold start")
                repository.saveDoc(page, PageDoc(
                    style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
                    outlines = listOf(Outline.Text("text", blocks = listOf(
                        Block("block", runs = listOf(Run("Text before the first window"))),
                    ))),
                ))
                val plan = exporter.plan(PdfExportRequest(page, section, PdfExportOptions()))
                assertEquals(1, plan.sheetCount)
                assertTrue(assertNotNull(exporter.preview(plan, 0, 400)).width > 0)
                val output = File(directory, "cold.pdf")
                assertEquals(1, exporter.write(plan, output.path) { _, _ -> })
                Loader.loadPDF(output).use { pdf ->
                    assertEquals(1, pdf.numberOfPages)
                    val image = PDFRenderer(pdf).renderImage(0)
                    assertTrue((0 until image.height).any { y ->
                        (0 until image.width).any { x -> image.getRGB(x, y) and 0xFFFFFF < 0x808080 }
                    }, "The exported text must be visible")
                }
                // Repeated service construction must keep the same backend.
                PdfExporter(repository).release()
            } finally {
                exporter.release()
            }
        } finally {
            db.close()
        }
        println("cold PDF export passed")
    }
}
