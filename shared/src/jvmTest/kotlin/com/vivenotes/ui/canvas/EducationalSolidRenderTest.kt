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
import com.vivenotes.model.ink.*
import com.vivenotes.ui.ribbon.draw.drawToolkitTracing
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.*

/** Android's gallery assertions, through both desktop preview and stored-shape drawing. */
@OptIn(ExperimentalTestApi::class)
class EducationalSolidRenderTest {
    @Test
    fun previewsAndStoredSolidsLeaveTheBoreOpenOverColoredPaper() = runDesktopComposeUiTest(width = 600, height = 460) {
        val paper = Color(0xFFE7D9B4)
        val fill = Color(0xFF3584E4)
        val gallery = listOf(Triple(ShapeKind.HollowCylinder, 20f, 20f),
            Triple(ShapeKind.Hemisphere, 300f, 20f), Triple(ShapeKind.Frustum, 20f, 230f))
        val mode = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            Canvas(Modifier.size(580.dp, 430.dp).testTag("solid-gallery")) {
                drawRect(paper)
                gallery.forEach { (kind, x, y) ->
                    if (mode.value) drawToolkitTracing(trace(kind, x, y, x + 240f, y + 180f), Color.Black, 2f, LineType.Solid, fill)
                    else drawDocumentShape(Outline.Shape(id = kind.name, kind = kind,
                        segments = seedSegments(kind, x, y, x + 240f, y + 180f) { "edge" },
                        fillArgb = 0xFF3584E4.toInt()), originX = 0f, originY = 0f)
                }
            }
        }
        for (tracing in listOf(true, false)) {
            runOnIdle { mode.value = tracing }
            val captured = onNodeWithTag("solid-gallery").captureToImage()
            val pixels = captured.toPixelMap()
            assertEquals(paper, pixels[140, 54], "tube bore is filled")
            assertEquals(fill, pixels[245, 54], "tube annulus is missing")
            assertEquals(fill, pixels[420, 60], "hemisphere dome is missing")
            assertEquals(fill, pixels[140, 330], "frustum body is missing")
            val directory = File("build/test-artifacts").apply { mkdirs() }
            Image.makeFromBitmap(captured.asSkiaBitmap()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    File(directory, "educational-solids-${if (tracing) "preview" else "canvas"}.png").writeBytes(it.bytes)
                }
            }
        }
    }
}
