package com.vivenotes.ui.canvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.rendering.InkRenderContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.*

class InkDirectReplayTest {
    @BeforeTest fun graphics() {
        @OptIn(InternalComposeUiApi::class) registerSkikoComposeImplementation()
    }

    @Test fun defaultUsesMovingPolicyWithExplicitRasterAndDiagnosticOverrides() {
        assertEquals(InkViewportRendering.MOVING, InkViewportRendering.fromProperty(null))
        assertEquals(InkViewportRendering.MOVING, InkViewportRendering.fromProperty("moving"))
        assertEquals(InkViewportRendering.RASTER, InkViewportRendering.fromProperty("raster"))
        assertEquals(InkViewportRendering.RASTER, InkViewportRendering.fromProperty("unknown"))
        assertEquals(InkViewportRendering.DIRECT, InkViewportRendering.fromProperty("direct"))
    }

    @Test fun replayPreservesClippingExclusionsAndFractionalViewportPixels() {
        val brush = ViveInkTool(ViveBrushes.MARKER, 0, 0xff263850.toInt(), 3f).brush
        val stroke = Stroke(brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 7.25f, 12.5f, 0L)
            add(InputToolType.MOUSE, 50.5f, 65.125f, 40L)
            add(InputToolType.MOUSE, 94.25f, 12.5f, 80L)
        })
        val scene = InkScene(List(4) { index -> InkSceneStroke(stroke,
            ImmutableAffineTransform(1f, .07f, index * 20f, -.11f, 1f, index * 15f)) })
        val renderer = InkPathRenderer()
        try {
            for (scale in listOf(1f, 1.25f, .75f)) for (scroll in listOf(0f, 17.25f, 29.75f)) {
                val viewport = Rect(scroll / scale, 8.75f / scale, (scroll + 128f) / scale, 136.75f / scale)
                val layers = listOf(InkSceneLayer(scene, setOf(scene.strokes[1])))
                fun draw(direct: Boolean): ByteArray = Surface.makeRasterN32Premul(128, 128).use { surface ->
                    surface.canvas.clear(0xffffffff.toInt())
                    surface.canvas.scale(scale, scale)
                    surface.canvas.translate(-viewport.left, -viewport.top)
                    SegmentedInkRasterCache(rendering = if (direct) InkViewportRendering.DIRECT else InkViewportRendering.RASTER).use { cache ->
                        cache.draw(surface.canvas.asComposeCanvas(), layers, renderer, viewport,
                            ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f), scale)
                        if (direct) {
                            assertEquals(0L, cache.retainedPixelBytes)
                            assertEquals(0L, cache.rasterBuildCount)
                            assertTrue(cache.lastDrawn > 0)
                        }
                    }
                    surface.makeImageSnapshot().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                    }
                }
                assertContentEquals(draw(false), draw(true), "scale=$scale scroll=$scroll")
            }
        } finally { renderer.clearCache() }
    }

    @Test fun movingViewsReplayWhileStationaryDrawingAndSoftwareKeepRasterReuse() {
        val scene = translucentScene()
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        val renderer = InkPathRenderer()
        val a = Rect(0f, 0f, 128f, 128f)
        val b = a.translate(9.25f, 7.5f)
        val c = a.translate(17.75f, 15.25f)
        SegmentedInkRasterCache(rendering = InkViewportRendering.MOVING).use { cache ->
            try {
                fun check(view: Rect, content: List<InkSceneLayer> = layers) {
                    val expected = SegmentedInkRasterCache(rendering = InkViewportRendering.RASTER).use {
                        pixels(it, content, renderer, view)
                    }
                    assertContentEquals(expected, pixels(cache, content, renderer, view))
                }
                InkRenderContext.openGpuFrame().use {
                    check(a)
                    check(b)
                    check(c)
                    assertEquals(1L, cache.rasterBuildCount)
                    assertEquals(2L, cache.work().directDraws)
                    check(c)
                    assertEquals(2L, cache.rasterBuildCount, "settled viewport regains a reusable image")
                    repeat(4) { check(c) }
                    assertEquals(2L, cache.rasterBuildCount, "pen frames reuse the stationary background")
                    assertEquals(0, cache.lastDrawn)
                    check(a)
                    assertEquals(2L, cache.rasterBuildCount, "returning to a retained view reuses its exact pixels")
                    assertEquals(2L, cache.work().directDraws)
                    check(c, listOf(InkSceneLayer(scene, setOf(scene.strokes[1]))))
                    check(c)
                    assertEquals(2L, cache.work().directDraws, "erase and undo retain raster invalidation")
                }
                val before = cache.rasterBuildCount
                check(b)
                assertEquals(before + 1, cache.rasterBuildCount, "software fallback always uses the raster path")
                assertEquals(2L, cache.work().directDraws)
                cache.clearCache()
                assertEquals(0L, cache.retainedPixelBytes)
                InkRenderContext.openGpuFrame().use { check(c) }
                assertEquals(before + 2, cache.rasterBuildCount, "a cleared cache starts with a normal raster")
            } finally { renderer.clearCache() }
        }
    }

    @Test fun translucentOverlapsAcrossSegmentsAndEraseUndoPreserveExactPixels() {
        val first = translucentScene()
        val second = translucentScene(0x6655cc99)
        val renderer = InkPathRenderer()
        try {
            for (scale in listOf(.3f, .75f, 1f, 1.25f, 2f)) {
                val viewport = Rect(7.25f / scale, 3.5f / scale, 135.25f / scale, 131.5f / scale)
                for (erased in listOf(false, true, false)) {
                    val layers = listOf(InkSceneLayer(first, if (erased) setOf(first.strokes[1]) else emptySet()),
                        InkSceneLayer(second, emptySet()))
                    val expected = SegmentedInkRasterCache(rendering = InkViewportRendering.RASTER).use {
                        pixels(it, layers, renderer, viewport, scale)
                    }
                    val actual = SegmentedInkRasterCache(rendering = InkViewportRendering.DIRECT).use {
                        pixels(it, layers, renderer, viewport, scale)
                    }
                    assertContentEquals(expected, actual, "scale=$scale erased=$erased")
                }
            }
        } finally { renderer.clearCache() }
    }

    private fun translucentScene(color: Int = 0x667700ff): InkScene {
        val brush = ViveInkTool(ViveBrushes.MARKER, 0, color, 9f).brush
        val stroke = Stroke(brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 5.25f, 15.5f, 0L)
            add(InputToolType.MOUSE, 55.5f, 85.125f, 40L)
            add(InputToolType.MOUSE, 114.25f, 15.5f, 80L)
        })
        return InkScene(List(3) { index -> InkSceneStroke(stroke,
            ImmutableAffineTransform(1f, .07f, index * 7.25f, -.11f, 1f, index * 5.5f)) })
    }

    private fun pixels(cache: SegmentedInkRasterCache, layers: List<InkSceneLayer>, renderer: InkPathRenderer,
        viewport: Rect, scale: Float = 1f): ByteArray = Surface.makeRasterN32Premul(128, 128).use { surface ->
        surface.canvas.clear(0xffe3dacd.toInt())
        surface.canvas.scale(scale, scale)
        surface.canvas.translate(-viewport.left, -viewport.top)
        cache.draw(surface.canvas.asComposeCanvas(), layers, renderer, viewport,
            ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f), scale)
        surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
    }
}
