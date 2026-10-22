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
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import kotlin.test.*

class InkViewportRasterCacheTest {
    @BeforeTest fun graphics() {
        @OptIn(InternalComposeUiApi::class) registerSkikoComposeImplementation()
    }

    @Test fun exactPanAndZoomReturnsReusePixelsWithoutVectorsAtFractionalDensities() {
        val scene = scene()
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        for (density in listOf(1f, 1.75f, 2f)) {
            val transform = ImmutableAffineTransform(density, .013f, 1.25f, -.02f, density, .75f)
            val views = listOf(View(Rect(17.25f, 8.75f, 217.25f, 208.75f), 1.25f),
                View(Rect(47.75f, 29.5f, 247.75f, 229.5f), 1.25f),
                View(Rect(9.75f, 3.5f, 159.75f, 153.5f), .3f))
            val renderer = InkPathRenderer()
            try {
                SegmentedInkRasterCache().use { cache ->
                    val expected = views.map { view -> fresh(scene, emptySet(), renderer, view, transform) }
                    for (index in listOf(0, 1, 0, 2, 1, 2, 0)) {
                        assertContentEquals(expected[index], draw(cache, layers, renderer, views[index], transform))
                    }
                    val work = cache.work()
                    assertEquals(7L, work.requests)
                    assertEquals(3L, work.fullBuilds)
                    assertEquals(4L, work.hits)
                    assertEquals(3, work.retainedViews)
                    assertEquals(views.sumOf { it.bytes }, work.retainedPixelBytes)
                    assertEquals(views.sumOf { it.bytes } / 4, work.allocatedPixels)
                    assertEquals(work.allocatedPixels, work.updatedPixels)
                    assertEquals(0, cache.lastDrawn)
                    assertEquals(0L, cache.lastUpdatedPixels)
                    val before = cache.work()
                    repeat(3) { draw(cache, layers, renderer, views[0], transform) }
                    assertEquals(before.vectors, cache.work().vectors)
                    assertEquals(before.allocatedPixels, cache.work().allocatedPixels)
                }
            } finally { renderer.clearCache() }
        }
    }

    @Test fun byteBudgetAndViewLimitEvictLeastRecentlyUsedImages() {
        val scene = scene()
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        val views = List(4) { View(Rect(it * 10f, 0f, it * 10f + 128f, 128f), 1f) }
        for ((limit, budget) in listOf(2 to Long.MAX_VALUE, 8 to (2L * 128 * 128 * 4))) {
            val renderer = InkPathRenderer()
            try {
                SegmentedInkRasterCache(limit, budget).use { cache ->
                    for (i in listOf(0, 1, 0, 2, 0)) draw(cache, layers, renderer, views[i])
                    assertEquals(3L, cache.rasterBuildCount)
                    assertEquals(1L, cache.work().capacityEvictions)
                    assertEquals(2, cache.retainedViews)
                    assertEquals(2L * 128 * 128 * 4, cache.retainedPixelBytes)
                    draw(cache, layers, renderer, views[1])
                    assertEquals(4L, cache.rasterBuildCount, "view 1 was evicted after view 0 was touched")
                    assertEquals(2L, cache.work().capacityEvictions)
                    assertContentEquals(fresh(scene, emptySet(), renderer, views[1]), draw(cache, layers, renderer, views[1]))
                }
            } finally { renderer.clearCache() }
        }
    }

