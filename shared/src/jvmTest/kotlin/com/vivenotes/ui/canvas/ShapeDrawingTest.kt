package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShapeDrawingTest {
    @Test
    fun filledShapeAndAutomaticBorderRenderFromStoredSegments() =
        runDesktopComposeUiTest(width = 180, height = 140) {
            val shape = Outline.Shape(id = "rectangle", kind = ShapeKind.Rectangle,
                segments = seedSegments(ShapeKind.Rectangle, 10f, 10f, 110f, 90f) { "edge" },
                borderArgb = Color.Black.toArgb(), borderFollowsTheme = true,
                borderWidth = 4f, fillArgb = Color.Red.toArgb()).withRecomputedBounds()
            setContent {
                Canvas(Modifier.size(120.dp, 100.dp).testTag("rendered-shape")) {
                    drawDocumentShape(shape, originX = 0f, originY = 0f, canvasInk = Color.White)
                }
            }
            val pixels = onNodeWithTag("rendered-shape").captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[60, 50])
            val border = pixels[10, 50]
            assertTrue(border.red > 0.8f && border.green > 0.8f && border.blue > 0.8f)
        }
}
