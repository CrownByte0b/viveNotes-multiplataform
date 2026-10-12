package com.vivenotes.ai

import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint

/*
 * Pictures for recognition tests: what Android's instrumented tests build with `Bitmap.setPixel` and
 * `Canvas.drawText`, as Skia images.
 */

/** A [width]×[height] image whose pixel at (x, y) is [argb]`(x, y)`. */
internal fun imageOf(width: Int, height: Int, argb: (x: Int, y: Int) -> Int): Image {
    val bytes = ByteArray(width * height * 4)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = argb(x, y)
            val offset = (y * width + x) * 4
            bytes[offset] = (color and 0xFF).toByte()
            bytes[offset + 1] = ((color ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((color ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((color ushr 24) and 0xFF).toByte()
        }
    }
    return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
}

/** Black text on white: each line drawn at its own baseline, as Android's fixtures draw them. */
internal fun textImage(width: Int, height: Int, textSize: Float, lines: List<Triple<String, Float, Float>>): Image {
    val typeface = FontMgr.default.matchFamilyStyle("sans-serif", FontStyle.NORMAL)
        ?: FontMgr.default.legacyMakeTypeface("", FontStyle.NORMAL)
    val font = Font(typeface, textSize)
    val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
    }
    return raster(width, height) { canvas ->
        canvas.clear(Color.WHITE)
        lines.forEach { (text, x, baseline) -> canvas.drawString(text, x, baseline, font, paint) }
    }
}

internal const val BLACK = 0xFF000000.toInt()
internal const val WHITE = 0xFFFFFFFF.toInt()
