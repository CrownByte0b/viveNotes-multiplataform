package com.vivenotes.pdf

import androidx.compose.ui.graphics.toPixelMap
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.PageLoad
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.nio.file.Files
import kotlin.test.*

// The reported page's geometry, with synthetic text instead of the user's document contents.
internal fun oversizedPdfTextDoc(bodyRepeats: Int = 3): PageDoc {
    val font = Mark.FontSize(24)
    return PageDoc(
        style = PageStyle(hideTitle = true, ruleLines = RuleLines.None),
        outlines = listOf(
            Outline.Text("wide-text", x = 415.23874f, y = 499.5782f, width = 2000f, minHeight = 374.04373f,
                blocks = listOf(
                    Block("heading", runs = listOf(Run("Wide text box", setOf(font, Mark.TextColor(0xffcc0000.toInt()))))),
                    Block("body", runs = listOf(Run(
                        "This sentence should wrap at the printable right margin, without placing its continuation on a sheet to the right. ".repeat(bodyRepeats),
                        setOf(font)))),
                    Block("blank-one"), Block("blank-two"),
                    Block("end", runs = listOf(Run("END OF TEXT", setOf(font, Mark.TextColor(0xff0000cc.toInt()))))),
                )),
            Outline.Shape("shape-below", x = 512.2995f, y = 1043.3761f, width = 700.2323f, height = 434.67072f),
        ),
    )
}

class PdfTextWrappingTest {
    @Test
    fun wrappedLargeTextKeepsReadableLineSpacing() = runBlocking {
        val fontSize = 24f
        for (family in listOf("sans-serif", "serif", "monospace", "inter", "lora", "jetbrains_mono")) {
            val layout = PdfTextMeasurer().layout(listOf(Block("large", runs = listOf(Run(
                "Large text needs enough space between every wrapped line. ".repeat(8),
                setOf(Mark.FontSize(fontSize.toInt()), Mark.FontFamily(family)))))), 400f, PdfCanvasColors.forPage(PageStyle()))
            val paragraph = layout.paragraphs.single().layout
            assertTrue(paragraph.lineCount > 2)
            for (line in 1 until paragraph.lineCount) {
                val spacing = paragraph.getLineBaseline(line) - paragraph.getLineBaseline(line - 1)
                assertTrue(spacing >= fontSize * PageMeasurer.LINE_SPACING_MULTIPLIER - .01f,
                    "wrapped lines must use the actual font size, not the 15 sp fallback (font=$family, spacing=$spacing)")
            }
        }
    }

    @Test
    fun aParagraphsLineSpacingMultipliesTheGapBetweenItsLines() = runBlocking {
        val text = "Spaced lines need room between every wrapped line of the paragraph. ".repeat(8)
        suspend fun gaps(spacing: Float): List<Float> {
            val paragraph = PdfTextMeasurer().layout(listOf(Block("spaced", lineSpacing = spacing,
                runs = listOf(Run(text, setOf(Mark.FontSize(18)))))), 400f, PdfCanvasColors.forPage(PageStyle()))
                .paragraphs.single().layout
            assertTrue(paragraph.lineCount > 2)
            return (1 until paragraph.lineCount).map { paragraph.getLineBaseline(it) - paragraph.getLineBaseline(it - 1) }
        }
        val line = 18f * PageMeasurer.LINE_SPACING_MULTIPLIER
        for (spacing in listOf(1f, 1.5f, 2f, 3f)) {
            // Skia rounds the parts of each line to whole pixels.
            gaps(spacing).forEach { assertEquals(line * spacing, it, 1f + line * spacing * 0.02f, "spacing $spacing") }
        }
        assertEquals(gaps(3f), gaps(12f), "a stored spacing past the menu's last is drawn as that")
    }

    @Test
    fun oversizedTextWrapsToThePrintableWidthAtItsOriginalFontSize() = runBlocking {
        val doc = oversizedPdfTextDoc(bodyRepeats = 30)
        val outline = doc.outlines.filterIsInstance<Outline.Text>().single()
        val original = PdfTextMeasurer().layout(outline.blocks,
            outline.width - PageMeasurer.TEXT_PADDING_DP * 2, PdfCanvasColors.forPage(doc.style))
        val papers = listOf(
            PdfPaper.of(PaperSize.A4, Orientation.Portrait),
            PdfPaper.of(PaperSize.A4, Orientation.Portrait, margins = PrintMargins.uniform(1f)),
            PdfPaper.of(PaperSize.A5, Orientation.Portrait),
            PdfPaper.of(PaperSize.A4, Orientation.Landscape),
        )
        papers.forEach { paper ->
            val page = PageMeasurer().measure("page", "Test", 0L, doc, emptyList(), paper)
            try {
                val layout = page.texts.getValue(outline.id).layout
                assertEquals(paper.tileWidthDp, page.boundsOf(outline.id)!!.width, .001f)
                assertEquals(paper.tileWidthDp - PageMeasurer.TEXT_PADDING_DP * 2, layout.width, .001f)
                assertTrue(layout.paragraphs[1].layout.lineCount > original.paragraphs[1].layout.lineCount,
                    "a long paragraph must gain lines at the printable width (paper=$paper, " +
                        "original=${original.paragraphs[1].layout.lineCount}, " +
                        "wrapped=${layout.paragraphs[1].layout.lineCount})")
                assertEquals(original.text, layout.text, "wrapping must retain every character")
                assertTrue(layout.paragraphs.flatMap { it.layout.layoutInput.text.spanStyles }
                    .all { it.item.fontSize.value == 24f }, "wrapping must retain the chosen font size")
            } finally { page.close() }
        }
    }

