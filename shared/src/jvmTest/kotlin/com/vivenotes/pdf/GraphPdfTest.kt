package com.vivenotes.pdf

import com.vivenotes.data.*
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.graph.GraphPainter
import com.vivenotes.model.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class GraphPdfTest {
    private val graph = Outline.Graph("graph", x = 100f, y = 200f,
        expression = "((x^2)+(-4))", latex = "x^{2} - 4",
        originalExpression = "((x^2)+(-1))", originalLatex = "x^{2} - 1",
        xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0,
        showDerivative = true, traceX = 1.0, area = GraphInterval(-2.0, 2.0))
    private val document = PageDoc(style = PageStyle(hideTitle = true, paper = PaperSize.A4, ruleLines = RuleLines.None),
        outlines = listOf(graph))

    @Test
    fun exportedVectorGraphKeepsItsHeaderCurvesTangentAndAreaWithoutChangingStorage() = runBlocking {
        val directory = Files.createTempDirectory("vive-graph-pdf").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db)
        val exporter = PdfExporter(repository)
        try {
            val section = repository.createSection(repository.createNotebook("Graph"), "Functions")
            val page = repository.createPage(section, "Parabola")
            repository.saveDoc(page, document)
            val plan = exporter.plan(PdfExportRequest(page, section, PdfExportOptions(includeRuling = false)))
            assertEquals(1, plan.sheetCount)
            val output = File(directory, "graph.pdf")
            exporter.write(plan, output.path)
            Loader.loadPDF(output).use { pdf ->
                val image = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                fun matching(argb: Int): Int = (100..419).sumOf { x -> (200..459).count { y ->
                    val pixel = image.getRGB(x, y)
                    (0..2).all { shift -> abs(((pixel ushr (shift * 8)) and 255) - ((argb ushr (shift * 8)) and 255)) < 8 }
                } }
                assertTrue(matching(GraphPainter.DEFAULT_CURVE_ARGB) > 100, "main curve")
                assertTrue(matching(GraphPainter.TANGENT_ARGB) > 50, "tangent and tracer")
                assertTrue(matching(GraphPainter.DERIVATIVE_ARGB) > 30, "derivative")
                assertTrue((110..300).any { x -> (205..230).any { y -> (image.getRGB(x, y) and 0xffffff) < 0x606060 } }, "equation header")
                val below = image.getRGB(220, 400)
                assertTrue((below and 255) > ((below ushr 16) and 255), "violet shading below the axis")
                val content = pdf.getPage(0).contents.use { it.readBytes().decodeToString() }
                assertTrue(content.contains(" m") || content.contains(" l"), "graph exports as paths")
            }
            assertEquals(document, (repository.loadDoc(page) as PageLoad.Loaded).doc)
        } finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }

    /** A graph's function means nothing without its sliders' values: the export binds them, as the canvas does. */
    @Test
    fun aGraphWithSlidersExportsItsCurveAtTheirValues() = runBlocking {
        val sliders = Outline.Graph("graph", x = 100f, y = 200f, height = 260f + 36f,
            expression = "((\$0*(x^2))+(-4))", latex = "a x^{2} - 4", showEquation = false,
            xMin = -5.0, xMax = 5.0, yMin = -5.0, yMax = 5.0,
            parameters = listOf(GraphParameter("a", "a", 1.0)))
        val doc = document.copy(outlines = listOf(sliders))
        val directory = Files.createTempDirectory("vive-graph-sliders-pdf").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db)
        val exporter = PdfExporter(repository)
        try {
            val section = repository.createSection(repository.createNotebook("Graph"), "Functions")
            val page = repository.createPage(section, "Sliders")
            repository.saveDoc(page, doc)
            val output = File(directory, "sliders.pdf")
            exporter.write(exporter.plan(PdfExportRequest(page, section, PdfExportOptions(includeRuling = false))), output.path)
            Loader.loadPDF(output).use { pdf ->
                val image = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                val curve = (0 until image.width).sumOf { x -> (0 until image.height).count { y ->
                    val pixel = image.getRGB(x, y)
                    (0..2).all { shift ->
                        abs(((pixel ushr (shift * 8)) and 255) - ((GraphPainter.DEFAULT_CURVE_ARGB ushr (shift * 8)) and 255)) < 8
                    }
                } }
                // The curve and the slider's active track; an unbound function would draw neither curve.
                assertTrue(curve > 300, "curve and slider: $curve")
            }
            assertEquals(doc, (repository.loadDoc(page) as PageLoad.Loaded).doc)
        } finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }

    @Test
    fun graphBoundsAndLazyHeadersParticipateInTilingAndIndexedRendering() = runBlocking {
        val paper = PdfPaper.of(PaperSize.A6, Orientation.Portrait)
        val doc = document.copy(style = document.style.copy(paper = PaperSize.Auto),
            outlines = listOf(graph.copy(x = 900f, y = 1200f)))
        val measurer = PageMeasurer()
        val page = measurer.measure("page", "", 0L, doc, emptyList(), paper, fitContent = false, loadDrawingAssets = false)
        try {
            assertEquals(PdfBounds(900f, 1200f, 1220f, 1460f), page.boundsOf(graph.id))
            assertTrue(page.equations.isEmpty())
            measurer.hydrateDrawingAssets(page)
            assertNotNull(page.equations[graph.id])
            val plan = PageTiling.plan(page.items, paper.tileWidthDp, paper.tileHeightDp, fit = false)
            PageRenderer().use { renderer ->
                for (tile in plan.tiles) {
                    val shift = page.shiftOf(graph.id, plan)
                    val lands = page.boundsOf(graph.id)!!.translated(shift.dx, shift.dy).overlaps(tile.area)
                    assertEquals(lands, page.visibleContent(plan, tile).graphs.isNotEmpty())
                    fun pixels(indexed: Boolean): ByteArray = Surface.makeRasterN32Premul(500, 700).use { surface ->
                        renderer.useVisibilityIndex = indexed
                        renderer.drawSheet(surface.canvas, page, tile, plan, paper, false, 1f)
                        surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } }
                    }
                    assertContentEquals(pixels(false), pixels(true))
                }
            }
        } finally { page.close() }
    }
}
