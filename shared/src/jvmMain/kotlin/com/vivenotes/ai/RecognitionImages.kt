package com.vivenotes.ai

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.abs

/*
 * The handful of Android `Bitmap` operations recognition is written against, in Skia — the library
 * Android's own bitmaps are drawn with, so a scaled crop here samples as it does on a tablet.
 */

/** Draws into a new transparent [width]×[height] raster and returns what was drawn. */
internal inline fun raster(width: Int, height: Int, draw: (Canvas) -> Unit): Image =
    Surface.makeRasterN32Premul(width, height).use { surface ->
        draw(surface.canvas)
        surface.makeImageSnapshot()
    }

/**
 * Android's `Bitmap.createScaledBitmap(this, width, height, filter = true)`: one bilinear pass
 * without mipmaps, which is what the filter flag asks Skia for there. Like Android, it returns the
 * image itself when the size already matches.
 */
internal fun Image.scaledTo(width: Int, height: Int): Image {
    if (width == this.width && height == this.height) return this
    val source = this
    return raster(width, height) { canvas ->
        canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
    }
}

/** Android's `Bitmap.createBitmap(this, left, top, width, height)`: the region, pixel for pixel. */
internal fun Image.cropped(left: Int, top: Int, width: Int, height: Int): Image {
    val source = this
    return raster(width, height) { canvas ->
        canvas.drawImageRect(
            source,
            Rect.makeXYWH(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            SamplingMode.DEFAULT,
            null,
            true,
        )
    }
}

/**
 * Every pixel as unpremultiplied sRGB ARGB, row by row: what Android's `Bitmap.getPixels` returns,
 * so the preprocessing below reads channels exactly as Android's does.
 */
internal fun Image.argbPixels(): IntArray {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
    Bitmap().use { bitmap ->
        check(bitmap.allocPixels(info)) { "Cannot allocate a ${width}×$height raster" }
        check(readPixels(bitmap, 0, 0)) { "Cannot read the picture's pixels" }
        val bytes = bitmap.readPixels(info, width * 4, 0, 0) ?: error("Cannot read the picture's pixels")
        return IntArray(width * height) { index ->
            val offset = index * 4
            ((bytes[offset + 3].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                (bytes[offset].toInt() and 0xFF)
        }
    }
}

internal fun red(argb: Int): Int = (argb ushr 16) and 0xFF
internal fun green(argb: Int): Int = (argb ushr 8) and 0xFF
internal fun blue(argb: Int): Int = argb and 0xFF

/**
 * Draws [image] through the projective transform taking its four [from] corners onto [to]'s, as
 * Android's `Matrix.setPolyToPoly(…, 4)` followed by `drawBitmap(image, matrix, filtered paint)`.
 * False when the corners are degenerate and no such transform exists.
 */
internal fun Canvas.drawProjected(image: Image, from: FloatArray, to: FloatArray): Boolean {
    val transform = projectiveTransform(from, to) ?: return false
    save()
    concat(transform)
    drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        SamplingMode.LINEAR,
        Paint().apply { isAntiAlias = true },
        true,
    )
    restore()
    return true
}

/**
 * The homography mapping four source points onto four destination points, by solving its eight
 * unknowns directly: `x' = (a·x + b·y + c) / (g·x + h·y + 1)`, and the same for `y'`.
 */
internal fun projectiveTransform(from: FloatArray, to: FloatArray): Matrix33? {
    require(from.size == 8 && to.size == 8) { "Four points each" }
    val rows = Array(8) { DoubleArray(9) }
    for (point in 0 until 4) {
        val x = from[point * 2].toDouble()
        val y = from[point * 2 + 1].toDouble()
        val u = to[point * 2].toDouble()
        val v = to[point * 2 + 1].toDouble()
        rows[point * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
        rows[point * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
    }
    for (column in 0 until 8) {
        val pivot = (column until 8).maxBy { abs(rows[it][column]) }
        if (abs(rows[pivot][column]) < SINGULAR) return null
        rows[column] = rows[pivot].also { rows[pivot] = rows[column] }
        val lead = rows[column][column]
        for (entry in column..8) rows[column][entry] /= lead
        for (row in 0 until 8) {
            if (row == column) continue
            val factor = rows[row][column]
            if (factor == 0.0) continue
            for (entry in column..8) rows[row][entry] -= factor * rows[column][entry]
        }
    }
    val h = DoubleArray(8) { rows[it][8] }
    if (!h.all(Double::isFinite)) return null
    return Matrix33(
        h[0].toFloat(), h[1].toFloat(), h[2].toFloat(),
        h[3].toFloat(), h[4].toFloat(), h[5].toFloat(),
        h[6].toFloat(), h[7].toFloat(), 1f,
    )
}

private const val SINGULAR = 1e-9
