package com.vivenotes.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's instrumented `RecognitionPreprocessTest`, on the desktop's Skia images: the tensors each
 * model is fed. A mistake here is silent — the model still answers, only worse.
 */
class RecognitionPreprocessTest {
    @Test
    fun textInputUsesPaddleShapeAndBgrNormalization() {
        val image = imageOf(2, 1) { x, _ -> if (x == 0) BLACK else WHITE }

        val tensor = preprocessText(image)

        assertEquals(320, tensor.width)
        assertEquals(3 * 48 * 320, tensor.values.size)
        assertTrue(tensor.values.first() <= -0.99f)
    }

    /**
     * A padded line keeps its rows aligned — the defect described on `preprocessText`.
     *
     * A vertical black bar down the left of the crop must stay vertical in the tensor. Walking the
     * pixels with one running index shifts each row by `width - resizedWidth`, so by row 48 the bar
     * has walked thousands of pixels to the right and the line is unreadable.
     */
    @Test
    fun aPaddedLineIsNotShearedRowByRow() {
        // 96 x 48 is 2:1, well under the 320-wide tensor, so every row is padded.
        val image = imageOf(96, 48) { x, _ -> if (x < 8) BLACK else WHITE }

        val tensor = preprocessText(image)

        assertEquals(320, tensor.width)
        for (y in 0 until 48) {
            val row = y * tensor.width
            assertTrue("row $y lost the bar at its left edge", tensor.values[row] <= -0.99f)
            // The bar is eight pixels wide and nothing else is dark, so column 40 is paper on every
            // row. Under the shear it was ink on most of them.
            assertTrue("row $y has ink where the page should be", tensor.values[row + 40] >= 0.99f)
            // Everything past the picture is padding, and padding is zero.
            assertEquals(0f, tensor.values[row + 200], 1e-6f)
        }
    }

    @Test
    fun formulaInputIsCroppedAndPaddedTo384Square() {
        val image = rectangleOnPaper()

        val tensor = preprocessFormula(image)

        assertEquals(384 * 384, tensor.size)
        assertTrue(tensor.all(Float::isFinite))

        // The square is padded with paper, not with black, and the ink survived the resize.
        val paper = (1f - 0.7931f) / 0.1738f
        assertEquals(paper, tensor.first(), 1e-4f)
        assertEquals(paper, tensor.last(), 1e-4f)
        assertTrue("no ink survived the crop and resize", tensor.min() < paper - 1f)

        // A 60x20 rectangle is 3:1, so it fits to 384x128 and is centred: the middle row crosses
        // both of its uprights, and the rows a third of the way down the frame are still padding.
        val middleRow = tensor.asList().subList(192 * 384, 193 * 384)
        assertTrue("the crop did not land in the middle of the frame", middleRow.min() < paper - 1f)
        assertEquals(paper, tensor[20 * 384 + 192], 1e-4f)
    }

    /**
     * UniMERNet-T's frame: the same crop, scaled to fit 192×672, centred on **black**.
     *
     * The 60×20 rectangle is 3:1, so the height binds. It scales by 9.6 to 576×192 and sits 48
     * columns in from either side. The padding is black and the rectangle's inside is still paper,
     * which is the one way this differs from FormulaNet's frame.
     */
    @Test
    fun uniMerNetInputIsCroppedScaledAndPaddedBlack() {
        val image = rectangleOnPaper()

        val tensor = preprocessUniMerNet(image)

        assertEquals(192 * 672, tensor.size)
        assertTrue(tensor.all(Float::isFinite))
        val black = (0f - 0.7931f) / 0.1738f
        val paper = (1f - 0.7931f) / 0.1738f
        val middle = 96 * 672
        assertEquals("corner is padding", black, tensor.first(), 1e-4f)
        assertEquals("left of the crop is padding", black, tensor[middle + 20], 1e-4f)
        assertEquals("right of the crop is padding", black, tensor[middle + 650], 1e-4f)
        assertEquals("inside the rectangle is paper", paper, tensor[middle + 336], 1e-4f)
        assertTrue("the left upright did not survive", tensor[middle + 50] < paper - 1f)
    }

    /** Detection's frame: side-limited, a multiple of 32, ImageNet-normalized in RGB order. */
    @Test
    fun detectionInputIsAlignedTo32AndImageNetNormalized() {
        val image = imageOf(1920, 1000) { _, _ -> 0xFFFF0000.toInt() }

        val tensor = preprocessDetection(image)

        assertEquals(960, tensor.width)
        assertEquals(512, tensor.height)
        assertEquals(3 * 960 * 512, tensor.values.size)
        val plane = tensor.width * tensor.height
        assertEquals((1f - 0.485f) / 0.229f, tensor.values[0], 1e-4f)
        assertEquals((0f - 0.456f) / 0.224f, tensor.values[plane], 1e-4f)
        assertEquals((0f - 0.406f) / 0.225f, tensor.values[plane * 2], 1e-4f)
    }

    /** A 120×60 page with a 60×20 rectangle outlined in black, as Android's two tests draw it. */
    private fun rectangleOnPaper() = imageOf(120, 60) { x, y ->
        val onHorizontal = x in 30 until 90 && (y == 20 || y == 39)
        val onVertical = y in 20 until 40 && (x == 30 || x == 89)
        if (onHorizontal || onVertical) BLACK else WHITE
    }
}
