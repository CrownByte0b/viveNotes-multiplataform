package com.vivenotes.ui.ribbon.file

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.NotesRepository
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.model.*
import com.vivenotes.model.ink.*
import com.vivenotes.pdf.*
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PdfExportPreviewTest {
    @Test
    fun storedRichTextEquationsShapesAndTablesAppearInTheRealPreview() = runBlocking {
        val db = NotesDatabase.inMemory()
        val repository = NotesRepository(db)
        val exporter = PdfExporter(repository)
        try {
            val section = repository.createSection(repository.createNotebook("Sample"), "Export")
            val page = repository.createPage(section, "PDF export")
            var edge = 0
            val table = Outline.Table("table", x = 650f, y = 550f, width = 500f, columns = listOf(250f, 250f),
                rows = listOf(TableRow("r1", 90f, listOf(TableCell("c1", listOf(Block.of("Item"))),
                    TableCell("c2", listOf(Block.of("Status")))))), headerRow = true)
            val shape = Outline.Shape("shape", x = 100f, y = 550f, width = 400f, height = 180f,
                segments = seedSegments(ShapeKind.Rectangle, 100f, 550f, 500f, 730f) { "edge-${edge++}" },
                fillArgb = 0xffdfecfa.toInt(), borderArgb = 0xff0063c6.toInt(), borderFollowsTheme = false,
                borderWidth = 4f)
            val doc = PageDoc(style = PageStyle(paper = PaperSize.A4, ruleLines = RuleLines.GridSmall),
                outlines = listOf(
                    Outline.Text("text", x = 100f, y = 180f, width = 950f, blocks = listOf(
                        Block(id = "heading", runs = listOf(Run("Export from Vive Notes", setOf(Mark.Bold, Mark.FontSize(26))))),
                        Block(id = "paragraph", runs = listOf(Run("Text, ink, pictures, tables and equations share one print layout."))),
                        Block(id = "bullet", type = BlockType.Bullet, runs = listOf(Run("Page and section export with live previews"))),
                        Block(id = "math", runs = listOf(Run("Math: \$\$ x^2 + y^2 = z^2 \$\$"))),
                    )), shape, table,
                    Outline.Equation("equation", x = 100f, y = 850f, width = 600f, height = 100f,
                        latex = "\\int_a^b f'(t)\\,dt = f(b) - f(a)"),
                ))
            repository.saveDoc(page, doc)
            val request = PdfExportRequest(page, section, PdfExportOptions())
            exporter.write(exporter.plan(request), File(System.getProperty("java.io.tmpdir"), "vive-pdf-export-preview.pdf").path)
            runDesktopComposeUiTest(width = 1100, height = 800) {
                setContent { ViveNotesTheme(darkTheme = true) {
                    ExportPdfDialog(exporter, page, section, "PDF export", "Export", {})
                } }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithContentDescription("Sheet 1").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Sheet 1").assertIsDisplayed()
                onNodeWithTag(ExportPdfTags.EXPORT).assertIsEnabled()
                val capture = onRoot().captureToImage()
                val pixels = capture.toPixelMap()
                val image = BufferedImage(capture.width, capture.height, BufferedImage.TYPE_INT_ARGB)
                for (x in 0 until image.width) for (y in 0 until image.height) image.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(image, "png", File(System.getProperty("java.io.tmpdir"), "vive-pdf-export-preview.png"))
            }
        } finally { exporter.release(); db.close() }
    }
}
