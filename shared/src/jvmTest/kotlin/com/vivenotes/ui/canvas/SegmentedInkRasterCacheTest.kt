package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
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
    @BeforeTest
    fun initializeStandaloneGraphics() {
        @OptIn(InternalComposeUiApi::class)
        registerSkikoComposeImplementation()
    }

    @Test
    fun cancellingLargeFiniteTransformsFallBackToExactFullViewportPixels() {
        val source = scene(0x66ff8800, 0f).strokes.first().stroke
        val offset = 1_073_741_824f
        val scene = InkScene(listOf(InkSceneStroke(source, ImmutableAffineTransform(1f, 0f, offset, 0f, 1f, offset), 0x660000ff),
            InkSceneStroke(source, ImmutableAffineTransform(1f, 0f, offset, 0f, 1f, offset), 0x66ff0000)))
        val transform = ImmutableAffineTransform(1f, 0f, -offset, 0f, 1f, -offset)
        val viewport = Rect(17.25f, 8.75f, 217.25f, 208.75f)
        for (scale in listOf(.3f, 1.25f)) {
            val renderer = InkPathRenderer()
            SegmentedInkRasterCache().use { cache -> InkSceneRasterCache().use { reference ->
                try {
                    for (excluded in listOf(emptySet(), setOf(scene.strokes.first()))) {
                        val actual = render(viewport, scale) { cache.draw(it.canvas.asComposeCanvas(),
                            listOf(InkSceneLayer(scene, excluded)), renderer, viewport, transform, scale) }
                        val expected = render(viewport, scale) { reference.draw(it.canvas.asComposeCanvas(),
                            scene, renderer, viewport, transform, scale, excluded) }
                        assertContentEquals(expected, actual)
                        assertTrue(cache.lastDrawn > 0)
                    }
                    assertEquals(0L, cache.dirtyBuildCount, "large cancellation error safely chooses the full raster")
                } finally { renderer.clearCache() }
            } }
        }
    }

    @Test
    fun dirtyEraseAndUndoMatchFullRasterWithOverlapsAndFractionalTransforms() {
        val shape = scene(0x66ff8800, 0f).strokes.first().stroke
        val scenes = List(4) { layer -> InkScene(List(100) { index ->
            val column = index % 10
            val row = index / 10
            val mirror = if (index % 3 == 0) -.22f else .22f
            InkSceneStroke(shape, ImmutableAffineTransform(mirror, .04f, column * 53f + 38f,
                -.03f, .22f, row * 43f + 18f + layer * 2f),
                if (layer % 2 == 0) 0x667700ff else 0x6600aa77)
        }) }
        val viewport = Rect(7.25f, 3.5f, 605.25f, 495.5f)
        for (density in listOf(1f, 2f)) for (scale in listOf(.3f, 1f, 1.25f)) {
            val transform = ImmutableAffineTransform(density, .12f * density, -25f,
                -.07f * density, density, 35f)
            val renderer = InkPathRenderer()
            val full = InkScene(scenes.flatMap { it.strokes })
            SegmentedInkRasterCache().use { cache -> InkSceneRasterCache().use { reference ->
                try {
                    var fullDrawn = 0
                    for (erased in listOf(false, true, false)) {
                        val layers = scenes.mapIndexed { layer, item -> InkSceneLayer(item,
                            if (erased && layer == 1) setOf(item.strokes[22]) else emptySet()) }
                        val actual = render(viewport, scale) { target -> cache.draw(target.canvas.asComposeCanvas(),
                            layers, renderer, viewport, transform, scale) }
                        val expected = render(viewport, scale) { target -> reference.draw(target.canvas.asComposeCanvas(),
                            full, renderer, viewport, transform, scale, if (erased) setOf(full.strokes[122]) else emptySet()) }
                        assertContentEquals(expected, actual, "density=$density scale=$scale erased=$erased")
                        if (cache.rasterBuildCount == 1L) fullDrawn = cache.lastDrawn else {
                            assertTrue(cache.lastDrawn < fullDrawn / 4, "only strokes overlapping the erased piece should redraw")
                            assertTrue(cache.lastUpdatedPixels < cache.retainedPixelBytes / 16)
                        }
                    }
                    assertEquals(2L, cache.dirtyBuildCount, "erase and undo both use exact dirty redraw")
                } finally { renderer.clearCache() }
            } }
        }
    }

    @Test
    fun dirtySceneReplacementKeepsDrawOrderAndEqualOccurrenceIdentity() {
        val shape = scene(0x66ffff00, 0f).strokes.first().stroke
        val duplicate = InkSceneStroke(shape, ImmutableAffineTransform(.3f, .05f, 70f, -.04f, .3f, 65f))
        val first = InkScene(listOf(duplicate, duplicate))
        val middle = InkScene(listOf(InkSceneStroke(shape, ImmutableAffineTransform(.4f, 0f, 60f, 0f, .4f, 62f), 0x770000ff)))
        val replacement = InkScene(listOf(InkSceneStroke(shape, ImmutableAffineTransform(.25f, .05f, 72f, -.04f, .25f, 67f), 0x77ff0000)))
        val last = InkScene(listOf(InkSceneStroke(shape, ImmutableAffineTransform(.3f, 0f, 75f, 0f, .3f, 62f), 0x7700ff00)))
        val viewport = Rect(17.25f, 8.75f, 217.25f, 208.75f)
        val transform = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
        val renderer = InkPathRenderer()
        SegmentedInkRasterCache().use { cache -> InkSceneRasterCache().use { reference ->
            try {
                for (replaced in listOf(false, true)) {
                    val ordered = listOf(first, if (replaced) replacement else middle, last)
                    val layers = ordered.mapIndexed { index, scene -> InkSceneLayer(scene,
                        if (index == 0) setOf(scene.strokes.first()) else emptySet()) }
                    val actual = render(viewport, 1.25f) { cache.draw(it.canvas.asComposeCanvas(), layers, renderer, viewport, transform, 1.25f) }
                    val full = InkScene(ordered.flatMap { it.strokes })
                    val expected = render(viewport, 1.25f) { reference.draw(it.canvas.asComposeCanvas(), full, renderer,
                        viewport, transform, 1.25f, setOf(full.strokes.first())) }
                    assertContentEquals(expected, actual)
                }
                assertEquals(1L, cache.dirtyBuildCount)
            } finally { renderer.clearCache() }
        } }
    }

    @Test
    fun offscreenExclusionChangesKeepTheExistingRasterWithoutNativeDraws() {
        val offscreen = scene(0xff000000.toInt(), 1000f)
        val visible = scene(0xff000000.toInt(), 0f)
        val renderer = InkPathRenderer()
        val viewport = Rect(0f, 0f, 128f, 128f)
        val transform = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
        SegmentedInkRasterCache().use { cache ->
            try {
                val first = render(viewport, 1f) { cache.draw(it.canvas.asComposeCanvas(),
                    listOf(InkSceneLayer(visible, emptySet()), InkSceneLayer(offscreen, emptySet())), renderer, viewport, transform, 1f) }
                val second = render(viewport, 1f) { cache.draw(it.canvas.asComposeCanvas(),
                    listOf(InkSceneLayer(visible, emptySet()), InkSceneLayer(offscreen, setOf(offscreen.strokes.first()))), renderer, viewport, transform, 1f) }
                assertContentEquals(first, second)
                assertEquals(1L, cache.rasterBuildCount)
                assertEquals(0, cache.lastDrawn)
            } finally { renderer.clearCache() }
        }
    }

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
