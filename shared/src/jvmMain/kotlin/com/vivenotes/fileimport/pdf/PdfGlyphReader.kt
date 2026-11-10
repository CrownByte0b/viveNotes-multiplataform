// The desktop's version of what viveNotes Android's fileimport/pdf/PdfPages.kt reads from the
// platform's PdfRenderer (Pdfium): every character on a page, in the file's order, with spaces and
// line ends between them and a box around each character's ink. Apache PDFBox reads it here.
package com.vivenotes.fileimport.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.StringWriter

/**
 * A page's characters as [readLines] wants them.
 *
 * Pdfium's text puts a line end between lines and a space between words, generated where the file
 * has none, and its boxes hug each letter's ink — the layout's sizes and baselines are read off those
 * boxes. PDFBox's text stripper finds the same word gaps and line ends, and each box here is the
 * glyph's own outline, taken through the page's matrices to where the page is drawn, top left as
 * origin, so it lines up with the pixels [PdfPages] renders.
 *
 * Spaces have no box, as on Android, whether the file drew them or not. A hyphen ending a line after
 * a letter becomes [SOFT_HYPHEN_MARK], which is what Pdfium writes for one; so does a soft hyphen.
 */
internal class PdfGlyphReader(private val pageIndex: Int) : PDFTextStripper() {
    private val glyphs = ArrayList<PdfGlyph>()
    private var toDisplay = AffineTransform()

    init {
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        // The file's own order, as Pdfium gives it; reading order is the layout's business.
        sortByPosition = false
    }

    fun read(document: PDDocument): List<PdfGlyph> {
        glyphs.clear()
        writeText(document, StringWriter())
        endLine()
        return glyphs.toList()
    }

    override fun startPage(page: PDPage) {
        // The stripper's matrices already start at the crop box's corner.
        toDisplay = displayTransform(page, fromCropCorner = true)
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (position in textPositions) {
            val unicode = position.unicode ?: continue
            if (unicode.isEmpty()) continue
            if (unicode.all { it.isWhitespace() }) {
                unicode.forEach { _ -> glyphs += PdfGlyph(' ', null) }
                continue
            }
            val box = inkBox(position) ?: continue
            // A ligature's letters share its box, a slice each, left to right.
            val share = box.width / unicode.length
            unicode.forEachIndexed { index, char ->
                glyphs += PdfGlyph(char, PdfRect(box.left + share * index, box.top, box.left + share * (index + 1), box.bottom))
            }
        }
    }

    override fun writeWordSeparator() {
        glyphs += PdfGlyph(' ', null)
    }

    override fun writeLineSeparator() {
        endLine()
        glyphs += PdfGlyph('\n', null)
    }

    /** A hyphen the line ended on after a letter is one that only exists because the word ran out of line. */
    private fun endLine() {
        val last = glyphs.lastOrNull() ?: return
        val before = glyphs.getOrNull(glyphs.size - 2)
        val soft = last.char == '­' || (last.char == '-' && before?.char?.isLetter() == true)
        if (soft && last.box != null) glyphs[glyphs.size - 1] = PdfGlyph(SOFT_HYPHEN_MARK, last.box)
    }

    /**
     * The box around [position]'s ink, in page points from the top left of the page as drawn.
     *
     * A glyph's outline is in its font's own units and the font matrix takes it to text space, as
     * PDFBox's renderer draws it: 1000 to the em for most fonts, but 2048 for a TrueType face such as
     * Liberation Sans or Arial standing in for a Type 1 font the file does not embed. The text
     * rendering matrix then takes it to the page's own space and [toDisplay] to the drawn page. A
     * Type 3 glyph has only the box its procedure declares, and a glyph with no outline at all — a
     * missing one — has the box its advance and the font's height give it, which is all there is to
     * go on.
     */
    private fun inkBox(position: TextPosition): PdfRect? {
        val font = position.font
        val code = position.characterCodes?.firstOrNull() ?: return null
        val transform = AffineTransform(toDisplay).apply { concatenate(position.textMatrix.createAffineTransform()) }
        val ink: Rectangle2D? = runCatching {
            val outline: Rectangle2D? = when (font) {
                is PDType3Font -> font.getCharProc(code)?.glyphBBox?.let { box ->
                    Rectangle2D.Float(box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                }
                is PDVectorFont -> font.getNormalizedPath(code).bounds2D.takeUnless { it.isEmpty }
                else -> null
            }
            outline?.let {
                transform.concatenate(font.fontMatrix.createAffineTransform())
                transform.createTransformedShape(it).bounds2D
            }
        }.getOrNull()
        // Without an outline, what PDFBox's own text positions say, turned with the page: the advance,
        // and the font's height above the baseline.
        val bounds = ink
            ?: Rectangle2D.Float(position.x, position.y - position.height, position.width, position.height)
                .takeUnless { it.isEmpty } ?: return null
        return PdfRect(bounds.minX.toFloat(), bounds.minY.toFloat(), bounds.maxX.toFloat(), bounds.maxY.toFloat())
    }

    companion object {
        /** A page's turn, as a quarter turn clockwise 0 to 3 times. */
        fun rotationOf(page: PDPage): Int = ((page.rotation % 360) + 360) % 360

        /** The page's size as drawn, in points: turned on its side when the page says so. */
        fun displaySize(page: PDPage): Pair<Float, Float> {
            val crop: PDRectangle = page.cropBox
            return if (rotationOf(page) == 90 || rotationOf(page) == 270) crop.height to crop.width else crop.width to crop.height
        }

        /**
         * From the page's own space to the page as drawn, top left as origin, in points: what PDFBox's
         * renderer applies before it scales, so a box found here lands on the pixels it draws.
         * [fromCropCorner] is for coordinates already measured from the crop box's corner.
         */
        fun displayTransform(page: PDPage, fromCropCorner: Boolean = false): AffineTransform {
            val crop = page.cropBox
            val rotation = rotationOf(page)
            return AffineTransform().apply {
                when (rotation) {
                    90 -> translate(crop.height.toDouble(), 0.0)
                    270 -> translate(0.0, crop.width.toDouble())
                    180 -> translate(crop.width.toDouble(), crop.height.toDouble())
                }
                if (rotation != 0) rotate(Math.toRadians(rotation.toDouble()))
                translate(0.0, crop.height.toDouble())
                scale(1.0, -1.0)
                if (!fromCropCorner) translate(-crop.lowerLeftX.toDouble(), -crop.lowerLeftY.toDouble())
            }
        }
    }
}
