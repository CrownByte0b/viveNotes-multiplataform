package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import kotlin.test.*

class SegmentedInkRasterCacheTest {
    @Test
    fun theSparseScenarioUsesExactEndpointDimensionsAndNativeCachePixels() {
        val scale = 1.25f
        val viewport = Rect(17.25f / scale, 29.75f / scale, (17.25f + 512) / scale, (29.75f + 512) / scale)
        val renderer = InkPathRenderer()
        val scene = scene(0x66ffff00, 0f)
        val transform = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(513.0, ceil(viewport.width.toDouble() * scale), "Float subtraction crosses a physical pixel boundary")
        SegmentedInkRasterCache().use { segmented -> InkSceneRasterCache().use { reference ->
            try {
                val actual = render(viewport, scale) { surface ->
                    segmented.draw(surface.canvas.asComposeCanvas(), listOf(InkSceneLayer(scene, emptySet())),
                        renderer, viewport, transform, scale)
                }
                val expected = render(viewport, scale) { surface ->
                    reference.draw(surface.canvas.asComposeCanvas(), scene, renderer, viewport, transform, scale)
                }
                assertEquals(512L * 512 * 4, reference.retainedPixelBytes)
                assertEquals(reference.retainedPixelBytes, segmented.retainedPixelBytes)
                assertContentEquals(expected, actual)
            } finally { renderer.clearCache() }
        } }
    }

    @Test
    fun appendingSegmentsCopiesExactPixelsAtFractionalViewsAndDrawsOnlyNewVectors() {
        val scenes = listOf(scene(0xffff0000.toInt(), 0f), scene(0x66ffff00, 4f), scene(0x550000ff, 8f))
        val viewport = Rect(7.25f, 3.5f, 105.25f, 95.5f)
        for (density in listOf(1f, 2f)) for (scale in listOf(.3f, 1f, 1.25f)) {
            val transform = ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
            val renderer = InkPathRenderer()
            SegmentedInkRasterCache().use { segmented -> InkSceneRasterCache().use { reference ->
                try {
                    for (count in 1..scenes.size) {
                        val layers = scenes.take(count).map { InkSceneLayer(it, emptySet()) }
                        val actual = render(viewport, scale) { surface ->
                            segmented.draw(surface.canvas.asComposeCanvas(), layers, renderer, viewport, transform, scale)
                        }
                        assertTrue(segmented.lastDrawn in 1..scenes.last().strokes.size,
                            "an append must rasterize only the newly sealed segment")
                        val full = InkScene(scenes.take(count).flatMap { it.strokes })
                        val expected = render(viewport, scale) { surface ->
                            reference.draw(surface.canvas.asComposeCanvas(), full, renderer, viewport, transform, scale)
                        }
                        assertContentEquals(expected, actual, "density=$density scale=$scale segments=$count")
                    }
                    val changed = scenes.mapIndexed { index, item -> InkSceneLayer(item,
                        if (index == 0) setOf(item.strokes.first()) else emptySet()) }
                    val actual = render(viewport, scale) { surface ->
                        segmented.draw(surface.canvas.asComposeCanvas(), changed, renderer, viewport, transform, scale)
                    }
                    val full = InkScene(scenes.flatMap { it.strokes })
                    val expected = render(viewport, scale) { surface ->
                        reference.draw(surface.canvas.asComposeCanvas(), full, renderer, viewport, transform, scale,
                            setOf(full.strokes.first()))
                    }
                    assertContentEquals(expected, actual, "exclusion must invalidate prior pixels")
                } finally { renderer.clearCache() }
            } }
        }
    }

    @Test
    fun anUnchangedLayerListDoesNotWalkSegmentsDuringLiveFrames() {
        val source = List(32) { InkSceneLayer(scene(0xff000000.toInt(), it.toFloat()), emptySet()) }
        var reads = 0
        val layers = object : AbstractList<InkSceneLayer>() {
            override val size get() = source.size
            override fun get(index: Int): InkSceneLayer { reads++; return source[index] }
        }
        val renderer = InkPathRenderer()
        val viewport = Rect(0f, 0f, 128f, 128f)
        val transform = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
        SegmentedInkRasterCache().use { cache ->
            try {
                render(viewport, 1f) { cache.draw(it.canvas.asComposeCanvas(), layers, renderer, viewport, transform, 1f) }
                val builds = cache.rasterBuildCount
                reads = 0
                repeat(10) {
                    render(viewport, 1f) { cache.draw(it.canvas.asComposeCanvas(), layers, renderer, viewport, transform, 1f) }
                }
                assertEquals(0, reads, "wet frames must use the immutable layer-list identity")
                assertEquals(builds, cache.rasterBuildCount)
            } finally { renderer.clearCache() }
        }
    }

    @Test
    fun invalidViewsDoNotAllocateOrDiscardUsableRasterAndCloseReleasesIt() {
        val layers = listOf(InkSceneLayer(scene(0xff000000.toInt(), 0f), emptySet()))
        val renderer = InkPathRenderer()
        val viewport = Rect(0f, 0f, 128f, 128f)
        val transform = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
        val cache = SegmentedInkRasterCache()
        try {
            Surface.makeRasterN32Premul(128, 128).use { surface ->
                val canvas = surface.canvas.asComposeCanvas()
                cache.draw(canvas, layers, renderer, viewport, transform, 1f)
                val bytes = cache.retainedPixelBytes
                val builds = cache.rasterBuildCount
                for (invalid in listOf(Rect(1f, 0f, 0f, 10f), Rect(0f, 0f, 2_000_000_000f, 2_000_000_000f))) {
                    assertFailsWith<IllegalArgumentException> { cache.draw(canvas, layers, renderer, invalid, transform, 1f) }
                }
                assertFailsWith<IllegalArgumentException> { cache.draw(canvas, layers, renderer, viewport,
                    ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f), 1f) }
                assertEquals(bytes, cache.retainedPixelBytes)
                cache.draw(canvas, layers, renderer, viewport, transform, 1f)
                assertEquals(builds, cache.rasterBuildCount)
                cache.close()
                assertEquals(0L, cache.retainedPixelBytes)
                assertFailsWith<IllegalStateException> { cache.draw(canvas, layers, renderer, viewport, transform, 1f) }
            }
        } finally { cache.close(); renderer.clearCache() }
    }

    private fun scene(color: Int, offset: Float): InkScene {
        val tool = ViveInkTool(if (color ushr 24 == 255) ViveBrushes.MARKER else ViveBrushes.HIGHLIGHTER,
            0, color, 10f)
        return InkScene(List(2) { index ->
            val input = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 4f, 18f + offset + index * 15, 0L)
                add(InputToolType.MOUSE, 115f, 48f + offset + index * 15, 100L)
            }
            InkSceneStroke(Stroke(tool.brush, input))
        })
    }

    private fun render(viewport: Rect, scale: Float, draw: (Surface) -> Unit): ByteArray =
        Surface.makeRasterN32Premul(ceil((viewport.right.toDouble() - viewport.left.toDouble()) * scale).toInt(),
            ceil((viewport.bottom.toDouble() - viewport.top.toDouble()) * scale).toInt()).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            surface.canvas.scale(scale, scale)
            surface.canvas.translate(-viewport.left, -viewport.top)
            draw(surface)
            surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        }
}
