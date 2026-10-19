package com.vivenotes.pdf

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.data.*
import com.vivenotes.data.db.*
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ink.toEntity
import com.vivenotes.model.*
import com.vivenotes.model.ink.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class PdfRenderingTest {
    private suspend fun fixture(block: suspend (NotesRepository, AttachmentStore, String, File, PdfExporter) -> Unit) {
        val directory = Files.createTempDirectory("vive-pdf-render").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db)
        val attachments = AttachmentStore(File(directory, "attachments"), db)
        val section = repository.createSection(repository.createNotebook("Book"), "Section")
        val page = repository.createPage(section, "Title that must not enter logs")
        val exporter = PdfExporter(repository, attachments)
        try { block(repository, attachments, page, directory, exporter) }
        finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }
    private val style = PageStyle(hideTitle = true, paper = PaperSize.A4, ruleLines = RuleLines.None)

    @Test
    fun scriptsQuotesHeadingsAndNumberingUseAndroidsExportMetrics() = runBlocking {
        val layout = PdfTextMeasurer().layout(listOf(
            Block("script", runs = listOf(Run("H"), Run("2", setOf(Mark.Subscript, Mark.FontSize(20))), Run("O"))),
            Block("quote", type = BlockType.Quote, runs = listOf(Run("Quoted"))),
            Block("heading", type = BlockType.Heading1, runs = listOf(Run("Heading"))),
            Block("first", type = BlockType.Numbered, runs = listOf(Run("One"))),
            Block("second", type = BlockType.Numbered, runs = listOf(Run("Two"))),
            Block("plain", runs = listOf(Run("Break"))),
            Block("restart", type = BlockType.Numbered, runs = listOf(Run("One again"))),
        ), 600f, PdfCanvasColors.forPage(style))
        val script = layout.paragraphs.first().layout.layoutInput.text.spanStyles.first { it.start == 1 }
        assertEquals(15f, script.item.fontSize.value, "Android ScriptSizeSpan multiplies by 0.75")
        assertEquals(4f, layout.paragraphs[1].leading, "Android QuoteSpan's stripe and gap")
        assertEquals(15f * 1.55f, layout.paragraphs[2].layout.layoutInput.style.fontSize.value)
        assertEquals(listOf(null, null, null, 1, 2, null, 1), layout.paragraphs.map { it.ordinal })
    }

    @Test
    fun invalidInlineEquationsKeepTheirLatexFallbackAndPaperDefaultsComeFromThePage() = runBlocking {
        fixture { repository, _, page, _, exporter ->
            val source = "\\frac{"
            val doc = PageDoc(style = style.copy(orientation = Orientation.Landscape,
                margins = PrintMargins(topInches = 4f, bottomInches = .5f, leftInches = 0f, rightInches = .25f)),
                outlines = listOf(Outline.Text("invalid", blocks = listOf(Block(id = "block", runs = listOf(
                    Run("\uFFFC", setOf(Mark.Equation(source)))))))))
            repository.saveDoc(page, doc)
            val options = exporter.defaultOptionsFor(page)
            assertEquals(PaperSize.A4, options.paper)
            assertEquals(Orientation.Landscape, options.orientation)
            assertEquals(PrintMargins(1f, .5f, 0f, .25f), options.margins)
            val measured = PageMeasurer().measure(page, "", 0L, doc, emptyList(),
                PdfPaper.of(PaperSize.A4, Orientation.Landscape))
            try { assertEquals(source, measured.texts.values.single().layout.text) }
            finally { measured.close() }
        }
    }

    @Test
    fun imagesBackgroundShapesTypedAndInkOnlyTablesSurviveTheVectorPdf() = runBlocking {
        fixture { repository, attachments, page, directory, exporter ->
            val bytes = Surface.makeRasterN32Premul(40, 40).use { surface ->
                surface.canvas.clear(0x00000000)
                surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            }
            val image = attachments.import(bytes)!!
            var id = 0
            val shape = Outline.Shape("shape", x = 400f, y = 100f, width = 200f, height = 200f,
                segments = seedSegments(ShapeKind.Rectangle, 400f, 100f, 600f, 300f) { "edge-${id++}" },
                fillArgb = 0xff00cc00.toInt(), borderArgb = 0xffffffff.toInt(), borderFollowsTheme = true, borderWidth = 8f)
            val table = Outline.Table("typed", x = 100f, y = 500f, width = 400f, columns = listOf(200f, 200f),
                rows = listOf(TableRow("row", 120f, listOf(TableCell("a", listOf(Block.of("Visible cell"))),
                    TableCell("b", listOf(Block.of("Second cell")))))), headerRow = true,
                borderArgb = 0xffffffff.toInt(), borderFollowsTheme = true, borderWidth = 4f)
            val inkTable = table.copy(id = "ink", y = 800f, headerRow = false, fillArgb = 0xffeeee00.toInt(), inkOnly = true,
                rows = listOf(TableRow("empty", 120f, listOf(TableCell("c", emptyList()), TableCell("d", emptyList())))))
            val doc = PageDoc(style = style, outlines = listOf(
                Outline.Image("image", x = 100f, y = 100f, width = 200f, height = 200f,
                    attachmentId = image.attachmentId, backgroundArgb = 0xffcc0000.toInt()), shape, table, inkTable))
            repository.saveDoc(page, doc)
            val plan = exporter.plan(PdfExportRequest(page, null, PdfExportOptions(includeRuling = false)))
            val out = File(directory, "objects.pdf")
            exporter.write(plan, out.path)
            Loader.loadPDF(out).use { pdf ->
                val rendered = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                assertEquals(0xffcc0000.toInt(), rendered.getRGB(180, 180), "transparent picture's stored background")
                assertEquals(0xff00cc00.toInt(), rendered.getRGB(500, 200), "shape fill")
                assertEquals(0xffeeee00.toInt(), rendered.getRGB(180, 850), "ink-only table fill")
                assertTrue((120..300).any { x -> (530..560).any { y ->
                    (rendered.getRGB(x, y) and 0xffffff) < 0x808080 } }, "typed table text")
                val content = pdf.getPage(0).contents.use { it.readBytes().decodeToString() }
                assertTrue(content.contains(" m") || content.contains(" l"), "content is vector paths")
                assertFalse(content.contains("/Im0 Do") && content.length < 100, "the whole page must not be a screenshot")
            }
            assertEquals(doc, (repository.loadDoc(page) as PageLoad.Loaded).doc, "export must not edit the page")
        }
    }

    @Test
    fun erasedInkExportsOnlyItsLiveFragmentsAndKeepsExplicitWhite() = runBlocking {
        fixture { repository, _, page, directory, exporter ->
            repository.saveDoc(page, PageDoc(style = style))
            val pen = ViveInkTool(sizeDp = 12f, colorArgb = 0xffffffff.toInt(), colorFollowsTheme = true)
            val stroke = pen.complete(Stroke(pen.brush, MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 100f, 300f, 0L); add(InputToolType.MOUSE, 600f, 300f, 80L)
            }), "stroke", page, 0, 1L).row.toEntity()
            repository.addStroke(stroke)
            val mask = ViveBrushes.eraseMask(MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 350f, 260f, 0L); add(InputToolType.MOUSE, 350f, 340f, 30L)
            }, 30f)
            val erase = ViveInkCodec.encodeErase(mask, "erase", page, InkEraseMode.Normal, 2L, listOf(stroke.id))
            repository.addPartialErase(InkEraseEntity(erase.id, page, EraserMode.Normal, erase.sizeDp, erase.points,
                erase.enc, erase.createdAt), listOf(stroke.id))
            val whitePen = ViveInkTool(sizeDp = 12f, colorArgb = 0xffffffff.toInt(), colorFollowsTheme = false)
            repository.addStroke(whitePen.complete(Stroke(whitePen.brush, MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 100f, 500f, 0L); add(InputToolType.MOUSE, 600f, 500f, 80L)
            }), "white", page, 0, 4L).row.toEntity())
            val plan = exporter.plan(PdfExportRequest(page, null, PdfExportOptions(includeRuling = false)))
            val out = File(directory, "ink.pdf")
            exporter.write(plan, out.path)
            Loader.loadPDF(out).use { pdf ->
                val rendered = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                assertTrue((rendered.getRGB(200, 300) and 0xffffff) < 0x404040)
                assertEquals(0xffffffff.toInt(), rendered.getRGB(350, 300), "partial erase stays cut")
                assertEquals(0xffffffff.toInt(), rendered.getRGB(200, 500), "explicit white stays white")
            }
            assertContentEquals(stroke.points, repository.inkFor(page).first { it.id == stroke.id }.points)
        }
    }

    @Test
    fun previewCacheRespectsRulingAndPaperAndStoredBackground() = runBlocking {
        fixture { repository, _, page, _, exporter ->
            repository.saveDoc(page, PageDoc(style = style.copy(ruleLines = RuleLines.GridSmall, backgroundArgb = 0xff112233.toInt())))
            val options = PdfExportOptions(includeRuling = false)
            val noRules = exporter.plan(PdfExportRequest(page, null, options))
            val plain = exporter.preview(noRules, 0, 600)!!.toPixelMap()
            assertEquals(0xff112233.toInt(), plain[100, 100].toArgb())
            val withRules = exporter.plan(PdfExportRequest(page, null, options.copy(includeRuling = true)))
            val ruled = exporter.preview(withRules, 0, 600)!!.toPixelMap()
            assertTrue((20..580).any { x -> (20..780).any { y -> plain[x, y] != ruled[x, y] } })
            val landscape = exporter.plan(PdfExportRequest(page, null, options.copy(orientation = Orientation.Landscape)))
            val preview = exporter.preview(landscape, 0, 600)!!
            assertTrue(preview.width > preview.height)
            assertNull(exporter.preview(landscape, -1, 600))
            assertNull(exporter.preview(landscape, landscape.sheetCount, 600))
        }
    }

    @Test
    fun failureAndCancellationPreserveTheDestinationAndCleanStagingFilesAndLogsExcludeContents() = runBlocking {
        fixture { repository, _, page, directory, _ ->
            repository.saveDoc(page, PageDoc(style = style, outlines = listOf(Outline.Text("text", blocks = listOf(Block.of("private body"))))))
            val logs = mutableListOf<String>()
            val exporter = PdfExporter(repository, log = DebugLog(true, logs::add))
            val plan = exporter.plan(PdfExportRequest(page, null, PdfExportOptions()))
            val out = File(directory, "existing.pdf").apply { writeText("keep original") }
            assertFailsWith<CancellationException> { exporter.write(plan, out.path) { _, _ -> throw CancellationException() } }
            assertEquals("keep original", out.readText())
            assertFailsWith<IllegalStateException> { exporter.write(plan, out.path) { _, _ -> error("write failed") } }
            assertEquals("keep original", out.readText())
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") })
            exporter.write(plan, out.path)
            assertTrue(logs.any { it.contains("[pdf] wrote 1 sheets") })
            assertTrue(logs.any { it.contains("[pdf] write failed") })
            assertFalse(logs.any { it.contains("private body") || it.contains("Title that") || it.contains(out.path) })
            exporter.release()
        }
    }
}
