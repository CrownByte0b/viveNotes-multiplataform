// Ported from viveNotes Android androidTest fileimport/PdfImportTest.kt: the same pages and
// assertions, written by PDFBox where Android's test writes them with android.graphics.pdf.PdfDocument.
package com.vivenotes.fileimport

import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The parts only the platform can run: opening, drawing, and where its renderer says the text is.
 *
 * Every file here is written by the test, so that none of it depends on a document from outside.
 */
class PdfImportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val importer by lazy { FileImporter(folder.newFolder("cache")) }
    private val file: File by lazy { File(folder.root, "pdf-import-test.pdf") }

    @After
    fun tearDown() {
        importer.release()
        file.delete()
    }

    /** What Android's test draws on a page's canvas, in its terms: from the top left, y down. */
    private class Canvas(private val stream: PDPageContentStream, private val height: Float) {
        private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

        /** [y] is the baseline, as `Canvas.drawText` takes it. */
        fun drawText(text: String, x: Float, y: Float, size: Float = 11f, rgb: Int = 0x000000) {
            stream.setNonStrokingColor(color(rgb))
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, height - y)
            stream.showText(text)
            stream.endText()
        }

        /** Each letter its own text object, as Chrome and Android's Skia write them. */
        fun drawLetters(text: String, x: Float, y: Float, size: Float = 11f) {
            var at = x
            for (char in text) {
                if (char != ' ') drawText(char.toString(), at, y, size)
                at += font.getStringWidth(char.toString()) / 1000f * size
            }
        }

        fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 0.75f) {
            stream.setStrokingColor(java.awt.Color.BLACK)
            stream.setLineWidth(width)
            stream.moveTo(x0, height - y0)
            stream.lineTo(x1, height - y1)
            stream.stroke()
        }

        fun drawRect(left: Float, top: Float, right: Float, bottom: Float, rgb: Int) {
            stream.setNonStrokingColor(color(rgb))
            stream.addRect(left, height - bottom, right - left, bottom - top)
            stream.fill()
        }

        private fun color(rgb: Int) = java.awt.Color(rgb)
    }

    private fun writePdf(
        widthPt: Int = 612,
        heightPt: Int = 792,
        vararg pages: (Canvas) -> Unit,
        crop: PDRectangle? = null,
    ) {
        PDDocument().use { document ->
            pages.forEach { draw ->
                val page = PDPage(PDRectangle(widthPt.toFloat(), heightPt.toFloat()))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream -> draw(Canvas(stream, heightPt.toFloat())) }
                crop?.let { page.cropBox = it }
            }
            document.save(file)
        }
    }

    private fun Canvas.table(left: Float, top: Float, columns: List<Float>, rowHeight: Float, rows: List<List<String>>) {
        val width = columns.sum()
        for (row in 0..rows.size) drawLine(left, top + row * rowHeight, left + width, top + row * rowHeight)
        var x = left
        for (column in 0..columns.size) {
            drawLine(x, top, x, top + rows.size * rowHeight)
            if (column < columns.size) x += columns[column]
        }
        rows.forEachIndexed { row, cells ->
            var cellLeft = left
            cells.forEachIndexed { column, text ->
                drawText(text, cellLeft + 6f, top + row * rowHeight + rowHeight * 0.7f)
                cellLeft += columns[column]
            }
        }
    }

    private fun objects(vararg pages: Int): List<CanvasPasteItem> = runBlocking {
        val opened = importer.openFile(file, file.name)
        importer.objects(opened, pages.toList(), emptySet())
    }

    private fun failureOpening(): ImportFailure {
        val thrown = assertThrows(ImportException::class.java) {
            runBlocking { importer.openFile(file, file.name) }
        }
        return thrown.failure
    }

    @Test
    fun itsPagesAndTheirSizesAreRead() = runBlocking {
        writePdf(pages = arrayOf({ _: Canvas -> }, { _: Canvas -> }, { _: Canvas -> }))

        val opened = importer.openFile(file, "three.pdf")

        assertEquals(3, opened.pages.size)
        // Letter is 8.5 by 11 inches, however many dp an inch is.
        assertEquals(11f / 8.5f, opened.pages[0].heightDp / opened.pages[0].widthDp, 0.01f)
    }

    /** A page's blank parts are transparent to the renderer, and white on what it is imported as. */
    @Test
    fun aPageDrawsOnWhitePaper() = runBlocking {
        writePdf(pages = arrayOf({ canvas: Canvas -> canvas.drawText("Hello", 72f, 72f) }))
        val opened = importer.openFile(file, file.name)

        val picture = requireNotNull(importer.previewBitmap(opened, 0, 306))

        assertEquals(306, picture.width)
        assertEquals(396, picture.height)
        assertEquals(WHITE, picture.getColor(300, 390))
    }

    @Test
    fun textComesInAsTextWhereThePageHasIt() {
        writePdf(pages = arrayOf({ canvas: Canvas -> canvas.drawText("The body of the page, as written.", 72f, 100f) }))

        val text = objects(0).single() as CanvasPasteItem.Text

        assertEquals(listOf("The body of the page, as written."), text.blocks.map { it.text })
    }

    @Test
    fun aRuledTableComesInAsATable() {
        writePdf(
            pages = arrayOf({ canvas: Canvas ->
                canvas.table(72f, 100f, listOf(120f, 120f, 120f), 24f, listOf(listOf("Name", "Rating", "Age"), listOf("Dustin", "7", "45"), listOf("Rusty", "10", "35")))
            }),
        )

        val outline = (objects(0).single() as CanvasPasteItem.Table).outline

        assertEquals(3, outline.columnCount)
        assertEquals(3, outline.rowCount)
        assertEquals(listOf("Dustin", "7", "45"), outline.rows[1].cells.map { it.plainText })
    }

    /** What a chart or a photograph comes down to, as far as the page can say. */
    @Test
    fun aDrawingComesInAsAPictureOfItself() {
        writePdf(pages = arrayOf({ canvas: Canvas -> canvas.drawRect(100f, 100f, 300f, 240f, 0x4472C4) }))

        val picture = objects(0).single() as CanvasPasteItem.Image

        assertTrue(requireNotNull(picture.bytes).isNotEmpty())
        // A figure keeps its proportions, which is all it has left of where it was.
        assertEquals(200f / 140f, picture.widthDp!! / picture.heightDp!!, 0.05f)
    }

    @Test
    fun onlyTheChosenPagesComeIn() {
        writePdf(
            pages = arrayOf(
                { canvas: Canvas -> canvas.drawText("Page one here.", 72f, 100f) },
                { canvas: Canvas -> canvas.drawText("Page two here.", 72f, 100f) },
            ),
        )

        val text = objects(1).single() as CanvasPasteItem.Text

        assertEquals(listOf("Page two here."), text.blocks.map { it.text })
    }

    @Test
    fun whatRepeatsAtTheFootOfEveryPageIsLeftOut() {
        val page = { number: Int ->
            { canvas: Canvas ->
                canvas.drawText("Body of page $number.", 72f, 100f)
                canvas.drawText("Course notes, do not distribute", 72f, 770f)
                Unit
            }
        }
        writePdf(pages = arrayOf(page(1), page(2), page(3), page(4)))

        val text = objects(0, 1, 2, 3).joinToString("\n") { (it as CanvasPasteItem.Text).blocks.joinToString("\n") { block -> block.text } }

        assertTrue(text, "Body of page 3." in text)
        assertFalse(text, "do not distribute" in text)
    }

    /** One text box to a slide, so they can be moved about separately on the canvas. */
    @Test
    fun aLandscapePageIsASlideWithATextBoxOfItsOwn() {
        val page = { canvas: Canvas ->
            canvas.drawText("Slide text one.", 72f, 100f)
            Unit
        }
        writePdf(widthPt = 720, heightPt = 540, pages = arrayOf(page, page))

        assertEquals(2, objects(0, 1).size)
    }

    @Test
    fun aPasswordProtectedFileSaysSo() {
        file.writeBytes(encryptedPdf())

        assertEquals(ImportFailure.PasswordProtected, failureOpening())
    }

    @Test
    fun aBrokenFileSaysSo() {
        file.writeBytes("%PDF-1.7\nthis is not really a pdf".toByteArray())

        assertEquals(ImportFailure.Unreadable, failureOpening())
    }

    // --- desktop: what PDFBox reads, where Android has Pdfium ---------------------------------------

    /** Chrome and Android's Skia set each letter on its own, with no spaces; the words still come in. */
    @Test
    fun aPageWrittenALetterAtATimeComesInAsItsWords() {
        writePdf(pages = arrayOf({ canvas: Canvas -> canvas.drawLetters("The body of the page, as written.", 72f, 100f) }))

        val text = objects(0).single() as CanvasPasteItem.Text

        assertEquals(listOf("The body of the page, as written."), text.blocks.map { it.text })
    }

    /**
     * The colour is read off the pixels under each letter's box, so the boxes must land where the
     * renderer draws, even on a page whose visible part does not start at the corner of its paper.
     */
    @Test
    fun colouredTextKeepsItsColourOnACroppedPage() {
        writePdf(
            pages = arrayOf({ canvas: Canvas -> canvas.drawText("A heading set in red ink.", 160f, 220f, size = 18f, rgb = 0xD02020) }),
            crop = PDRectangle(100f, 100f, 412f, 592f),
        )

        val runs = (objects(0).single() as CanvasPasteItem.Text).blocks.single().runs

        assertEquals("A heading set in red ink.", runs.joinToString("") { it.text })
        val colours = runs.filter { it.text.isNotBlank() }.map { run -> run.marks.filterIsInstance<Mark.TextColor>().singleOrNull()?.argb }
        assertTrue("every word should be red: $colours", colours.all { it != null && isRed(it) })
    }

    private fun isRed(argb: Int): Boolean {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return red > 150 && green < 90 && blue < 90
    }

    /**
     * A one-page file whose encryption dictionary asks for a password nothing here knows. The renderer
     * only has to compare a hash to refuse it, so the hashes don't have to be anyone's real ones.
     */
    private fun encryptedPdf(): ByteArray {
        val hash = "AB".repeat(32)
        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>",
            "<</Filter/Standard/V 1/R 2/O<$hash>/U<$hash>/P -4>>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            out.length.also { out.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
        out.append("trailer\n<</Size ${objects.size + 1}/Root 1 0 R/Encrypt 4 0 R/ID[<00><00>]>>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private companion object {
        /** Android's `Color.WHITE`. */
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