    @Test fun contentChangesInvalidateOtherViewsAndKeepCurrentAppendAndDamagePaths() {
        val original = scene()
        val extra = scene(0x6600ff00)
        val views = listOf(View(Rect(0f, 0f, 256f, 256f), 1f), View(Rect(32f, 0f, 288f, 256f), 1f))
        val renderer = InkPathRenderer()
        try {
            SegmentedInkRasterCache().use { cache ->
                val initial = listOf(InkSceneLayer(original, emptySet()))
                views.forEach { draw(cache, initial, renderer, it) }
                val appended = initial + InkSceneLayer(extra, emptySet())
                val full = InkScene(original.strokes + extra.strokes)
                assertContentEquals(fresh(full, emptySet(), renderer, views[1]), draw(cache, appended, renderer, views[1]))
                assertEquals(1L, cache.work().appendBuilds)
                assertEquals(1, cache.retainedViews)
                assertContentEquals(fresh(full, emptySet(), renderer, views[0]), draw(cache, appended, renderer, views[0]))
                assertEquals(3L, cache.work().fullBuilds)
                val excluded = setOf(original.strokes.last())
                val changed = listOf(InkSceneLayer(original, excluded), InkSceneLayer(extra, emptySet()))
                val fullExclusion = setOf(full.strokes[original.strokes.lastIndex])
                assertContentEquals(fresh(full, fullExclusion, renderer, views[0]), draw(cache, changed, renderer, views[0]))
                assertEquals(1, cache.retainedViews)
                assertTrue(cache.dirtyBuildCount > 0)
                assertContentEquals(fresh(full, fullExclusion, renderer, views[1]), draw(cache, changed, renderer, views[1]))
                assertContentEquals(fresh(full, emptySet(), renderer, views[1]), draw(cache, appended, renderer, views[1]))
                assertEquals(1, cache.retainedViews, "undo must discard every older view")
                assertContentEquals(fresh(full, emptySet(), renderer, views[0]), draw(cache, appended, renderer, views[0]))
            }
        } finally { renderer.clearCache() }
    }

    @Test fun equalOccurrencesUseIdentityAndEquivalentLayerListsStillHit() {
        val stroke = scene().strokes.first()
        val scene = InkScene(listOf(stroke, stroke))
        val view = View(Rect(0f, 0f, 128f, 128f), 1f)
        val renderer = InkPathRenderer()
        try {
            SegmentedInkRasterCache().use { cache ->
                val first = listOf(InkSceneLayer(scene, setOf(scene.strokes[0])))
                draw(cache, first, renderer, view)
                draw(cache, listOf(InkSceneLayer(scene, setOf(scene.strokes[0]))), renderer, view)
                assertEquals(1L, cache.work().hits)
                val other = listOf(InkSceneLayer(scene, setOf(scene.strokes[1])))
                assertContentEquals(fresh(scene, setOf(scene.strokes[1]), renderer, view), draw(cache, other, renderer, view))
                assertEquals(2L, cache.rasterBuildCount, "equal occurrences are different exclusion keys")
                assertEquals(1L, cache.work().hits)
            }
        } finally { renderer.clearCache() }
    }

