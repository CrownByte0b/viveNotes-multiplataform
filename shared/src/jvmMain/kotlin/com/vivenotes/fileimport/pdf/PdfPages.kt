// Ported from viveNotes Android fileimport/pdf/PdfPages.kt. Apache PDFBox does what Android's
// platform PdfRenderer does there: drawing the pages, and saying which character is where.
package com.vivenotes.fileimport.pdf

import com.vivenotes.fileimport.ImportException
import com.vivenotes.fileimport.ImportFailure
import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.fileimport.docx.WordDocument
import com.vivenotes.fileimport.docx.WordObjects
import com.vivenotes.fileimport.docx.WordSettings
import com.vivenotes.fileimport.toSkiaBitmap
import com.vivenotes.model.Mark
import com.vivenotes.model.PageStyle
import com.vivenotes.model.normalizedLinkUrl
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.ensureActive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.RenderDestination
import org.jetbrains.skia.Bitmap
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A PDF, held open for the import window.
 *
 * Android renders PDFs and will say what text is where, which between them cover everything here:
 * the page pictures are the renderer's own, and the objects are worked out from the text's positions
 * and the rendered pixels. Here PDFBox renders and [PdfGlyphReader] reads the text. Used from one
 * coroutine at a time, as the renderer requires.
 */
