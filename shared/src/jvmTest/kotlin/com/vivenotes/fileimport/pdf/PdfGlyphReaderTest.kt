package com.vivenotes.fileimport.pdf

import org.apache.fontbox.FontBoxFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.CIDFontMapping
import org.apache.pdfbox.pdmodel.font.FontMapper
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.FontMapping
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
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
    private fun glyphs(vararg lines: Pair<String, Float>, size: Float = 20f, crop: PDRectangle? = null): List<PdfGlyph> =
        read(page(*lines, size = size, crop = crop))

    private fun read(file: File): List<PdfGlyph> = Loader.loadPDF(file).use { PdfGlyphReader(0).read(it) }

    private fun page(vararg lines: Pair<String, Float>, size: Float = 20f, crop: PDRectangle? = null): File {
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
        return file
    }

    /** The box around every dark pixel of [file]'s page as PDFBox draws it, in points from the top left. */
    private fun inkOnPage(file: File): PdfRect {
        val scale = 4f
        val image = Loader.loadPDF(file).use { PDFRenderer(it).renderImage(0, scale, ImageType.GRAY) }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.raster.getSample(x, y, 0) >= 128) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        check(right >= 0) { "nothing was drawn" }
        return PdfRect(left / scale, top / scale, (right + 1) / scale, (bottom + 1) / scale)
    }

    private fun assertSameBox(expected: PdfRect, actual: PdfRect, tolerance: Float) {
        assertEquals(expected.left, actual.left, tolerance, "left of $actual against $expected")
        assertEquals(expected.top, actual.top, tolerance, "top of $actual against $expected")
        assertEquals(expected.right, actual.right, tolerance, "right of $actual against $expected")
        assertEquals(expected.bottom, actual.bottom, tolerance, "bottom of $actual against $expected")
    }

    /**
     * Runs [block] with every font PDFBox looks for answered by its own Liberation Sans, a TrueType
     * face of 2048 units to the em: what Linux machines without a Type 1 Helvetica (Liberation Sans)
     * and Windows (Arial) draw a PDF's unembedded Helvetica with.
     */
    private fun <T> withTrueTypeStandIn(block: (TrueTypeFont) -> T): T {
        val face = checkNotNull(PDDocument::class.java.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"))
            .use { TTFParser().parse(RandomAccessReadBuffer(it)) }
        val previous = FontMappers.instance()
        FontMappers.set(object : FontMapper {
            override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?) = FontMapping(face, false)
            override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?) = FontMapping<FontBoxFont>(face, false)
            override fun getCIDFont(baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?) =
                CIDFontMapping(null, face, false)
        })
        try {
            return block(face)
        } finally {
            FontMappers.set(previous)
            face.close()
        }
    }

    private fun text(glyphs: List<PdfGlyph>): String = glyphs.joinToString("") { it.char.toString() }.trim()

    @Test
    fun wordsAreSpacedAndLinesEnded() {
        val read = glyphs("First line here" to 100f, "Second line" to 140f)

        assertEquals("First line here\nSecond line", text(read))
        assertTrue(read.filter { it.char == ' ' || it.char == '\n' }.all { it.box == null }, "spaces and line ends have no box")
        assertTrue(read.filter { it.char.isLetter() }.all { it.box != null })
    }

    /**
     * Boxes hug the ink, as Pdfium's do: a capital sits on the baseline and is about 0.7 of the size
     * tall — 0.72 in Helvetica, 0.69 in Liberation Sans, whichever face this machine draws Helvetica
     * with — and the letters' boxes together cover exactly what the page draws.
     */
    @Test
    fun aLettersBoxHugsItsInk() {
        val file = page("HxH" to 200f, size = 20f)
        val read = read(file)
        val capital = read.first { it.char == 'H' }.box!!
        val small = read.first { it.char == 'x' }.box!!

        assertTrue(capital.height in 20f * 0.66f..20f * 0.75f, "an H is about 0.7 of the size tall: ${capital.height}")
        assertEquals(200f, capital.bottom, 0.5f)
        assertEquals(72f, capital.left, 2f)
        assertTrue(small.height < capital.height * 0.8f, "an x is shorter than an H: ${small.height} against ${capital.height}")
        assertSameBox(inkOnPage(file), read.mapNotNull { it.box }.reduce(PdfRect::union), tolerance = 0.5f)
    }

    /**
     * A TrueType face standing in for an unembedded Type 1 font draws in 2048 units to the em, not
     * 1000: its boxes are measured in those units, not twice the size of the letters.
     */
    @Test
    fun aTrueTypeStandInIsMeasuredInItsOwnUnits() = withTrueTypeStandIn { face ->
        val file = page("HxH" to 200f, size = 20f)
        val read = read(file)
        val capital = read.first { it.char == 'H' }.box!!
        val outline = face.getPath("H").bounds2D

        assertEquals((20.0 * outline.height / face.unitsPerEm).toFloat(), capital.height, 0.05f)
        assertEquals(200f, capital.bottom, 0.5f)
        assertSameBox(inkOnPage(file), read.mapNotNull { it.box }.reduce(PdfRect::union), tolerance = 0.5f)
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
