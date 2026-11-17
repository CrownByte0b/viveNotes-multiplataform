package com.vivenotes.pdf

import androidx.compose.ui.graphics.toPixelMap
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PaperSize
import com.vivenotes.model.RuleLines
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withVertex
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The stored cubic projection reaches both the export preview and independently rendered PDF. */
class CurvePdfTest {
    @Test
    fun stretchedCurvesKeepTheirRoundedSidesAndStayUnfilledInPreviewAndVectorPdf() = runBlocking {
        val directory = Files.createTempDirectory("curve-pdf").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db)
        val attachments = AttachmentStore(File(directory, "attachments"), db)
        val exporter = PdfExporter(repository, attachments)
        try {
            val section = repository.createSection(repository.createNotebook("Curves"), "Drawing")
            val page = repository.createPage(section, "Curve fixture")
            var next = 0
            fun curve(id: String, left: Float, top: Float, right: Float, bottom: Float) = Outline.Shape(
                id = id, kind = ShapeKind.Curve, borderWidth = 3f, borderArgb = 0xff182b49.toInt(),
                borderFollowsTheme = false,
                // Even stale fill data must not turn an open curve into a closed triangle.
                fillArgb = 0xffff0000.toInt(),
                segments = seedSegments(ShapeKind.Curve, left, top, right, bottom) { "s-${next++}" },
            ).withRecomputedBounds()
            val wide = curve("wide", 290f, 40f, 350f, 100f)
                .withVertex(0, 220f, 100f).withVertex(2, 420f, 100f)
            val tall = curve("tall", 40f, 220f, 160f, 280f)
                .withVertex(0, 40f, 440f).withVertex(2, 160f, 440f)
            val doc = PageDoc(style = PageStyle(hideTitle = true, paper = PaperSize.A4,
                ruleLines = RuleLines.None), outlines = listOf(wide, tall))
            repository.saveDoc(page, doc)
            val plan = exporter.plan(PdfExportRequest(page, null, PdfExportOptions(includeRuling = false)))
            val preview = assertNotNull(exporter.preview(plan, 0, plan.paper.widthDp.toInt())).toPixelMap()
            listOf(270 to 55, 370 to 55, 70 to 275, 130 to 275).forEach { (x, y) ->
                assertTrue(preview[x, y].red < .4f, "preview lost rounded side at ($x, $y)")
            }
            assertTrue(preview[320, 80].green > .9f, "preview filled the open curve")
            val output = File(directory, "curves.pdf")
            exporter.write(plan, output.path)
            Loader.loadPDF(output).use { pdf ->
                val rendered = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                listOf(270 to 55, 370 to 55, 70 to 275, 130 to 275).forEach { (x, y) ->
                    assertTrue((rendered.getRGB(x, y) shr 16 and 255) < 102,
                        "PDF lost rounded side at ($x, $y)")
                }
                assertTrue((rendered.getRGB(320, 80) shr 8 and 255) > 229, "PDF filled the open curve")
                assertTrue(pdf.getPage(0).resources.xObjectNames.none {
                    pdf.getPage(0).resources.getXObject(it) is org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                }, "curve export must retain vector paths")
            }
            assertEquals(doc, (repository.loadDoc(page) as PageLoad.Loaded).doc)
        } finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }
}
