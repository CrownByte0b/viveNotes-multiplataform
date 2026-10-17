package com.vivenotes.ai

import com.vivenotes.localization.Strings
import com.vivenotes.model.ocr.TextDetection
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One line read out of a picture, with where it was and how sure the recognizer was. */
data class ImageTextLine(
    val text: String,
    val confidence: Float,
    /** Corners in source-picture pixels, clockwise from the top-left. */
    val corners: List<TextDetection.Point>,
)

/** Everything read out of one picture. */
data class ImageTextResult(
    val lines: List<ImageTextLine>,
    val meanConfidence: Float,
) {
    val text: String get() = lines.joinToString("\n") { it.text }

    companion object {
        val Empty = ImageTextResult(emptyList(), 0f)
    }
}

/** The detector's input tensor and the size it was built at, which the boxes come back in. */
internal data class DetectionTensor(val values: FloatArray, val width: Int, val height: Int)

internal enum class DetectionChannelOrder { Rgb, Bgr }

/**
 * PaddleOCR v5/v6 detection preprocessing: fit a side limit, round to a multiple of 32, ImageNet-normalize.
 * Android's `ai/ImageOcr.kt`.
 * [channelOrder] keeps Android's original RGB projection by default; the v6 Small adapter
 * explicitly selects the BGR order from its official export configuration.
 *
 * The multiple of 32 is the network's own requirement — it downsamples by 32 and upsamples back —
 * and `limit_side_len` is what keeps a 12-megapixel photograph from being fed in whole. 960 is
 * PaddleOCR's default and was kept: 640 was equally accurate and a third faster over the Android
 * repository's `simulations/image-ocr/`, but every sample in that corpus is comfortably large type,
 * so the corpus cannot see the case 960 exists for.
 */
internal fun preprocessDetection(
    image: Image,
    limit: Int = DETECTION_LIMIT,
    channelOrder: DetectionChannelOrder = DetectionChannelOrder.Rgb,
): DetectionTensor {
    require(image.width > 0 && image.height > 0) { Strings.detection_image_is_empty }
    val longest = max(image.width, image.height)
    val ratio = if (longest > limit) limit.toFloat() / longest else 1f
    val width = alignTo32(image.width * ratio)
    val height = alignTo32(image.height * ratio)
    val resized = image.scaledTo(width, height)
    val pixels = try {
        resized.argbPixels()
    } finally {
        if (resized !== image) resized.close()
    }

    val plane = width * height
    val values = FloatArray(plane * 3)
    val bgr = channelOrder == DetectionChannelOrder.Bgr
    pixels.forEachIndexed { index, color ->
        val first = if (bgr) blue(color) else red(color)
        val third = if (bgr) red(color) else blue(color)
        values[index] = (first / 255f - DETECTION_MEAN_0) / DETECTION_STD_0
        values[plane + index] = (green(color) / 255f - DETECTION_MEAN_1) / DETECTION_STD_1
        values[plane * 2 + index] = (third / 255f - DETECTION_MEAN_2) / DETECTION_STD_2
    }
    return DetectionTensor(values, width, height)
}

/** The Small detector's inference.yml settings, leaving Android's DB defaults unchanged. */
internal fun ppOcrV6SmallQuads(probability: FloatArray, width: Int, height: Int): List<TextDetection.Quad> =
    TextDetection.quads(probability, width, height, threshold = 0.2f, boxThreshold = 0.45f, unclipRatio = 1.4f)

/**
 * Warps one detected quad to a horizontal strip 48 pixels tall — what the recognizer eats.
 *
 * A four-point projective transform is what a photographed page needs: its lines are not merely
 * rotated, they are foreshortened.
 *
 * The quad's long side becomes the strip's width, so a line written down the page is read along its
 * own axis. Which end of that axis comes first cannot be known without an orientation classifier,
 * so it is fixed rather than guessed: the corner after the top-left leads.
 */
internal fun cropQuad(image: Image, quad: TextDetection.Quad, height: Int = CROP_HEIGHT): Image? {
    val longSide = max(quad.width, quad.height)
    val shortSide = min(quad.width, quad.height)
    if (longSide < 2f || shortSide < 1f) return null
    val width = (height * longSide / shortSide).roundToInt().coerceIn(MIN_CROP_WIDTH, MAX_CROP_WIDTH)

    // Rotate the source corners so the long side is the one that maps to the strip's width.
    val ordered = if (quad.width >= quad.height) quad.corners else {
        listOf(quad.corners[1], quad.corners[2], quad.corners[3], quad.corners[0])
    }
    val source = FloatArray(8)
    ordered.forEachIndexed { index, point ->
        source[index * 2] = point.x
        source[index * 2 + 1] = point.y
    }
    val destination = floatArrayOf(
        0f, 0f,
        width.toFloat(), 0f,
        width.toFloat(), height.toFloat(),
        0f, height.toFloat(),
    )
    var projected = false
    val strip = raster(width, height) { canvas ->
        // Paper-coloured, so a quad that reaches past the edge of the picture is padded with
        // something the recognizer reads as background rather than as a black bar down the line.
        canvas.clear(Color.WHITE)
        projected = canvas.drawProjected(image, source, destination)
    }
    if (!projected) {
        strip.close()
        return null
    }
    return strip
}

/**
 * Whether a reading is worth storing at all.
 *
 * A bullet glyph, a rule or a stray tick is a real text region and recognizes as one or two marks.
 * It is a true reading and it is useless to search, so it is dropped rather than kept.
 */
internal fun isSearchableReading(text: String): Boolean = text.any { it.isLetterOrDigit() }

private fun alignTo32(value: Float): Int =
    max(32, (value / 32f).roundToInt() * 32)

/** PaddleOCR's `limit_side_len` for detection. See [preprocessDetection]. */
internal const val DETECTION_LIMIT = 960

/** The recognizer's fixed input height, shared with `preprocessText`. */
internal const val CROP_HEIGHT = 48

private const val MIN_CROP_WIDTH = 8
private const val MAX_CROP_WIDTH = 3200

// ImageNet statistics, which is what the detector was trained with — not the [-1, 1] the recognizer
// uses. Feeding one model the other's normalization is silent and produces an empty page.
private const val DETECTION_MEAN_0 = 0.485f
private const val DETECTION_MEAN_1 = 0.456f
private const val DETECTION_MEAN_2 = 0.406f
private const val DETECTION_STD_0 = 0.229f
private const val DETECTION_STD_1 = 0.224f
private const val DETECTION_STD_2 = 0.225f
