// Ported from viveNotes Android fileimport/docx/DocxPages.kt. Skia bitmaps and Skia's decoder
// replace android.graphics.Bitmap and ImageDecoder; the rest is Android's.
package com.vivenotes.fileimport.docx

import com.vivenotes.fileimport.ImportedDocument
import com.vivenotes.fileimport.ImportedPageSize
import com.vivenotes.fileimport.FileImporter
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/** The visible part of a picture, bounded to the attachment store's size, or null if it cannot be decoded. */
internal fun croppedPicture(bytes: ByteArray, crop: WordCrop): ByteArray? {
    val left = crop.left.coerceIn(0f, 1f)
    val top = crop.top.coerceIn(0f, 1f)
    val right = crop.right.coerceIn(0f, 1f)
    val bottom = crop.bottom.coerceIn(0f, 1f)
    if (left + right >= 1f || top + bottom >= 1f) return null
    return runCatching {
        Image.makeFromEncoded(bytes).use { source ->
            val x = (source.width * left).roundToInt().coerceIn(0, source.width - 1)
            val y = (source.height * top).roundToInt().coerceIn(0, source.height - 1)
            val endX = (source.width * (1f - right)).roundToInt().coerceIn(x + 1, source.width)
            val endY = (source.height * (1f - bottom)).roundToInt().coerceIn(y + 1, source.height)
            val scale = minOf(1f, FileImporter.PICTURE_LONGEST_PX.toFloat() / max(endX - x, endY - y))
            val width = ((endX - x) * scale).roundToInt().coerceAtLeast(1)
            val height = ((endY - y) * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeLTRB(x.toFloat(), y.toFloat(), endX.toFloat(), endY.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                surface.makeImageSnapshot().use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)?.use { it.bytes }
                }
            }
        }
    }.getOrNull()
}

/** The pictures inside the document, decoded when a page first needs them. */
internal class DocxMedia(private val read: (String) -> ByteArray?) {
    private val cache = LinkedHashMap<String, Image>(16, 0.75f, true)
    private var cachedBytes = 0L

    /** Parts that would not decode — EMF and WMF, typically — so they are not tried per draw. */
    private val undecodable = HashSet<String>()

    fun bytes(part: String): ByteArray? = runCatching { read(part) }.getOrNull()

    /** [part] at no less than [longestPx] on its long side, if it is that big; null if unreadable. */
    fun bitmap(part: String, longestPx: Int): Image? {
        if (part in undecodable) return null
        val target = longestPx.coerceIn(1, MAX_DECODE_PX)
        cache[part]?.let { cached ->
            if (max(cached.width, cached.height) >= target * ENOUGH) return cached
        }
        val decoded = decode(part, target)
        if (decoded == null) {
            undecodable += part
            return null
        }
        cache.put(part, decoded)?.let { cachedBytes -= it.byteCount() }
        cachedBytes += decoded.byteCount()
        val eldest = cache.entries.iterator()
        while (cachedBytes > MAX_CACHE_BYTES && cache.size > 1 && eldest.hasNext()) {
            val entry = eldest.next()
            if (entry.key == part) continue
            cachedBytes -= entry.value.byteCount()
            eldest.remove()
        }
        return decoded
    }

    private fun Image.byteCount(): Long = width.toLong() * height * 4

