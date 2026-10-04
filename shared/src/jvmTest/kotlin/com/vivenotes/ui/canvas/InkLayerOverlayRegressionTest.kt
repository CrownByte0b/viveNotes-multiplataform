package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toEntity
import com.vivenotes.workspace.InkTool
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkLayerOverlayRegressionTest {
    @Test
    fun finishedAppendsKeepBaseSceneIndexAndRasterAndDisposeBothLayers() = runDesktopComposeUiTest(width = 128, height = 128) {
        val tool = ViveInkTool(ViveBrushes.MARKER, colorArgb = 0xffff0000.toInt(), sizeDp = 6f)
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 30f, 0); add(InputToolType.MOUSE, 100f, 30f, 100)
        }
        val initial = InkPageReader.read("page", listOf(tool.complete(Stroke(tool.brush, inputs), "base", "page", 0, 1).row.toEntity()), emptyList(), emptyList())
        val base = initial.desktopGeometry().base
        val index = base.index
        val page = mutableStateOf(initial)
        val show = mutableStateOf(true)
        val diagnostics = InkLayerDiagnostics()
        setContent {
            if (show.value) CompositionLocalProvider(LocalInkLayerDiagnostics provides diagnostics) {
                Box(Modifier.size(128.dp).background(Color.White).testTag("overlay-frame")) {
                    InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = InkTool.Pen,
                        onEdit = { _, updated -> page.value = updated })
                }
            }
        }
        val frame = onNodeWithTag("overlay-frame")
        assertEquals(Color.Red, frame.captureToImage().toPixelMap()[60, 30])
        val initialCounts = diagnostics.counts()
        repeat(2) { index ->
            val y = 70f + index * 30f
            frame.performMouseInput { updatePointerTo(Offset(10f, y)); press(); moveTo(Offset(100f, y), delayMillis = 32); release() }
            assertEquals(Color.Black, frame.captureToImage().toPixelMap()[60, y.toInt()])
        }
        runOnIdle {
            assertSame(base, page.value.desktopGeometry().base)
            assertSame(index, page.value.desktopGeometry().base.index)
            assertEquals(1L, diagnostics.baseSceneBuilds)
            assertEquals(2, page.value.desktopGeometry().additions.projections.size)
            assertEquals(2L, (diagnostics.counts() - initialCounts).rasters, "only the additions raster rebuilds at each finish")
            assertEquals(2L * 128 * 128 * 4, diagnostics.retainedPixelBytes)
            show.value = false
        }
        runOnIdle { assertEquals(0L, diagnostics.retainedPixelBytes) }
    }

    @Test
    fun translucentAdditionOverBaseMatchesCanonicalReopenAndTombstonesHideBothLayers() = runDesktopComposeUiTest(width = 128, height = 128) {
        val pen = ViveInkTool(ViveBrushes.MARKER, colorArgb = 0xff000000.toInt(), sizeDp = 6f)
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0); add(InputToolType.MOUSE, 110f, 50f, 100)
        }
        val base = pen.complete(Stroke(pen.brush, inputs), "base", "page", 0, 1).row.toEntity()
        val page = mutableStateOf(InkPageReader.read("page", listOf(base), emptyList(), emptyList()))
        val tool = mutableStateOf(InkTool.Highlighter)
        val edits = mutableListOf<InkEdit>()
        setContent {
            Box(Modifier.size(128.dp).background(Color.White).testTag("overlay-frame")) {
                InkLayer(page.value, DpSize(128.dp, 128.dp), Color.Black, tool = tool.value,
                    onEdit = { edit, updated -> edits += edit; page.value = updated })
            }
        }
        val frame = onNodeWithTag("overlay-frame")
        frame.performMouseInput { updatePointerTo(Offset(10f, 50f)); press(); moveTo(Offset(110f, 50f), delayMillis = 32); release() }
        val before = frame.captureToImage().toPixelMap()
        val highlight = (edits.single() as InkEdit.AddStroke).row
        runOnIdle { page.value = InkPageReader.read("page", listOf(base, highlight.copy(seq = 1)), emptyList(), emptyList()) }
        val reloaded = frame.captureToImage().toPixelMap()
        for (y in 0 until 128) for (x in 0 until 128) {
            val a = before[x, y]; val b = reloaded[x, y]
            assertTrue(kotlin.math.abs(a.red - b.red) <= 2.01f / 255f && kotlin.math.abs(a.green - b.green) <= 2.01f / 255f &&
                kotlin.math.abs(a.blue - b.blue) <= 2.01f / 255f && a.alpha == b.alpha, "canonical layer pixels differ at $x,$y")
        }
        runOnIdle { tool.value = InkTool.Eraser }
        frame.performMouseInput { updatePointerTo(Offset(60f, 50f)); press(); release() }
        assertEquals(Color.White, frame.captureToImage().toPixelMap()[90, 50])
        runOnIdle { assertTrue(page.value.desktopGeometry().projections.isEmpty()) }
    }
}