    @Test
    fun printableWidthCanChangeLineBreaksWithoutChangingTheLineCount() = runBlocking {
        val outline = Outline.Text("same-line-count", x = 0f, y = 0f, width = 2000f,
            blocks = listOf(Block("body", runs = listOf(Run("W ".repeat(90).trimEnd(),
                setOf(Mark.FontSize(24), Mark.FontFamily("jetbrains_mono")))))))
        val doc = PageDoc(style = PageStyle(hideTitle = true), outlines = listOf(outline))
        val paper = PdfPaper.of(PaperSize.A4, Orientation.Landscape)
        val colors = PdfCanvasColors.forPage(doc.style)
        val text = PdfTextMeasurer()
        val original = text.layout(outline.blocks, outline.width - PageMeasurer.TEXT_PADDING_DP * 2, colors)
        val expected = text.layout(outline.blocks, paper.tileWidthDp - PageMeasurer.TEXT_PADDING_DP * 2, colors)
        val originalParagraph = original.paragraphs.single().layout
        val expectedParagraph = expected.paragraphs.single().layout
        assertEquals(2, originalParagraph.lineCount, "the bundled-font fixture spans two original lines")
        assertEquals(originalParagraph.lineCount, expectedParagraph.lineCount,
            "both widths intentionally need two lines, so line count alone cannot detect reflow")
        assertTrue(expectedParagraph.getLineEnd(0) < originalParagraph.getLineEnd(0),
            "the narrower printable width must place fewer characters on the first line")

        val page = PageMeasurer().measure("page", "Test", 0L, doc, emptyList(), paper)
        try {
            val actual = page.texts.getValue(outline.id).layout
            val actualParagraph = actual.paragraphs.single().layout
            assertEquals(paper.tileWidthDp, page.boundsOf(outline.id)!!.width, .001f)
            assertEquals(expected.width, actual.width, .001f)
            assertEquals(expectedParagraph.layoutInput.constraints, actualParagraph.layoutInput.constraints)
            assertEquals((0 until expectedParagraph.lineCount).map(expectedParagraph::getLineStart),
                (0 until actualParagraph.lineCount).map(actualParagraph::getLineStart))
            assertEquals((0 until expectedParagraph.lineCount).map { expectedParagraph.getLineEnd(it) },
                (0 until actualParagraph.lineCount).map { actualParagraph.getLineEnd(it) })
            assertEquals(original.text, actual.text)
            assertTrue(actualParagraph.layoutInput.text.spanStyles.all { it.item.fontSize.value == 24f })
            assertEquals(doc, page.doc, "export reflow must preserve the source font and document")
        } finally { page.close() }
    }

    @Test
    fun aTextBoxThatAlreadyFitsKeepsItsWidthAndLineBreaks() = runBlocking {
        val original = oversizedPdfTextDoc()
        val outline = original.outlines.filterIsInstance<Outline.Text>().single().copy(width = 650f)
        val doc = original.copy(outlines = listOf(outline))
        val paper = PdfPaper.of(PaperSize.A4, Orientation.Portrait)
        val page = PageMeasurer().measure("page", "Test", 0L, doc, emptyList(), paper)
        try {
            val expected = PdfTextMeasurer().layout(outline.blocks,
                outline.width - PageMeasurer.TEXT_PADDING_DP * 2, page.colors)
            assertEquals(outline.width, page.boundsOf(outline.id)!!.width, .001f)
            assertEquals(expected.height, page.texts.getValue(outline.id).layout.height)
            assertEquals(expected.paragraphs.map { it.layout.lineCount },
                page.texts.getValue(outline.id).layout.paragraphs.map { it.layout.lineCount })
        } finally { page.close() }
    }