internal class PdfPages private constructor(
    private val document: PDDocument,
    private val renderer: PDFRenderer,
    override val pages: List<ImportedPageSize>,
) : ImportedDocument {

    /** Found once, on the first insert: it takes every page, and most windows are only looked at. */
    private var furniture: PdfFurniture? = null

    override fun render(index: Int, widthPx: Int): Bitmap? {
        if (index !in pages.indices) return null
        val (pageWidth, pageHeight) = PdfGlyphReader.displaySize(document.getPage(index))
        val scale = widthPx.toFloat() / pageWidth
        val height = (pageHeight * scale).roundToInt().coerceAtLeast(1)
        return drawn(index, widthPx.coerceAtLeast(1), height, scale).toSkiaBitmap()
    }

    /**
     * A blank page's pixels are transparent, and what a page is drawn on is white paper. [left] and
     * [top], in points, are the page's place that lands at the picture's corner.
     */
    private fun drawn(index: Int, width: Int, height: Int, scale: Float, left: Float = 0f, top: Float = 0f): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.background = Color.WHITE
            graphics.clearRect(0, 0, width, height)
            graphics.translate((-left * scale).toDouble(), (-top * scale).toDouble())
            renderer.renderPageToGraphics(index, graphics, scale, scale, RenderDestination.VIEW)
        } finally {
            graphics.dispose()
        }
        return image
    }

    override suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> {
        val chosen = pages.sorted().filter { it in this.pages.indices }
        val found = furnitureOf()
        val figures = HashMap<String, Pair<Int, PdfRect>>()
        val inputs = chosen.map { index ->
            coroutineContext.ensureActive()
            readPage(index, found, figures)
        }
        val bodyEm = bodyEmOf(inputs)
        val pieces = joinPages(
            inputs.map { input ->
                coroutineContext.ensureActive()
                layoutPage(input, bodyEm)
            },
        )
        val document = WordDocument(emptyList(), emptyMap(), emptyMap(), WordSettings())
        return WordObjects(document, marks) { part -> figures[part]?.let { (page, box) -> figureBytes(page, box) } }.items(pieces)
    }

    /** A sample spread through the file: what repeats on every page shows up in any of them. */
    private fun furnitureOf(): PdfFurniture = furniture ?: PdfFurniture.find(
        (0 until pages.size).toList().sample(FURNITURE_SAMPLE_PAGES).map { index ->
            wholeLines(readGlyphs(index)) to PdfGlyphReader.displaySize(document.getPage(index)).second
        },
    ).also { furniture = it }

    private fun List<Int>.sample(count: Int): List<Int> =
        if (size <= count) this else List(count) { this[it * size / count] }

    /** Every character on the page and where it is; see [PdfGlyphReader]. */
    private fun readGlyphs(index: Int): List<PdfGlyph> = PdfGlyphReader(index).read(document)

    /** Where the page's links go, and the boxes on the page they cover, as Pdfium's link contents say. */
    private fun linksOf(page: PDPage): List<Pair<String, List<PdfRect>>> {
        val toDisplay = PdfGlyphReader.displayTransform(page)
        fun displayed(box: Rectangle2D): PdfRect = toDisplay.createTransformedShape(box).bounds2D.let {
            PdfRect(it.minX.toFloat(), it.minY.toFloat(), it.maxX.toFloat(), it.maxY.toFloat())
        }
        val annotations = runCatching { page.annotations }.getOrNull().orEmpty()
        return annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
            val uri = (runCatching { link.action }.getOrNull() as? PDActionURI)?.uri ?: return@mapNotNull null
            val url = normalizedLinkUrl(uri) ?: return@mapNotNull null
            // A link across a line break covers each line's part of it: one box per quadrilateral.
            val quads = link.quadPoints?.takeIf { it.size >= 8 }?.toList()?.chunked(8)?.filter { it.size == 8 }.orEmpty()
            val boxes = quads.map { quad ->
                val xs = quad.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = quad.filterIndexed { i, _ -> i % 2 == 1 }
                displayed(Rectangle2D.Float(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min()))
            }.ifEmpty {
                val rect = link.rectangle ?: return@mapNotNull null
                listOf(displayed(Rectangle2D.Float(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)))
            }
            url to boxes
        }
    }

    private fun readPage(index: Int, found: PdfFurniture, figures: MutableMap<String, Pair<Int, PdfRect>>): PdfPageInput {
        val page = document.getPage(index)
        val (width, height) = PdfGlyphReader.displaySize(page)
        val glyphs = readGlyphs(index)
        val everyLine = readLines(glyphs)
        val lines = readLines(glyphs) { line, box -> found.matches(line, box, height) }
            .filterNot { it.rotated && isInSideMargin(it.box, width) }
        val links = linksOf(page)
        lines.forEach { line ->
            line.words.forEach { word ->
                word.link = links.firstOrNull { (_, boxes) -> boxes.any { it.expanded(2f).containsCenterOf(word.box) } }?.first
            }
        }
        settleMonospace(lines)
        val scale = LAYOUT_PIXELS_PER_POINT
        val image = drawn(
            index,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            scale,
        )
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val raster = PdfRaster(pixels, image.width, image.height, scale)
        readWordLooks(raster, lines)
        // Masked including what is left out of the text, or a margin stamp would read as a figure.
        return PdfPageInput(width, height, lines, readInk(raster, everyLine.flatMap { it.words })) { figure ->
            val part = "page-$index-figure-${figures.size}"
            figures[part] = index to figure
            part
        }
    }

    private fun isInSideMargin(box: PdfRect, pageWidth: Float): Boolean =
        box.right < pageWidth * SIDE_MARGIN_SHARE || box.left > pageWidth * (1f - SIDE_MARGIN_SHARE)

    /** A figure cut out of its page, as sharp as is any use once it reaches the attachment store. */
    private fun figureBytes(index: Int, box: PdfRect): ByteArray? {
        val longest = max(box.width, box.height).coerceAtLeast(1f)
        val scale = min(FIGURE_PIXELS_PER_POINT, FIGURE_LONGEST_PX / longest)
        val width = (box.width * scale).roundToInt().coerceAtLeast(1)
        val height = (box.height * scale).roundToInt().coerceAtLeast(1)
        val image = drawn(index, width, height, scale, box.left, box.top)
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    override fun close() {
        document.close()
    }

    companion object {
        /** `%PDF-` may sit a little way in, after junk some producers put first. */
        fun isPdf(file: File): Boolean = file.inputStream().use { input ->
            val head = ByteArray(1024)
            val read = input.read(head)
            read > 0 && String(head, 0, read, Charsets.ISO_8859_1).contains("%PDF-")
        }

        fun open(file: File): PdfPages {
            val document = try {
                Loader.loadPDF(file)
            } catch (locked: InvalidPasswordException) {
                throw ImportException(ImportFailure.PasswordProtected, locked)
            } catch (broken: IOException) {
                throw ImportException(ImportFailure.Unreadable, broken)
            }
            try {
                // Pdfium will not open a file with no pages; PDFBox rebuilds one from almost anything.
                if (document.numberOfPages == 0) throw ImportException(ImportFailure.Unreadable)
                if (document.numberOfPages > MAX_PAGES) throw ImportException(ImportFailure.TooLarge)
                val sizes = (0 until document.numberOfPages).map { index ->
                    val (width, height) = PdfGlyphReader.displaySize(document.getPage(index))
                    ImportedPageSize(width.toDp(), height.toDp())
                }
                return PdfPages(document, PDFRenderer(document), sizes)
            } catch (failure: Throwable) {
                document.close()
                throw failure
            }
        }

        private fun Float.toDp(): Float = this / POINTS_PER_INCH * PageStyle.DP_PER_INCH

        private const val POINTS_PER_INCH = 72f
        private const val MAX_PAGES = 5000

        /** Two to the point: a hairline survives, and a page is still under 2 MP. */
        private const val LAYOUT_PIXELS_PER_POINT = 2f
        private const val FIGURE_PIXELS_PER_POINT = 4f
        private const val FIGURE_LONGEST_PX = 2048f
        private const val FURNITURE_SAMPLE_PAGES = 24
        private const val SIDE_MARGIN_SHARE = 0.12f
    }
}