    @Test fun invalidViewsPreserveAllCachedImagesAndClearCloseReleaseThem() {
        val scene = scene()
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        val views = List(3) { View(Rect(it * 8f, 0f, it * 8f + 128f, 128f), 1f) }
        val renderer = InkPathRenderer()
        val cache = SegmentedInkRasterCache()
        try {
            views.forEach { draw(cache, layers, renderer, it) }
            val before = cache.work()
            Surface.makeRasterN32Premul(1, 1).use { surface ->
                for (view in listOf(View(Rect(0f, 0f, Float.NaN, 128f), 1f),
                    View(Rect(0f, 0f, 2_000_000_000f, 2_000_000_000f), 1f), views[0].copy(scale = 0f))) {
                    assertFailsWith<IllegalArgumentException> { cache.draw(surface.canvas.asComposeCanvas(), layers,
                        renderer, view.rect, identity, view.scale) }
                }
                assertFailsWith<IllegalArgumentException> { cache.draw(surface.canvas.asComposeCanvas(), layers,
                    renderer, views[0].rect, ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f), 1f) }
            }
            assertEquals(before, cache.work())
            views.forEach { draw(cache, layers, renderer, it) }
            assertEquals(3L, cache.rasterBuildCount)
            cache.clearCache()
            assertEquals(0, cache.retainedViews)
            assertEquals(0L, cache.retainedPixelBytes)
            views.forEach { draw(cache, layers, renderer, it) }
            cache.close()
            assertEquals(0, cache.retainedViews)
            assertEquals(0L, cache.retainedPixelBytes)
            assertFailsWith<IllegalStateException> { draw(cache, layers, renderer, views[0]) }
        } finally { cache.close(); renderer.clearCache() }
    }

    @Test fun uncacheableViewsAndDifferentRenderersReleaseOlderImages() {
        val scene = scene()
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        val small = View(Rect(0f, 0f, 64f, 64f), 1f)
        val large = View(Rect(0f, 0f, 256f, 256f), 1f)
        val renderer = InkPathRenderer()
        val replacement = InkPathRenderer()
        try {
            SegmentedInkRasterCache(4, 128L * 128 * 4).use { cache ->
                draw(cache, layers, renderer, small)
                draw(cache, layers, renderer, small.copy(rect = Rect(8f, 0f, 72f, 64f)))
                draw(cache, layers, replacement, small)
                assertEquals(1, cache.retainedViews)
                assertContentEquals(fresh(scene, emptySet(), replacement, large), draw(cache, layers, replacement, large))
                assertEquals(0L, cache.retainedPixelBytes)
                assertEquals(0, cache.retainedViews)
                draw(cache, layers, renderer, small)
                assertEquals(5L, cache.rasterBuildCount)
            }
            SegmentedInkRasterCache(0).use { cache ->
                repeat(2) { draw(cache, layers, renderer, small) }
                assertEquals(2L, cache.rasterBuildCount)
                assertEquals(0, cache.retainedViews)
            }
            assertFailsWith<IllegalArgumentException> { SegmentedInkRasterCache(-1) }
            assertFailsWith<IllegalArgumentException> { SegmentedInkRasterCache(pixelBudgetBytes = -1) }
        } finally { renderer.clearCache(); replacement.clearCache() }
    }

    private data class View(val rect: Rect, val scale: Float) {
        val width get() = ceil((rect.right.toDouble() - rect.left) * scale).toInt()
        val height get() = ceil((rect.bottom.toDouble() - rect.top) * scale).toInt()
        val bytes get() = width.toLong() * height * 4
    }
    private val identity = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)
    private fun draw(cache: SegmentedInkRasterCache, layers: List<InkSceneLayer>, renderer: InkPathRenderer,
        view: View, transform: ImmutableAffineTransform = identity) = pixels(view) {
        cache.draw(it.canvas.asComposeCanvas(), layers, renderer, view.rect, transform, view.scale)
    }
    private fun fresh(scene: InkScene, excluded: Set<InkSceneStroke>, renderer: InkPathRenderer,
        view: View, transform: ImmutableAffineTransform = identity) = InkSceneRasterCache().use { cache -> pixels(view) {
        cache.draw(it.canvas.asComposeCanvas(), scene, renderer, view.rect, transform, view.scale, excluded)
    } }
    private fun pixels(view: View, draw: (Surface) -> Unit): ByteArray = Surface.makeRasterN32Premul(view.width, view.height).use {
        it.canvas.clear(0xffffffff.toInt()); it.canvas.scale(view.scale, view.scale)
        it.canvas.translate(-view.rect.left, -view.rect.top); draw(it)
        it.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
    }
    private fun scene(color: Int = 0x66ff8800): InkScene {
        val brush = ViveInkTool(ViveBrushes.HIGHLIGHTER, 0, color, 8f).brush
        val stroke = Stroke(brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 7.25f, 12.5f, 0L)
            add(InputToolType.MOUSE, 19.75f, 23.125f, 100L)
        })
        return InkScene(List(36) { index -> InkSceneStroke(stroke,
            ImmutableAffineTransform(1f, .07f, index % 6 * 39f, -.11f, 1f, index / 6 * 39f), color) })
    }
}