    @Test
    fun aBoundSheetPreservesItsOriginalTextLayoutEvenInsidePrintMargins() = runBlocking {
        val paper = PdfPaper.of(PaperSize.A4, Orientation.Portrait)
        val original = oversizedPdfTextDoc()
        val outline = original.outlines.filterIsInstance<Outline.Text>().single().copy(x = 0f, y = 120f, width = paper.widthDp - 12f)
        val doc = original.copy(style = original.style.copy(paper = PaperSize.A4), outlines = listOf(outline))
        val page = PageMeasurer().measure("page", "Test", 0L, doc, emptyList(), paper)
        try {
            assertNotNull(page.boundSheet)
            assertTrue(outline.width > paper.tileWidthDp)
            assertEquals(outline.width, page.boundsOf(outline.id)!!.width, .001f)
        } finally { page.close() }
    }

    @Test
    fun planningPreviewAndSavedPdfWrapTheSameTextAndFitOffKeepsCanvasTiling() = runBlocking {
        val directory = Files.createTempDirectory("vive-pdf-text-wrap").toFile()
        val db = NotesDatabase.create(File(directory, "notes.db"))
        val repository = NotesRepository(db)
        val logs = mutableListOf<String>()
        val exporter = PdfExporter(repository, log = DebugLog(true, logs::add))
        try {
            val section = repository.createSection(repository.createNotebook("Book"), "Section")
            val page = repository.createPage(section, "Test")
            val doc = oversizedPdfTextDoc()
            repository.saveDoc(page, doc)
            val options = PdfExportOptions(includeRuling = false)
            val raw = exporter.plan(PdfExportRequest(page, section, options.copy(fitContent = false)))
            assertEquals(listOf(0, 1), raw.pages.single().plan.tiles.map { it.column })
            exporter.preview(raw, 0, 700)
            assertTrue(logs.none { it.contains("wrapped") }, "fit off must not reflow text")

            val fitted = exporter.plan(PdfExportRequest(page, section, options))
            assertEquals(1, fitted.sheetCount, "a wide paragraph must wrap instead of continuing sideways")
            assertEquals(listOf(0), fitted.pages.single().plan.tiles.map { it.column })
            val preview = exporter.preview(fitted, 0, 1300)!!.toPixelMap()
            assertTrue((0 until preview.height).any { y -> (0 until preview.width).any { x ->
                val color = preview[x, y]; color.red > .5f && color.green < .3f && color.blue < .3f
            } }, "the first text must be in the preview")
            assertTrue((0 until preview.height).any { y -> (0 until preview.width).any { x ->
                val color = preview[x, y]; color.blue > .5f && color.green < .3f && color.red < .3f
            } }, "the final text must be on the same preview sheet")

            val out = File(directory, "wrapped.pdf")
            assertEquals(1, exporter.write(fitted, out.path))
            Loader.loadPDF(out).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                val image = PDFRenderer(pdf).renderImageWithDPI(0, 160f)
                var red = 0
                var blue = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    val r = rgb ushr 16 and 255; val g = rgb ushr 8 and 255; val b = rgb and 255
                    if (r > 128 && g < 80 && b < 80) red++
                    if (b > 128 && g < 80 && r < 80) blue++
                }
                assertTrue(red > 30 && blue > 30, "PDFBox must render both the first and last text on one sheet")
            }
            val wideMargins = exporter.plan(PdfExportRequest(page, section, options.copy(margins = PrintMargins.uniform(1f))))
            assertTrue(wideMargins.pages.single().plan.tiles.all { it.column == 0 })
            exporter.preview(wideMargins, 0, 700)
            assertTrue(logs.any { it.contains("[pdf] wrapped 1 wide text boxes") })
            assertFalse(logs.any { it.contains("END OF TEXT") || it.contains("This sentence") || it.contains("Test") })
            assertEquals(doc, (repository.loadDoc(page) as PageLoad.Loaded).doc, "wrapping is export-only")
        } finally { exporter.release(); db.close(); directory.deleteRecursively() }
    }

    @Test
    fun longWideTextContinuesDownTheSheetsInsteadOfAcrossThem() = runBlocking {
        val doc = oversizedPdfTextDoc(bodyRepeats = 150)
        val paper = PdfPaper.of(PaperSize.A4, Orientation.Portrait)
        val page = PageMeasurer().measure("page", "Test", 0L, doc, emptyList(), paper)
        try {
            val plan = PageTiling.plan(page.items, paper.tileWidthDp, paper.tileHeightDp)
            assertTrue(plan.tiles.size > 1)
            assertTrue(plan.tiles.all { it.column == 0 }, "all continuation sheets must be below the first")
            assertEquals((0 until plan.tiles.size).toList(), plan.tiles.map { it.row })
            assertEquals("END OF TEXT", page.texts.getValue("wide-text").layout.paragraphs.last().layout.layoutInput.text.text)
        } finally { page.close() }
    }
}
