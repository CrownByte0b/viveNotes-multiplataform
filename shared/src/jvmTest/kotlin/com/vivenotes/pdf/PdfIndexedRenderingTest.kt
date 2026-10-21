package com.vivenotes.pdf

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.ink.toEntity
import com.vivenotes.model.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlin.test.*

class PdfIndexedRenderingTest {
    @Test
    fun sourceIdentityIncludesColorThemeAndGroupChangesWithUnchangedInputBytes() = runBlocking {
        val directory = Files.createTempDirectory("vive-pdf-source-key").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db, clock = { 1_000_000L })
        try {
            val section = repository.createSection(repository.createNotebook("Book"), "Section")
            val page = repository.createPage(section, "Page")
            val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 10f, 20f, 0L)
                add(InputToolType.MOUSE, 30f, 40f, 20L)
            }
            repository.addStroke(tool.complete(Stroke(tool.brush, inputs), "stroke", page, 0, 1L).row.toEntity())
            val initial = loadPdfSource(repository, page)!!.identity
            repository.setInkColors(mapOf("stroke" to StrokeColor(0xffcc0000.toInt(), false)))
            val recolored = loadPdfSource(repository, page)!!.identity
            assertNotEquals(initial, recolored)
            repository.setInkGroups(mapOf("stroke" to "group"))
            val grouped = loadPdfSource(repository, page)!!.identity
            assertNotEquals(recolored, grouped)
            assertEquals(grouped, loadPdfSource(repository, page)!!.identity)
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test
    fun visibleParagraphAndTableDrawingHasExactPixelsAtEveryTileBoundary() = runBlocking {
        val blocks = List(160) { index ->
            Block("paragraph-$index", type = listOf(BlockType.Bullet, BlockType.Numbered, BlockType.Todo,
                BlockType.Quote, BlockType.Heading2, BlockType.Code)[index % 6], checked = index % 2 == 0,
                runs = listOf(Run("Paragraph $index with wrapped words.", setOf(Mark.FontSize(18)))))
        }
        var id = 0
        val table = newTable(2, 50, x = 360f, y = 30f, columnWidth = 110f, rowHeight = 70f,
            headerRow = true, headerColumn = true, newId = { "table-${id++}" }).let { base ->
            base.copy(rows = base.rows.mapIndexed { row, value -> value.copy(cells = value.cells.mapIndexed { column, cell ->
                cell.copy(blocks = listOf(Block("cell-$row-$column", type = BlockType.Quote,
                    runs = listOf(Run("Row $row column $column with words.")))))
            }) })
        }
        val doc = PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
            outlines = listOf(Outline.Text("text", x = 10f, width = 300f, blocks = blocks), table))
        val paper = PdfPaper.of(PaperSize.A6, Orientation.Portrait, margins = PrintMargins.uniform(0f))
        val page = PageMeasurer().measure("page", "", 0L, doc, emptyList(), paper, fitContent = false)
        val plan = PageTiling.plan(page.items, paper.tileWidthDp, paper.tileHeightDp, fit = false)
        assertTrue(plan.tiles.size > 3)
        PageRenderer().use { full -> PageRenderer().use { indexed ->
            full.useVisibilityIndex = false
            full.work = PdfWorkCounts()
            indexed.work = PdfWorkCounts()
            try {
                for (tile in plan.tiles) {
                    assertContentEquals(render(full, page, tile, plan, paper), render(indexed, page, tile, plan, paper),
                        "tile ${tile.column},${tile.row}")
                }
                assertTrue(indexed.work!!.paragraphsDrawn < full.work!!.paragraphsDrawn / 2,
                    "off-tile paragraph work must be omitted")
                assertTrue(indexed.work!!.tableCellsDrawn < full.work!!.tableCellsDrawn / 2,
                    "off-tile cells must be omitted")
            } finally { page.close() }
        } }
    }

    @Test
    fun tileMembershipMatchesExactBoundsAfterFittingAndKeepsPaintOrder() = runBlocking {
        val outlines = List(120) { index -> Outline.Shape("shape-$index", x = index % 8 * 180f,
            y = index / 8 * 170f, width = 90f, height = 95f, borderWidth = 3f) }
        val doc = PageDoc(style = PageStyle(hideTitle = true), outlines = outlines)
        val paper = PdfPaper.of(PaperSize.A6, Orientation.Portrait)
        val page = PageMeasurer().measure("page", "", 0L, doc, emptyList(), paper)
        try {
            val plan = PageTiling.plan(page.items, paper.tileWidthDp, paper.tileHeightDp)
            for (tile in plan.tiles) {
                val expected = outlines.filter { outline ->
                    val shift = page.shiftOf(outline.id, plan)
                    page.boundsOf(outline.id)!!.translated(shift.dx, shift.dy).overlaps(tile.area)
                }.map { it.id }
                assertEquals(expected, page.visibleContent(plan, tile).shapes.map { it.id })
            }
        } finally { page.close() }
    }

    @Test
    fun sectionPlanningStreamsAndReusesBoundedMeasurementsWithExactContentInvalidation() = runBlocking {
        val directory = Files.createTempDirectory("vive-pdf-indexed").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db, clock = { 1_000_000L })
        val exporter = PdfExporter(repository)
        try {
            val section = repository.createSection(repository.createNotebook("Book"), "Section")
            val pages = List(2) { index ->
                repository.createPage(section, "Page $index").also { page ->
                    repository.saveDoc(page, PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
                        outlines = listOf(Outline.Text("text-$index", blocks = listOf(Block.of("Body $index"))))))
                }
            }
            val plan = exporter.plan(PdfExportRequest(pages.first(), section,
                PdfExportOptions(scope = PdfExportScope.Section, includeRuling = false)))
            assertEquals(2, exporter.work.measuredPages)
            exporter.preview(plan, 0, 400)
            exporter.preview(plan, 1, 400)
            exporter.preview(plan, 0, 400)
            assertEquals(2, exporter.work.measuredPages, "planning layouts must survive preview page switches")
            assertTrue(exporter.work.peakPreparedPages <= 2)
            repository.saveDoc(pages.first(), PageDoc(style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
                outlines = listOf(Outline.Text("changed", blocks = listOf(Block.of("Changed at the same timestamp"))))))
            exporter.preview(plan, 0, 400)
            assertEquals(3, exporter.work.measuredPages, "content changes cannot hide behind equal timestamps")
            val output = File(directory, "indexed.pdf")
            assertEquals(plan.sheetCount, exporter.write(plan, output.path))
            Loader.loadPDF(output).use { pdf ->
                assertEquals(plan.sheetCount, pdf.numberOfPages)
                val image = PDFRenderer(pdf).renderImage(0)
                assertTrue((0 until image.height).any { y -> (0 until image.width).any { x ->
                    image.getRGB(x, y) and 0xffffff != 0xffffff
                } })
            }
            exporter.release()
            exporter.preview(plan, 0, 400)
            assertTrue(exporter.work.measuredPages > 3, "release must discard all retained measurements")
        } finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }

    private fun render(renderer: PageRenderer, page: MeasuredPage, tile: PdfTile, plan: PdfPagePlan,
        paper: PdfPaper): ByteArray = Surface.makeRasterN32Premul((paper.widthDp * .5f).roundToInt(),
            (paper.heightDp * .5f).roundToInt()).use { surface ->
        renderer.drawSheet(surface.canvas, page, tile, plan, paper, ruling = false, scale = .5f)
        surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
    }
}
