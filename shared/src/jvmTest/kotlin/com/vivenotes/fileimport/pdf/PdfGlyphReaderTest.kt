package com.vivenotes.fileimport.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Android reads from Pdfium's text, read with PDFBox: the characters in the file's order, spaces and
 * line ends between them, and a box around each letter's ink on the page as it is drawn.
 */
class PdfGlyphReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    /** One Letter page with each of [lines] set at its baseline, measured from the top, at [size] points. */
    private fun glyphs(vararg lines: Pair<String, Float>, size: Float = 20f, crop: PDRectangle? = null): List<PdfGlyph> {
        val file = folder.newFile()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.LETTER)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                for ((text, baseline) in lines) {
                    stream.beginText()
                    stream.setFont(helvetica, size)
                    stream.newLineAtOffset(72f, 792f - baseline)
                    stream.showText(text)
                    stream.endText()
                }
            }
            crop?.let { page.cropBox = it }
            document.save(file)
        }
        return Loader.loadPDF(file).use { PdfGlyphReader(0).read(it) }
    }

    private fun text(glyphs: List<PdfGlyph>): String = glyphs.joinToString("") { it.char.toString() }.trim()

    @Test
    fun wordsAreSpacedAndLinesEnded() {
        val read = glyphs("First line here" to 100f, "Second line" to 140f)

        assertEquals("First line here\nSecond line", text(read))
        assertTrue(read.filter { it.char == ' ' || it.char == '\n' }.all { it.box == null }, "spaces and line ends have no box")
        assertTrue(read.filter { it.char.isLetter() }.all { it.box != null })
    }

    /** Boxes hug the ink, as Pdfium's do: a capital is about 0.72 of the size tall and sits on the baseline. */
    @Test
    fun aLettersBoxHugsItsInk() {
        val read = glyphs("HxH" to 200f, size = 20f)
        val capital = read.first { it.char == 'H' }.box!!
        val small = read.first { it.char == 'x' }.box!!

        assertEquals(20f * 0.718f, capital.height, 0.6f)
        assertEquals(200f, capital.bottom, 0.5f)
        assertEquals(72f, capital.left, 2f)
        assertTrue(small.height < capital.height * 0.8f, "an x is shorter than an H: ${small.height} against ${capital.height}")
    }

    /** What Pdfium writes for a hyphen that exists only because the word ran out of line. */
    @Test
    fun aHyphenEndingALineAfterALetterIsASoftHyphen() {
        val read = glyphs("A word broken across hyph-" to 100f, "enation here" to 130f)

        val lineEnd = read.indexOfFirst { it.char == '\n' }
        assertEquals(SOFT_HYPHEN_MARK, read[lineEnd - 1].char)
        assertTrue(read[lineEnd - 1].box != null)
        // One in the middle of a line is a hyphen.
        val kept = glyphs("well-known words" to 100f)
        assertTrue(kept.any { it.char == '-' })
    }

    /** Boxes are measured from the visible part of the page, which need not start at the paper's corner. */
    @Test
    fun boxesStartAtTheCropBoxsCorner() {
        val whole = glyphs("Cropped" to 300f)
        val cropped = glyphs("Cropped" to 300f, crop = PDRectangle(50f, 100f, 400f, 600f))
        val before = whole.first { it.char == 'C' }.box!!
        val after = cropped.first { it.char == 'C' }.box!!

        assertEquals(before.left - 50f, after.left, 0.01f)
        // The crop box's top edge is at 700 from the paper's bottom, 92 points below the paper's top.
        assertEquals(before.top - 92f, after.top, 0.01f)
        assertNull(cropped.firstOrNull { it.char == '\n' })
    }
}
