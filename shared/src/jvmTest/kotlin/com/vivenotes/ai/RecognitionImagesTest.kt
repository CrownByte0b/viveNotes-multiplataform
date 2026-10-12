package com.vivenotes.ai

import com.vivenotes.model.ocr.TextDetection
import kotlin.test.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.skia.Matrix33

/**
 * The Skia stand-ins for Android's `Bitmap` calls. Recognition reads exactly what these return, so
 * a wrong channel order or a misplaced warp would read as a worse model rather than as an error.
 */
class RecognitionImagesTest {
    @Test
    fun pixelsReadBackAsUnpremultipliedArgbRowByRow() {
        val image = imageOf(3, 2) { x, y -> if (x == 2 && y == 1) 0x80123456.toInt() else 0xFF000000.toInt() or (x shl 16) or (y shl 8) }

        val pixels = image.argbPixels()

        assertEquals(6, pixels.size)
        assertEquals(0xFF020000.toInt(), pixels[2])
        assertEquals(0xFF000100.toInt(), pixels[3])
        // Half-transparent pixels keep their own colour, as Android's `getPixels` reports them.
        val translucent = pixels[5]
        assertEquals(0x80, translucent ushr 24)
        assertTrue(kotlin.math.abs(red(translucent) - 0x12) <= 1)
        assertTrue(kotlin.math.abs(green(translucent) - 0x34) <= 1)
        assertTrue(kotlin.math.abs(blue(translucent) - 0x56) <= 1)
    }

    @Test
    fun scalingToTheSameSizeIsTheImageItselfAndOtherwiseBlendsBilinearly() {
        val image = imageOf(2, 1) { x, _ -> if (x == 0) BLACK else WHITE }

        assertSame(image, image.scaledTo(2, 1))
        val wide = image.scaledTo(8, 1).argbPixels()
        assertEquals(BLACK, wide.first())
        assertEquals(WHITE, wide.last())
        // A blend in between rather than a hard nearest-neighbour step.
        assertTrue(wide.any { red(it) in 40..215 })
    }

    @Test
    fun croppingCopiesTheRegionPixelForPixel() {
        val image = imageOf(10, 10) { x, y -> if (x == 4 && y == 6) BLACK else WHITE }

        val pixels = image.cropped(4, 6, 2, 2).argbPixels()

        assertEquals(listOf(BLACK, WHITE, WHITE, WHITE), pixels.toList())
    }

    @Test
    fun theProjectiveTransformCarriesEachCornerOntoItsTarget() {
        val from = floatArrayOf(10f, 20f, 110f, 30f, 105f, 70f, 12f, 60f)
        val to = floatArrayOf(0f, 0f, 200f, 0f, 200f, 48f, 0f, 48f)

        val matrix = assertNotNull(projectiveTransform(from, to))

        for (corner in 0 until 4) {
            val (x, y) = apply(matrix, from[corner * 2], from[corner * 2 + 1])
            assertEquals(to[corner * 2], x, 1e-3f)
            assertEquals(to[corner * 2 + 1], y, 1e-3f)
        }
    }

    @Test
    fun collapsedCornersHaveNoTransform() {
        val from = floatArrayOf(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)
        val to = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

        assertNull(projectiveTransform(from, to))
    }

    /** A detected line becomes a 48-pixel strip, its ink kept and the area past the picture paper-white. */
    @Test
    fun aQuadIsWarpedToAStripOfTheRecognizersHeight() {
        val image = imageOf(200, 40) { x, y -> if (x in 20..180 && y in 10..30) BLACK else WHITE }
        val quad = TextDetection.Quad(
            corners = listOf(
                TextDetection.Point(10f, 5f),
                TextDetection.Point(190f, 5f),
                TextDetection.Point(190f, 35f),
                TextDetection.Point(10f, 35f),
            ),
            score = 1f,
        )

        val strip = assertNotNull(cropQuad(image, quad))

        assertEquals(CROP_HEIGHT, strip.height)
        assertEquals(288, strip.width) // 48 * 180 / 30
        val pixels = strip.argbPixels()
        assertEquals(BLACK, pixels[24 * strip.width + strip.width / 2])
        assertEquals(WHITE, pixels[1 * strip.width + 2])
    }

    @Test
    fun aQuadTooThinToReadIsDropped() {
        val image = imageOf(20, 20) { _, _ -> WHITE }
        val sliver = TextDetection.Quad(
            listOf(TextDetection.Point(0f, 0f), TextDetection.Point(1f, 0f), TextDetection.Point(1f, 0.5f), TextDetection.Point(0f, 0.5f)),
            score = 1f,
        )

        assertNull(cropQuad(image, sliver))
    }

    private fun apply(matrix: Matrix33, x: Float, y: Float): Pair<Float, Float> {
        val m = matrix.mat
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }
}
