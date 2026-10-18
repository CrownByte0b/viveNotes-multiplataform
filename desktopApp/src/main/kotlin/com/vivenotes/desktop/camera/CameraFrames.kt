package com.vivenotes.desktop.camera

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/** Raw YUYV — 4:2:2, two pixels in four bytes, Y0 U Y1 V — as most webcams send it uncompressed. */
internal object Yuyv {

    /**
     * [width] by [height] of YUYV as RGBA at [outWidth] by [outHeight], each output pixel the nearest
     * source one. BT.601 limited range, which is what webcams send: Y from 16, chroma around 128.
     */
    fun toRgba(
        yuyv: ByteArray,
        width: Int,
        height: Int,
        bytesPerLine: Int,
        outWidth: Int = width,
        outHeight: Int = height,
    ): ByteArray {
        val out = ByteArray(outWidth * outHeight * 4)
        var o = 0
        for (oy in 0 until outHeight) {
            val row = (oy.toLong() * height / outHeight).toInt() * bytesPerLine
            for (ox in 0 until outWidth) {
                val x = (ox.toLong() * width / outWidth).toInt()
                val pair = row + (x and 1.inv()) * 2
                if (pair + 3 >= yuyv.size) {
                    // A short frame: what is missing stays black rather than reading past the end.
                    out[o + 3] = -1
                    o += 4
                    continue
                }
                val c = (yuyv[pair + (x and 1) * 2].toInt() and 0xFF) - 16
                val d = (yuyv[pair + 1].toInt() and 0xFF) - 128
                val e = (yuyv[pair + 3].toInt() and 0xFF) - 128
                out[o] = clamp((298 * c + 409 * e + 128) shr 8)
                out[o + 1] = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
                out[o + 2] = clamp((298 * c + 516 * d + 128) shr 8)
                out[o + 3] = -1
                o += 4
            }
        }
        return out
    }

    private fun clamp(value: Int): Byte = value.coerceIn(0, 255).toByte()
}

/** [rgba] as a Skia image, for drawing or encoding, closed by the caller. */
internal fun rgbaImage(rgba: ByteArray, width: Int, height: Int): Image =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), rgba, width * 4)

/** The largest size within [maxWidth] by [maxHeight] at [width] by [height]'s shape, never enlarged. */
internal fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/**
 * The bitmaps the preview is drawn into, reused in turn.
 *
 * A new bitmap per frame would be thirty a second of native memory that only the garbage collector
 * frees, and it has no reason to run often. Compose copies a bitmap's pixels each time it draws one,
 * so writing the frame after next into the slot shown now is safe: by then the window shows a newer
 * slot. The bitmaps are never closed — the last frame may still be drawn after the stream ends —
 * and a few of them are all there ever are.
 */
internal class PreviewRing(private val slots: Int = 3) {
    private var bitmaps: List<Bitmap> = emptyList()
    private var next = 0

    /** The next slot at [width] by [height], drawn by [draw]; the same size until the stream changes. */
    fun draw(width: Int, height: Int, draw: (Canvas) -> Unit): ImageBitmap {
        if (bitmaps.firstOrNull()?.let { it.width != width || it.height != height } != false) {
            bitmaps = List(slots) {
                Bitmap().apply { allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)) }
            }
            next = 0
        }
        val bitmap = bitmaps[next]
        next = (next + 1) % slots
        Canvas(bitmap).use(draw)
        bitmap.notifyPixelsChanged()
        return bitmap.asComposeImageBitmap()
    }
}

/**
 * [frame] as the dialog shows it, drawn into [ring] at most [maxWidth] by [maxHeight]; null for a
 * frame that does not decode, which a camera sends now and then as it starts.
 */
internal fun preview(frame: RawFrame, ring: PreviewRing, maxWidth: Int, maxHeight: Int): ImageBitmap? {
    val (width, height) = fitWithin(frame.width, frame.height, maxWidth, maxHeight)
    return if (frame.format.compressed) {
        val image = runCatching { Image.makeFromEncoded(frame.data) }.getOrNull() ?: return null
        image.use { source ->
            // The picture's own size, not the one asked for: a JPEG frame says what it is.
            if (source.width <= 0 || source.height <= 0) return null
            val (w, h) = fitWithin(source.width, source.height, maxWidth, maxHeight)
            ring.draw(w, h) { canvas ->
                canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
            }
        }
    } else {
        val rgba = Yuyv.toRgba(frame.data, frame.width, frame.height, frame.bytesPerLine, width, height)
        rgbaImage(rgba, width, height).use { source -> ring.draw(width, height) { it.drawImage(source, 0f, 0f) } }
    }
}

/**
 * [frame] as the photo: a JPEG frame is already one, and a raw one is converted at full size and
 * encoded. The attachment store re-encodes either, so this only has to be a picture it can read.
 */
internal fun photo(frame: RawFrame): ByteArray? =
    if (frame.format.compressed) {
        frame.data.takeIf { runCatching { Image.makeFromEncoded(it).use { image -> image.width > 0 } }.getOrDefault(false) }
    } else {
        val rgba = Yuyv.toRgba(frame.data, frame.width, frame.height, frame.bytesPerLine)
        rgbaImage(rgba, frame.width, frame.height).use { image ->
            image.encodeToData(EncodedImageFormat.JPEG, PHOTO_QUALITY)?.use { it.bytes }
        }
    }

private const val PHOTO_QUALITY = 95
