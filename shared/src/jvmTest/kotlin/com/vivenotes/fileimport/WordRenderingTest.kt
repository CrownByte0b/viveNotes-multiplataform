package com.vivenotes.fileimport

import com.vivenotes.fileimport.DocxTestFile.paragraph
import com.vivenotes.fileimport.docx.DocxPages
import com.vivenotes.fileimport.docx.WordParagraph
import com.vivenotes.fileimport.docx.WordText
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * What the desktop's stand-in for Android's StaticLayout and spans (`WordSpannable.kt`) must do as
 * Android's do: a run's shading behind its letters, Word's line spacing on every line, the first line's
 * indent against the rest, and tab stops.
 *
 * Pages are drawn at [PX_PER_INCH] on Letter with one-inch margins, so the text column starts at 100 px.
 */
class WordRenderingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val importer by lazy { FileImporter(folder.newFolder("cache")) }
    private val file: File by lazy { File(folder.root, "word-rendering.docx") }

    @After
    fun tearDown() {
        importer.release()
    }

    private fun open(body: String): ImportedFile = runBlocking {
        DocxTestFile.write(file, body)
        importer.openFile(file, file.name)
    }

    private fun page(body: String): Bitmap = runBlocking {
        requireNotNull(importer.previewBitmap(open(body), 0, (8.5f * PX_PER_INCH).toInt()))
    }

    /** Rows with ink on them, gathered into the bands the lines of text make. */
    private fun lineBands(bitmap: Bitmap): List<IntRange> {
        val rows = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> isInk(bitmap.getColor(x, y)) } }
        val bands = ArrayList<IntRange>()
        for (row in rows) {
            val last = bands.lastOrNull()
            if (last != null && row == last.last + 1) bands[bands.lastIndex] = last.first..row else bands += row..row
        }
        return bands
    }

    private fun leftmostInk(bitmap: Bitmap, band: IntRange): Int =
        (0 until bitmap.width).first { x -> band.any { y -> isInk(bitmap.getColor(x, y)) } }

    private fun rightmostInk(bitmap: Bitmap, band: IntRange): Int =
        (bitmap.width - 1 downTo 0).first { x -> band.any { y -> isInk(bitmap.getColor(x, y)) } }

    private fun isInk(argb: Int): Boolean = luminance(argb) < 128

    private fun luminance(argb: Int): Int =
        (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    /** A shaded run is its letters' own colour on its shading, not the shading's colour on black. */
    @Test
    fun aShadedRunKeepsItsInkAndPaintsItsShadingBehindIt() {
        val bitmap = page(
            """<w:p><w:r><w:rPr><w:color w:val="000000"/><w:shd w:val="clear" w:color="auto" w:fill="FFFF00"/></w:rPr>""" +
                """<w:t>Highlighted words on the page</w:t></w:r></w:p>""",
        )
        val band = lineBands(bitmap).first()
        var yellow = 0
        var dark = 0
        var black = 0
        for (y in band) {
            for (x in 0 until bitmap.width) {
                val argb = bitmap.getColor(x, y)
                val red = (argb shr 16) and 0xFF
                val green = (argb shr 8) and 0xFF
                val blue = argb and 0xFF
                if (red > 230 && green > 230 && blue < 40) yellow++
                if (luminance(argb) < 80) dark++
                if (red < 30 && green < 30 && blue < 30) black++
            }
        }
        assertTrue("no shading behind the run", yellow > 500)
        assertTrue("no letters in the run", dark > 50)
        assertTrue("the shading was drawn black: $black black pixels against $yellow yellow", black < yellow)
    }

    private val words = List(160) { "word$it" }.joinToString(" ")

    private fun heightOf(spacing: String): Int = runBlocking {
        val opened = open(paragraph(words, spacing))
        requireNotNull(importer.withPages(opened) { pages: DocxPages ->
            pages.sheets.first().pieces.single { (it.block.source as? WordParagraph)?.inlines?.firstOrNull() is WordText }.block.height
        })
    }

    /** Word's "Multiple" spacing multiplies every line, the last one included. */
    @Test
    fun doubleSpacingDoublesEveryLine() {
        val single = heightOf("""<w:spacing w:after="0" w:line="240" w:lineRule="auto"/>""")
        val double = heightOf("""<w:spacing w:after="0" w:line="480" w:lineRule="auto"/>""")
        assertEquals(2f, double.toFloat() / single, 0.02f)
    }

    /** "Exactly" sets each line's height whatever its letters are: 18 points is 180 layout units. */
    @Test
    fun exactSpacingSetsEveryLinesHeight() {
        val exact = heightOf("""<w:spacing w:after="0" w:line="360" w:lineRule="exact"/>""")
        val twice = heightOf("""<w:spacing w:after="0" w:line="720" w:lineRule="exact"/>""")
        assertTrue("$exact is not a whole number of 180-unit lines", exact > 180 && exact % 180 == 0)
        assertEquals(exact * 2, twice)
    }

    /** A hanging indent starts the first line half an inch in and every other line an inch in. */
    @Test
    fun aHangingIndentLeavesTheFirstLineOutAndTheRestIn() {
        val bitmap = page(paragraph(words, """<w:spacing w:after="0"/><w:ind w:left="1440" w:hanging="720"/>"""))
        val bands = lineBands(bitmap)
        assertTrue(bands.size >= 3)
        val first = leftmostInk(bitmap, bands[0])
        val second = leftmostInk(bitmap, bands[1])
        assertTrue("first line starts at $first", abs(first - (MARGIN_PX + PX_PER_INCH / 2)) <= INK_SLACK)
        assertTrue("second line starts at $second", abs(second - (MARGIN_PX + PX_PER_INCH)) <= INK_SLACK)
    }

    /** What follows a right tab ends at the stop, six and a half inches into the column. */
    @Test
    fun aRightTabStopEndsWhatFollowsItAtTheStop() {
        val bitmap = page(
            """<w:p><w:pPr><w:tabs><w:tab w:val="right" w:pos="9360"/></w:tabs></w:pPr>""" +
                """<w:r><w:t>Left</w:t></w:r><w:r><w:tab/><w:t>Right side</w:t></w:r></w:p>""",
        )
        val band = lineBands(bitmap).first()
        val right = rightmostInk(bitmap, band)
        assertTrue("the right text ends at $right", abs(right - (MARGIN_PX + 6.5f * PX_PER_INCH).toInt()) <= INK_SLACK)
        assertTrue(abs(leftmostInk(bitmap, band) - MARGIN_PX) <= INK_SLACK)
    }

    private companion object {
        const val PX_PER_INCH = 100
        const val MARGIN_PX = 100

        /** A letter's ink starts a little inside its advance. */
        const val INK_SLACK = 6
    }
}
