package com.vivenotes.data

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import kotlin.math.roundToInt

/** A parsed SVG drawn as vectors. The attachment retains the original file bytes. */
class SvgPicture private constructor(private val dom: SVGDOM, private val source: SvgPictureSource) : AutoCloseable {
    val width: Int get() = source.width
    val height: Int get() = source.height

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        if (width <= 0 || height <= 0) return
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(Rect.makeXYWH(left, top, width, height))
            canvas.translate(left, top)
            canvas.scale(width / source.viewportWidth, height / source.viewportHeight)
            dom.render(canvas)
        } finally { canvas.restoreToCount(checkpoint) }
    }

    fun painter(): Painter = object : Painter() {
        override val intrinsicSize = Size(width.toFloat(), height.toFloat())
        override fun DrawScope.onDraw() {
            drawIntoCanvas { draw(it.skiaCanvas, 0f, 0f, size.width, size.height) }
        }
    }

    /** Temporary pixels for OCR or a search preview; never replaces the stored SVG. */
    fun image(maxDimension: Int): Image {
        val scale = maxDimension.coerceIn(1, AttachmentStore.MAX_DIMENSION).toFloat() / maxOf(width, height)
        return Surface.makeRasterN32Premul(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        ).use { surface ->
            surface.canvas.clear(Color.TRANSPARENT)
            draw(surface.canvas, 0f, 0f, surface.width.toFloat(), surface.height.toFloat())
            surface.makeImageSnapshot()
        }
    }

    override fun close() = dom.close()

    companion object {
        fun read(bytes: ByteArray): SvgPicture? = runCatching {
            val source = SvgPictureSource.read(bytes, AttachmentStore.MAX_DIMENSION) ?: return null
            val dom = Data.makeFromBytes(source.text.encodeToByteArray()).use { SVGDOM(it) }
            try {
                val root = requireNotNull(dom.root)
                root.width = SVGLength(source.viewportWidth)
                root.height = SVGLength(source.viewportHeight)
                dom.setContainerSize(source.viewportWidth, source.viewportHeight)
                SvgPicture(dom, source)
            } catch (failure: Exception) {
                dom.close()
                throw failure
            }
        }.getOrNull()
    }
}

/** Raster or SVG pixels for services that require a bitmap, such as picture OCR or search previews. */
internal fun decodePictureImage(bytes: ByteArray, maxDimension: Int = AttachmentStore.MAX_DIMENSION): Image? =
    runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
        ?: SvgPicture.read(bytes)?.use { it.image(maxDimension) }
