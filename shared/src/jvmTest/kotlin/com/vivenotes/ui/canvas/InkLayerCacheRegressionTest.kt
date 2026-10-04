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
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toByteInk
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import com.vivenotes.workspace.InkTool
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkLayerCacheRegressionTest {
    @Test
    fun heldFinishedBackgroundIsReusedThroughoutWetFrames() = runDesktopComposeUiTest(width = 128, height = 128) {
        val diagnostics = InkLayerDiagnostics()
        setContent {
            CompositionLocalProvider(LocalInkLayerDiagnostics provides diagnostics) {
                Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                    InkLayer(page(), DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen)
                }
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.captureToImage()
        assertEquals(1L, diagnostics.counts().rasters)
        frame.performMouseInput { updatePointerTo(Offset(10f, 80f)); press() }
        val dotPixels = frame.captureToImage().toPixelMap()
        val dot = dotPixels[10, 80]
        val capTolerance = 2f / 255f + 0.000001f
        assertTrue(dot.red <= capTolerance && dot.green <= capTolerance && dot.blue <= capTolerance,
            "initial dot is immediate: $dot")
        assertEquals(Color.White, dotPixels[80, 80])
        val before = diagnostics.counts()
        for (x in listOf(30f, 50f, 70f, 100f)) {
            frame.performMouseInput { moveTo(Offset(x, 80f), delayMillis = 16) }
            frame.captureToImage()
        }
        val duringWet = diagnostics.counts() - before
        assertTrue(duringWet.draws > 0)
        assertTrue(duringWet.shapeAdvances > 0)
        assertEquals(0L, duringWet.scenes)
        assertEquals(0L, duringWet.rasters)
        assertEquals(0L, duringWet.finishedPaths)
        assertEquals(0L, duringWet.finishedStrokeDraws)
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 80])
        frame.performMouseInput { release() }
    }

    @Test
    fun queuedMovesWaitForAFrameAndAllObservationsReachTheCanonicalRow() = runDesktopComposeUiTest(width = 128, height = 128) {
        val diagnostics = InkLayerDiagnostics()
        val edits = mutableListOf<InkEdit>()
        setContent {
            CompositionLocalProvider(LocalInkLayerDiagnostics provides diagnostics) {
                Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                    InkLayer(page(), DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen,
                        onEdit = { edit, _ -> edits += edit })
                }
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput { updatePointerTo(Offset(20f, 80f)); press() }
        frame.captureToImage()
        mainClock.autoAdvance = false
        try {
            val before = diagnostics.counts()
            frame.performMouseInput {
                for (x in listOf(35f, 50f, 65f, 80f, 95f)) moveTo(Offset(x, 80f), delayMillis = 1)
            }
            runOnIdle {
                val queued = diagnostics.counts() - before
                assertEquals(5L, queued.observations)
                assertEquals(0L, queued.shapeAdvances, "enqueue must not process geometry for each event")
            }
            mainClock.advanceTimeByFrame()
            runOnIdle {
                assertEquals(1L, (diagnostics.counts() - before).shapeAdvances,
                    "one processed frame consumes the whole observation burst")
            }
        } finally {
            mainClock.autoAdvance = true
        }
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 80])
        frame.performMouseInput { moveTo(Offset(110f, 80f), delayMillis = 1); release() }
        runOnIdle {
            val row = (edits.single() as InkEdit.AddStroke).row
            val decoded = assertNotNull(ViveInkCodec.decode(row.toByteInk()))
            val expected = listOf(20f, 35f, 50f, 65f, 80f, 95f, 110f)
            assertEquals(expected.size, decoded.inputs.size)
            expected.forEachIndexed { index, x -> assertEquals(x, decoded.inputs[index].x, 0.02f) }
        }
    }

    @Test
    fun eraserPreviewKeepsSceneIndexAndHidesEveryDisconnectedProjectionOfEachRow() = runDesktopComposeUiTest(width = 128, height = 128) {
        val partial = InkPageOperation.Erase("cut", 1, setOf("split"), false, 18f,
            listOf(InkSample(50f, 35f), InkSample(50f, 65f)))
        val initial = InkPage("page", listOf(
            line("split", 50f, 0xffff0000.toInt()), line("other", 90f, 0xff008800.toInt())), listOf(partial))
        assertEquals(2, initial.desktopGeometry().projections.count { it.id == "split" })
        val page = mutableStateOf(initial)
        val edits = mutableListOf<InkEdit>()
        val diagnostics = InkLayerDiagnostics()
        setContent {
            CompositionLocalProvider(LocalInkLayerDiagnostics provides diagnostics) {
                Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Eraser,
                        onEdit = { edit, updated -> edits += edit; page.value = updated })
                }
            }
        }
        val frame = onNodeWithTag(FRAME)
        assertEquals(Color.Red, frame.captureToImage().toPixelMap()[80, 50])
        val sceneBuilds = diagnostics.counts().scenes
        frame.performMouseInput { updatePointerTo(Offset(20f, 50f)); press() }
        assertEquals(Color.White, frame.captureToImage().toPixelMap()[80, 50], "the remote fragment disappears before release")
        assertEquals(sceneBuilds, diagnostics.counts().scenes)
        frame.performMouseInput { moveTo(Offset(20f, 90f), delayMillis = 32) }
        assertEquals(Color.White, frame.captureToImage().toPixelMap()[80, 90])
        runOnIdle {
            assertEquals(sceneBuilds, diagnostics.counts().scenes)
            assertTrue(edits.isEmpty(), "preview does not commit while held")
        }
        frame.performMouseInput { release() }
        runOnIdle {
            assertEquals(setOf("split", "other"), (edits.single() as InkEdit.EraseStrokes).ids)
            assertTrue(page.value.desktopGeometry().projections.isEmpty())
            assertEquals(sceneBuilds, diagnostics.counts().scenes, "committed tombstones reuse the base scene too")
        }
    }

    @Test
    fun fractionalScrollAndZoomKeepAutomaticColorsAndHighlightAlphaAtBothDensities() {
        for (density in listOf(1f, 2f)) {
            runDesktopComposeUiTest(width = 256, height = 256) {
                val dark = mutableStateOf(false)
                val zoom = 1.25f
                val scroll = Offset(17.25f, 29.75f)
                val visible = mutableStateOf(true)
                val viewport = Rect(scroll.x / zoom, scroll.y / zoom,
                    (scroll.x + 256f) / zoom, (scroll.y + 256f) / zoom)
                val initial = InkPage("page", listOf(
                    VisibleInkStroke("fixed", "marker", 1, 6f, 0xffff0000.toInt(), false,
                        listOf(InkSample(20f, 30f), InkSample(80f, 30f))),
                    VisibleInkStroke("automatic", "marker", 1, 6f, 0xff000000.toInt(), true,
                        listOf(InkSample(20f, 55f), InkSample(80f, 55f))),
                    VisibleInkStroke("highlight", "highlighter", 1, 10f, 0x80ffe000.toInt(), false,
                        listOf(InkSample(20f, 80f), InkSample(80f, 80f))),
                ))
                val diagnostics = InkLayerDiagnostics()
                setContent {
                    if (visible.value) {
                        CompositionLocalProvider(LocalDensity provides Density(density),
                            LocalInkLayerDiagnostics provides diagnostics) {
                            Viewport(density, zoom, scroll, if (dark.value) Color.Black else Color.White) {
                                InkLayer(initial, DpSize(256.dp, 256.dp), if (dark.value) Color.White else Color.Black,
                                    visibleWindow = { viewport }, zoom = zoom)
                            }
                        }
                    }
                }
                fun point(yDp: Float) = ((50f * density * zoom - scroll.x).roundToInt()) to
                    ((yDp * density * zoom - scroll.y).roundToInt())
                val frame = onNodeWithTag(FRAME)
                val lightPixels = frame.captureToImage().toPixelMap()
                val fixed = point(30f); val automatic = point(55f); val highlight = point(80f)
                assertEquals(Color.Red, lightPixels[fixed.first, fixed.second])
                assertEquals(Color.Black, lightPixels[automatic.first, automatic.second])
                assertTrue(lightPixels[highlight.first, highlight.second].blue in 0.49f..0.51f)
                val physicalWidth = ceil((viewport.right.toDouble() - viewport.left.toDouble()) * zoom).toLong()
                val physicalHeight = ceil((viewport.bottom.toDouble() - viewport.top.toDouble()) * zoom).toLong()
                assertTrue(physicalWidth in 256L..257L && physicalHeight in 256L..257L)
                val viewportBytes = physicalWidth * physicalHeight * 4L
                assertEquals(viewportBytes, diagnostics.retainedPixelBytes,
                    "raster uses physical viewport resolution at density $density and zoom $zoom")
                val before = diagnostics.counts()
                runOnIdle { dark.value = true }
                val darkPixels = frame.captureToImage().toPixelMap()
                assertEquals(Color.Red, darkPixels[fixed.first, fixed.second])
                assertEquals(Color.White, darkPixels[automatic.first, automatic.second])
                assertTrue(darkPixels[highlight.first, highlight.second].red in 0.49f..0.51f)
                assertEquals(1L, (diagnostics.counts() - before).rasters)
                assertEquals(0L, (diagnostics.counts() - before).finishedPaths, "recolor reuses modeled paths")
                assertEquals(viewportBytes, diagnostics.peakRetainedPixelBytes)
                runOnIdle { visible.value = false }
                runOnIdle {
                    assertEquals(0L, diagnostics.retainedPixelBytes, "leaving composition releases the raster")
                    assertEquals(viewportBytes, diagnostics.peakRetainedPixelBytes)
                }
            }
        }
    }

    @Test
    fun changingPagesCancelsTheUnfinishedGesture() = runDesktopComposeUiTest(width = 128, height = 128) {
        val page = mutableStateOf(page())
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag(FRAME)) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen,
                    onEdit = { edit, _ -> edits += edit })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput { updatePointerTo(Offset(10f, 80f)); press(); moveTo(Offset(100f, 80f), delayMillis = 32) }
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, 80])
        runOnIdle { page.value = InkPage("other-page", emptyList()) }
        frame.performMouseInput { release() }
        runOnIdle { assertTrue(edits.isEmpty()) }
        assertEquals(Color.White, frame.captureToImage().toPixelMap()[60, 80])
    }

    @Test
    fun changingParentZoomCancelsTheCapturedGesture() = runDesktopComposeUiTest(width = 256, height = 256) {
        val zoom = mutableStateOf(1f)
        val edits = mutableListOf<InkEdit>()
        setContent {
            Viewport(1f, zoom.value, Offset.Zero, Color.White) {
                InkLayer(page(), DpSize(256.dp, 256.dp), Color.Black, tool = InkTool.Pen,
                    visibleWindow = { Rect(0f, 0f, 256f / zoom.value, 256f / zoom.value) },
                    zoom = zoom.value, onEdit = { edit, _ -> edits += edit })
            }
        }
        val frame = onNodeWithTag(FRAME)
        frame.performMouseInput {
            updatePointerTo(Offset(20f, 120f)); press(); moveTo(Offset(180f, 120f), delayMillis = 32)
        }
        assertEquals(Color.Black, frame.captureToImage().toPixelMap()[100, 120])
        runOnIdle { zoom.value = 1.25f }
        frame.performMouseInput { release() }
        runOnIdle { assertTrue(edits.isEmpty()) }
        val pixels = frame.captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[100, 120])
        assertEquals(Color.White, pixels[125, 150])
    }

    @Composable
    private fun Viewport(density: Float, zoom: Float, scroll: Offset, background: Color,
        content: @Composable () -> Unit) {
        Box(Modifier.size((256f / density).dp).clipToBounds().background(background).testTag(FRAME)) {
            Layout(content = content) { measurables, _ ->
                val child = measurables.single().measure(Constraints())
                layout(256, 256) {
                    child.placeWithLayer(0, 0) {
                        scaleX = zoom; scaleY = zoom
                        translationX = -scroll.x; translationY = -scroll.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    }

    private fun line(id: String, y: Float, color: Int) = VisibleInkStroke(id, "marker", 1, 6f,
        color, false, listOf(InkSample(10f, y), InkSample(90f, y)))

    private fun page() = InkPage("page", listOf(line("finished", 30f, 0xffff0000.toInt())))

    companion object { private const val FRAME = "ink-cache-frame" }
}
