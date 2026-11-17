package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.model.ink.withVertex
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CurveRenderTest {
    @Test
    fun stretchedCurvesRenderRoundedSides() = runDesktopComposeUiTest(width = 500, height = 520) {
        var id = 0
        fun curve(left: Float, top: Float, right: Float, bottom: Float) = Outline.Shape(
            id = "curve-${id++}", kind = ShapeKind.Curve, borderWidth = 3f,
            borderArgb = 0xFF182B49.toInt(), borderFollowsTheme = false,
            segments = seedSegments(ShapeKind.Curve, left, top, right, bottom) { "s-${id++}" },
        ).withRecomputedBounds()
        val original = curve(40f, 40f, 160f, 100f)
        val wide = curve(290f, 40f, 350f, 100f)
            .withVertex(0, 220f, 100f).withVertex(2, 420f, 100f)
        val tall = curve(40f, 220f, 160f, 280f)
            .withVertex(0, 40f, 440f).withVertex(2, 160f, 440f)
        setContent {
            Canvas(Modifier.size(460.dp, 480.dp).testTag("curve-gallery")) {
                drawRect(Color.White)
                listOf(original, wide, tall).forEach { drawDocumentShape(it, originX = 0f, originY = 0f) }
            }
        }
        val captured = onNodeWithTag("curve-gallery").captureToImage()
        val directory = File("build/test-artifacts").apply { mkdirs() }
        Image.makeFromBitmap(captured.asSkiaBitmap()).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { File(directory, "stretched-curves.png").writeBytes(it.bytes) }
        }
        val pixels = captured.toPixelMap()
        // Android's probes sit above the triangle chords where fixed handles flattened the sides.
        fun assertInk(x: Int, y: Int) = assertTrue(pixels[x, y].red < .4f,
            "Curve is missing its rounded side at ($x, $y)")
        assertInk(270, 55)
        assertInk(370, 55)
        assertInk(70, 275)
        assertInk(130, 275)
    }
}