    /**
     * Android's `ImageDecoder` with a target size: decoded, and drawn down to [target] on its long
     * side when it is bigger, so what is kept is no larger than a page will draw it.
     */
    private fun decode(part: String, target: Int): Image? {
        val data = bytes(part) ?: return null
        return runCatching {
            Image.makeFromEncoded(data).use { source ->
                // Raster now: an encoded image would decode again at every draw.
                val longest = max(source.width, source.height)
                val scale = if (longest > target) target.toFloat() / longest else 1f
                val width = (source.width * scale).roundToInt().coerceAtLeast(1)
                val height = (source.height * scale).roundToInt().coerceAtLeast(1)
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    surface.canvas.drawImageRect(
                        source,
                        Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                        Rect.makeWH(width.toFloat(), height.toFloat()),
                        SamplingMode.MITCHELL,
                        null,
                        true,
                    )
                    surface.makeImageSnapshot()
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_DECODE_PX = 4096
        const val MAX_CACHE_BYTES = 48L * 1024 * 1024

        /** Close enough to the size asked for that decoding again would not be worth it. */
        const val ENOUGH = 0.75f
    }
}

/**
 * An opened word-processor document, cut into sheets. Used from one coroutine at a time.
 *
 * Every such format arrives here as a [WordDocument], so they share one layout and one set of rules
 * for what becomes an object.
 */
internal class DocxPages private constructor(
    private val source: Closeable,
    private val document: WordDocument,
    private val engine: WordLayoutEngine,
    private val media: DocxMedia,
    val sheets: List<DocxSheet>,
) : ImportedDocument {

    override val pages: List<ImportedPageSize> = sheets.map { ImportedPageSize(it.widthDp, it.heightDp) }

    override fun render(index: Int, widthPx: Int): Bitmap? {
        val sheet = sheets.getOrNull(index) ?: return null
        val scale = widthPx.toFloat() / sheet.widthUnits
        val heightPx = (sheet.heightUnits * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(widthPx.coerceAtLeast(1), heightPx, true)
        val canvas = Canvas(bitmap)
        canvas.clear(WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        engine.state.scale = scale
        drawSheet(canvas, sheet)
        canvas.restore()
        canvas.close()
        bitmap.setImmutable()
        return bitmap
    }

    private fun drawSheet(canvas: Canvas, sheet: DocxSheet) {
        sheet.floats.filter { it.behind }.forEach { drawFloat(canvas, it) }
        sheet.header?.let { drawFlow(canvas, it) }
        sheet.footer?.let { drawFlow(canvas, it) }
        sheet.pieces.forEach { drawPiece(canvas, it) }
        sheet.floats.filterNot { it.behind }.forEach { drawFloat(canvas, it) }
    }

    private fun drawFloat(canvas: Canvas, float: SheetFloat) {
        val count = canvas.save()
        canvas.translate(float.x.toFloat(), float.y.toFloat())
        if (float.rotationDegrees != 0f) canvas.rotate(float.rotationDegrees, float.box.width / 2f, float.box.height / 2f)
        float.box.draw(canvas)
        canvas.restoreToCount(count)
    }

    private fun drawFlow(canvas: Canvas, placed: PlacedFlow) {
        val count = canvas.save()
        canvas.translate(placed.x.toFloat(), placed.y.toFloat())
        placed.flow.draw(canvas)
        canvas.restoreToCount(count)
    }

    private fun drawPiece(canvas: Canvas, piece: SheetPiece) {
        val count = canvas.save()
        canvas.translate(piece.x.toFloat(), piece.y.toFloat())
        val repeat = if (piece.from > 0) piece.block.repeatHeight else 0
        if (repeat > 0) {
            piece.block.drawRepeat(canvas)
            canvas.translate(0f, repeat.toFloat())
        }
        canvas.clipRect(Rect.makeLTRB(-CLIP_MARGIN, 0f, CLIP_MARGIN + 100_000f, (piece.to - piece.from).toFloat()))
        canvas.translate(0f, -piece.from.toFloat())
        piece.block.draw(canvas, piece.from, piece.to)
        canvas.restoreToCount(count)
    }

    /** What the chosen [pages] hold as canvas objects; see [WordObjects]. */
    override suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> {
        val pieces = pages.sorted().flatMap { index -> sheets.getOrNull(index)?.objectPieces().orEmpty() }
        return WordObjects(document, marks, media::bytes, ::croppedPicture).items(pieces)
    }

    override fun close() = source.close()

    companion object {
        /** Reads and lays out [pkg]; takes ownership of it. */
        suspend fun open(pkg: DocxPackage): DocxPages =
            of(DocxReader(pkg).read(), { pkg.bytes(it) }, readFontFamilies(pkg), pkg)

        /**
         * Lays out a [document] read from any format. [parts] finds a picture by the name the document
         * gives it, [fontFamilies] is each font's generic family, lowercased, and [source] is closed
         * along with this.
         */
        suspend fun of(
            document: WordDocument,
            parts: (String) -> ByteArray?,
            fontFamilies: Map<String, String>,
            source: Closeable,
        ): DocxPages {
            val media = DocxMedia(parts)
            val engine = WordLayoutEngine(document, media, fontFamilies)
            return DocxPages(source, document, engine, media, engine.layout())
        }

        /** `fontTable.xml`'s family for each font, used to pick a stand-in for a font we have not met. */
        private fun readFontFamilies(pkg: DocxPackage): Map<String, String> {
            val root = pkg.relationships(pkg.mainPart).values
                .firstOrNull { !it.external && it.type.endsWith("/fontTable") }
                ?.let { pkg.xml(it.target) } ?: return emptyMap()
            return root.children(NS_W, "font").associate { font ->
                font.w("name").orEmpty().lowercase() to font.wVal("family").orEmpty().lowercase()
            }
        }

        private const val CLIP_MARGIN = 20_000f

        /** Android's `Color.WHITE`: a page is drawn on white paper. */
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
