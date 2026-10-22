package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.PortedLogs
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkViewportInteractionTest {
    @Test fun panZoomReturnsKeepExactPixelsAndRecolorAndPageExitDropOlderViews() =
        runDesktopComposeUiTest(width = 256, height = 256) {
            val density = 1.75f
            val views = listOf(1.25f to Offset(17.25f, 29.75f), 1.25f to Offset(41.25f, 37.75f),
                .75f to Offset(3.75f, 5.25f))
            val index = mutableStateOf(0)
            val color = mutableStateOf(Color.Black)
            val present = mutableStateOf(true)
            val diagnostics = InkLayerDiagnostics()
            val page = page()
            val messages = mutableListOf<String>()
            val priorLog = PortedLogs.sink
            PortedLogs.sink = DebugLog(true, messages::add)
            try {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density),
                        LocalInkLayerDiagnostics provides diagnostics) {
                        val (zoom, scroll) = views[index.value]
                        Viewport(density, zoom, scroll) {
                            if (present.value) InkLayer(page, DpSize(512.dp, 512.dp), color.value,
                                visibleWindow = { Rect(scroll.x / zoom, scroll.y / zoom,
                                    (scroll.x + 256f) / zoom, (scroll.y + 256f) / zoom) }, zoom = zoom)
                        }
                    }
                }
                val frame = onNodeWithTag("viewport-frame")
                fun pixels(): List<Color> = frame.captureToImage().toPixelMap().let { map ->
                    List(map.width * map.height) { map[it % map.width, it / map.width] }
                }
                val images = mutableMapOf<Int, List<Color>>()
                for (value in listOf(0, 1, 0, 2, 1, 2, 0)) {
                    val before = diagnostics.counts()
                    runOnIdle { index.value = value }
                    val image = pixels()
                    if (value in images) {
                        assertEquals(images[value], image)
                        assertEquals(0L, (diagnostics.counts() - before).rasters)
                        assertEquals(0L, (diagnostics.counts() - before).finishedStrokeDraws)
                    } else images[value] = image
                }
                assertEquals(3L, diagnostics.counts().rasters)
                assertEquals(3, diagnostics.viewportRasterWork?.retainedViews)
                assertTrue(assertNotNull(diagnostics.viewportRasterWork).hits >= 4)
                assertTrue(messages.none { "viewport cache released" in it }, "no per-frame logging")
                runOnIdle { color.value = Color.White }
                val recolored = pixels()
                assertNotEquals(images[0], recolored)
                assertEquals(1, diagnostics.viewportRasterWork?.retainedViews)
                runOnIdle { present.value = false }
                pixels()
                assertEquals(0L, diagnostics.retainedPixelBytes)
                assertEquals(0, diagnostics.viewportRasterWork?.retainedViews)
                val summaries = messages.filter { "viewport cache released" in it }
                assertEquals(1, summaries.size)
                assertTrue(summaries.single().contains("fullBuilds=4"))
                assertTrue(summaries.single().contains("allocatedPixels="))
                assertFalse(summaries.single().contains(page.pageId))
            } finally {
                runOnIdle { present.value = false }
                waitForIdle()
                PortedLogs.sink = priorLog
            }
        }

    @Test fun disabledLoggingDoesNotEmitDisposalSummary() = runDesktopComposeUiTest(width = 128, height = 128) {
        val present = mutableStateOf(true)
        val messages = mutableListOf<String>()
        val priorLog = PortedLogs.sink
        PortedLogs.sink = DebugLog(false, messages::add)
        try {
            setContent { Box(Modifier.size(128.dp)) {
                if (present.value) InkLayer(page(), DpSize(128.dp, 128.dp), Color.Black)
            } }
            waitForIdle()
            runOnIdle { present.value = false }
            waitForIdle()
            assertTrue(messages.isEmpty())
        } finally { PortedLogs.sink = priorLog }
    }

    private fun page() = InkPage("private-page-identity", listOf(
        VisibleInkStroke("automatic", "marker", 1, 6f, 0xff000000.toInt(), true,
            listOf(InkSample(20f, 35f), InkSample(180f, 95f))),
        VisibleInkStroke("highlighter", "highlighter", 1, 12f, 0x77ffe000, false,
            listOf(InkSample(12f, 55f), InkSample(190f, 30f))),
        VisibleInkStroke("fixed", "marker", 1, 3f, 0xffff0000.toInt(), false,
            listOf(InkSample(50f, 15f), InkSample(70f, 150f))),
    ))

    @Composable private fun Viewport(density: Float, zoom: Float, scroll: Offset, content: @Composable () -> Unit) {
        Box(Modifier.size((256f / density).dp).clipToBounds().background(Color.White).testTag("viewport-frame")) {
            Layout(content = content) { measurables, _ ->
                val child = measurables.singleOrNull()?.measure(Constraints())
                layout(256, 256) {
                    child?.placeWithLayer(0, 0) {
                        scaleX = zoom; scaleY = zoom
                        translationX = -scroll.x; translationY = -scroll.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    }
}
