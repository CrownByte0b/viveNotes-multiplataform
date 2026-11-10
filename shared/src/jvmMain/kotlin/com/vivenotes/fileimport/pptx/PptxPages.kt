// Ported from viveNotes Android fileimport/pptx/PptxPages.kt; Skia bitmaps replace android.graphics.
package com.vivenotes.fileimport.pptx

import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.DocxPages
import com.vivenotes.fileimport.docx.WordObjects
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A presentation, held open for the import window.
 *
 * Slides are laid out by the same engine as a Word document, each as one page of shapes placed where
 * the slide puts them, so previews and page pictures come from there. Objects are worked out per
 * slide, and a drawing among them is drawn on a page of its own to be cut out as a picture.
 */
internal class PptxPages private constructor(
    private val pkg: DocxPackage,
    private val read: PptxRead,
    private val slides: DocxPages,
    private val figures: DocxPages?,
) : ImportedDocument {

    override val pages: List<ImportedPageSize> get() = slides.pages

    override fun render(index: Int, widthPx: Int): Bitmap? = slides.render(index, widthPx)

    override suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> {
        val parts = read.figureParts
        val chosen = pages.sorted().mapNotNull { read.slides.getOrNull(it) }
        val pieces = joinSlides(chosen.map { piecesOf(it, read.slideWidthTwips, parts) })
        return WordObjects(read.document, marks) { part -> figureBytes(part) ?: pkg.bytes(part) }.items(pieces)
    }

    /** The drawing named [part], drawn as sharply as is any use once it reaches the attachment store. */
    private fun figureBytes(part: String): ByteArray? {
        val index = read.figureIndex(part) ?: return null
        val figure = read.figures.getOrNull(index) ?: return null
        val longest = max(figure.width, figure.height).coerceAtLeast(1)
        val scale = min(FIGURE_PIXELS_PER_TWIP, FIGURE_LONGEST_PX / longest)
        val bitmap = figures?.render(index, (figure.width * scale).roundToInt().coerceAtLeast(1)) ?: return null
        return try {
            Image.makeFromBitmap(bitmap).use { image -> image.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes } }
        } finally {
            bitmap.close()
        }
    }

    override fun close() = pkg.close()

    companion object {
        /** Reads and lays out [pkg]; takes ownership of it. */
        suspend fun open(pkg: DocxPackage): PptxPages {
            val read = PptxReader(pkg).read()
            val parts: (String) -> ByteArray? = { pkg.bytes(it) }
            // The package outlives these, and closing it is this object's to do.
            val slides = DocxPages.of(read.document, parts, emptyMap(), Closeable {})
            val figures = read.figureDocument?.let { DocxPages.of(it, parts, emptyMap(), Closeable {}) }
            return PptxPages(pkg, read, slides, figures)
        }

        private const val FIGURE_PIXELS_PER_TWIP = 0.2f
        private const val FIGURE_LONGEST_PX = 2048f
    }
}
