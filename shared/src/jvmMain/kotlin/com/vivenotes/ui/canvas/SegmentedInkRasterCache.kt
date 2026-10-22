package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor

internal class InkSceneLayer(val scene: InkScene, excluded: Set<InkSceneStroke>) {
    val excluded: Set<InkSceneStroke> = if (excluded.isEmpty()) emptySet() else
        Collections.unmodifiableSet(Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>()).apply {
            addAll(excluded)
        })
}

internal data class InkViewportRasterWork(
    val requests: Long,
    val hits: Long,
    val fullBuilds: Long,
    val appendBuilds: Long,
    val dirtyBuilds: Long,
    val vectors: Long,
    val allocatedPixels: Long,
    val updatedPixels: Long,
    val capacityEvictions: Long,
    val retainedViews: Int,
    val retainedPixelBytes: Long,
)

/** Exact recent physical views; content changes keep only the view being updated. */
internal class SegmentedInkRasterCache(
    private val maxViews: Int = 4,
    private val pixelBudgetBytes: Long = 64L * 1024 * 1024,
) : AutoCloseable {
    init { require(maxViews >= 0 && pixelBudgetBytes >= 0) }
    private data class View(val viewport: Rect, val transform: ImmutableAffineTransform, val scale: Float)
    private class SavedRaster(val image: Image, val bytes: Long)
    private val previousViews = LinkedHashMap<View, SavedRaster>()
    private var previousPixelBytes = 0L
    private var currentPixelBytes = 0L
    private var image: Image? = null
    private var view: View? = null
    private var layers: List<InkSceneLayer> = emptyList()
    private var renderer: InkPathRenderer? = null
    private var rendererVersion = 0L
    private var closed = false
    val retainedPixelBytes: Long get() = currentPixelBytes + previousPixelBytes
    val retainedViews: Int get() = previousViews.size + if (image == null) 0 else 1
    private var requests = 0L
    private var hits = 0L
    private var fullBuilds = 0L
    private var appendBuilds = 0L
    private var vectors = 0L
    private var allocatedPixels = 0L
    private var updatedPixels = 0L
    private var capacityEvictions = 0L
    var rasterBuildCount = 0L
        private set
    var lastDrawn = 0
        private set
    var dirtyBuildCount = 0L
        private set
    var lastUpdatedPixels = 0L
        private set

    fun draw(canvas: Canvas, next: List<InkSceneLayer>, renderer: InkPathRenderer,
        viewport: Rect, transform: ImmutableAffineTransform, scale: Float): Int {
        check(!closed)
        require(scale.isFinite() && scale > 0f)
        require(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom).all(Float::isFinite))
        require(viewport.right >= viewport.left && viewport.bottom >= viewport.top)
        if (viewport.isEmpty || next !== layers && next.all { it.scene.strokes.isEmpty() }) { clearCache(); return 0 }
        val rawWidth = ceil((viewport.right.toDouble() - viewport.left.toDouble()) * scale)
        val rawHeight = ceil((viewport.bottom.toDouble() - viewport.top.toDouble()) * scale)
        require(rawWidth in 1.0..Int.MAX_VALUE.toDouble() && rawHeight in 1.0..Int.MAX_VALUE.toDouble())
        val width = rawWidth.toInt()
        val height = rawHeight.toInt()
        val pixels = width.toLong() * height
        require(pixels <= Int.MAX_VALUE / 4L)
        val bytes = pixels * 4L
        require(listOf(transform.m00, transform.m10, transform.m20, transform.m01, transform.m11, transform.m21).all(Float::isFinite))
        val inverse = transform.computeInverse()
        require(listOf(inverse.m00, inverse.m10, inverse.m20, inverse.m01, inverse.m11, inverse.m21).all(Float::isFinite))
        val sceneToRaster = ImmutableAffineTransform(transform.m00 * scale, transform.m10 * scale,
            (transform.m20 - viewport.left) * scale, transform.m01 * scale, transform.m11 * scale,
            (transform.m21 - viewport.top) * scale)
        fun finite(value: ImmutableAffineTransform) =
            listOf(value.m00, value.m10, value.m20, value.m01, value.m11, value.m21).all(Float::isFinite)
        require(finite(sceneToRaster) && finite(sceneToRaster.computeInverse()))
        val inverseScale = 1f / scale
        require(inverseScale.isFinite())
        val drawingViewport = Rect(viewport.left, viewport.top, viewport.left + width * inverseScale,
            viewport.top + height * inverseScale).inflate(maxOf(0f, inverseScale - 1f))
        val query = drawingViewport.inflate(1f)
        repeat(4) { corner ->
            val x = if (corner and 1 == 0) query.left else query.right
            val y = if (corner and 2 == 0) query.top else query.bottom
            require((inverse.m00 * x + inverse.m10 * y + inverse.m20).isFinite())
            require((inverse.m01 * x + inverse.m11 * y + inverse.m21).isFinite())
        }
        val requested = View(viewport, transform, scale)
        fun sameLayer(first: InkSceneLayer, second: InkSceneLayer): Boolean =
            first.scene === second.scene && first.excluded.size == second.excluded.size && first.excluded.all { it in second.excluded }
        requests++
        lastDrawn = 0
        lastUpdatedPixels = 0L
        val cacheable = maxViews > 0 && bytes <= pixelBudgetBytes
        val sameContent = this.renderer === renderer && rendererVersion == renderer.renderVersion &&
            (layers === next || layers.size == next.size && layers.indices.all { sameLayer(layers[it], next[it]) })
        if (!sameContent) clearPreviousViews()
        if (!cacheable) clearCache()
        else {
            if (sameContent && view != requested) {
                image?.let { previous ->
                    previousViews[checkNotNull(view)] = SavedRaster(previous, currentPixelBytes)
                    previousPixelBytes += currentPixelBytes
                }
                image = null
                view = null
                currentPixelBytes = 0L
                previousViews.remove(requested)?.let { saved ->
                    image = saved.image
                    view = requested
                    currentPixelBytes = saved.bytes
                    previousPixelBytes -= saved.bytes
                }
            }
            // Reserve the requested view before allocating a replacement surface. The retained
            // image budget excludes the temporary surface used by append/damage updates.
            while (previousViews.size >= maxViews || previousPixelBytes > pixelBudgetBytes - bytes) {
                val oldest = previousViews.entries.iterator()
                val retired = oldest.next().value
                oldest.remove()
                previousPixelBytes -= retired.bytes
                retired.image.close()
                capacityEvictions++
            }
        }
        val reusable = cacheable && image != null && view == requested && this.renderer === renderer &&
            rendererVersion == renderer.renderVersion
        val append = reusable && (layers === next || layers.size <= next.size &&
            layers.indices.all { sameLayer(layers[it], next[it]) })
        val damage = if (reusable && !append && layers.size == next.size)
            damageBetween(layers, next, transform, viewport, width, height, scale) else null
        if (damage != null && damage.isEmpty) {
            layers = next
            lastDrawn = 0
            hits++
        } else
        if (!append || layers.size != next.size) {
            val prior = image.takeIf { append || damage != null }
            val firstLayer = if (append) layers.size else 0
            if (prior == null) clearCurrentView()
            var drawn = 0
            val snapshot = Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(0)
                if (prior != null && damage == null) Paint().use { copy ->
                    copy.blendMode = BlendMode.SRC
                    copy.isAntiAlias = false
                    surface.canvas.drawImage(prior, 0f, 0f, copy)
                }
                val checkpoint = surface.canvas.save()
                surface.canvas.scale(scale, scale)
                surface.canvas.translate(-viewport.left, -viewport.top)
                surface.canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
                for (index in firstLayer until next.size) {
                    val layer = next[index]
                    drawn += if (damage == null)
                        layer.scene.draw(surface.canvas.asComposeCanvas(), renderer, transform, drawingViewport, layer.excluded)
                    else {
                        val dirtyViewport = Rect(viewport.left + damage.left * inverseScale,
                            viewport.top + damage.top * inverseScale, viewport.left + damage.right * inverseScale,
                            viewport.top + damage.bottom * inverseScale).inflate(maxOf(0f, inverseScale - 1f))
                        drawDamaged(layer, surface, renderer, transform, dirtyViewport, drawingViewport)
                    }
                }
                surface.canvas.restoreToCount(checkpoint)
                if (damage != null && prior != null) Paint().use { copy ->
                    copy.blendMode = BlendMode.SRC
                    copy.isAntiAlias = false
                    // A smaller vector clip changes Skia's path AA at its edges. Keep the original
                    // full-viewport rasterization, then copy unchanged integer pixels back exactly.
                    val outside = listOf(Rect(0f, 0f, damage.left, height.toFloat()),
                        Rect(damage.right, 0f, width.toFloat(), height.toFloat()),
                        Rect(damage.left, 0f, damage.right, damage.top),
                        Rect(damage.left, damage.bottom, damage.right, height.toFloat()))
                    for (strip in outside) if (!strip.isEmpty) {
                        val saved = surface.canvas.save()
                        try {
                            surface.canvas.clipRect(strip.left, strip.top, strip.right, strip.bottom, false)
                            surface.canvas.drawImage(prior, 0f, 0f, copy)
                        } finally { surface.canvas.restoreToCount(saved) }
                    }
                }
                surface.makeImageSnapshot()
            }
            image?.close()
            image = snapshot
            view = requested
            // The producer retains this immutable list until a segment or exclusion changes.
            layers = next
            this.renderer = renderer
            rendererVersion = renderer.renderVersion
            currentPixelBytes = bytes
            rasterBuildCount++
            lastDrawn = drawn
            lastUpdatedPixels = if (damage == null) pixels else
                damage.width.toLong() * damage.height.toLong()
            if (damage != null) dirtyBuildCount++ else if (append && prior != null) appendBuilds++ else fullBuilds++
            vectors += drawn
            allocatedPixels += pixels
            updatedPixels += lastUpdatedPixels
        } else {
            hits++
        }
        val destination = canvas.skiaCanvas
        val checkpoint = destination.save()
        val drawn = lastDrawn
        try {
            try {
                destination.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
                destination.translate(viewport.left, viewport.top)
                destination.scale(inverseScale, inverseScale)
                destination.drawImage(checkNotNull(image), 0f, 0f)
            } finally { destination.restoreToCount(checkpoint) }
        } finally { if (!cacheable) clearCache() }
        return drawn
    }

    fun work() = InkViewportRasterWork(requests, hits, fullBuilds, appendBuilds, dirtyBuildCount,
        vectors, allocatedPixels, updatedPixels, capacityEvictions, retainedViews, retainedPixelBytes)

    private fun clearPreviousViews() {
        previousViews.values.forEach { it.image.close() }
        previousViews.clear()
        previousPixelBytes = 0L
    }

    private fun clearCurrentView() {
        image?.close()
        image = null
        view = null
        currentPixelBytes = 0L
        lastDrawn = 0
        lastUpdatedPixels = 0L
    }

    fun clearCache() {
        clearPreviousViews()
        clearCurrentView()
        layers = emptyList()
        renderer = null
    }

    override fun close() { clearCache(); closed = true }
}

private fun drawDamaged(layer: InkSceneLayer, surface: Surface, renderer: InkPathRenderer,
    transform: ImmutableAffineTransform, dirtyViewport: Rect, drawingViewport: Rect): Int {
    val strokeTransform = MutableAffineTransform()
    var drawn = 0
    for (item in layer.scene.visibleStrokes(dirtyViewport, transform)) {
        if (item in layer.excluded) continue
        AffineTransform.multiply(transform, item.strokeToScene, strokeTransform)
        if (renderer.render(surface.canvas.asComposeCanvas(), item.stroke, strokeTransform, drawingViewport, item.colorArgb)) drawn++
    }
    return drawn
}

private fun damageBetween(previous: List<InkSceneLayer>, next: List<InkSceneLayer>,
    sceneToCanvas: ImmutableAffineTransform, viewport: Rect, width: Int, height: Int, scale: Float): Rect? {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    var roundingError = 0.0
    val transform = MutableAffineTransform()
    fun include(item: InkSceneStroke): Boolean {
        val box = item.stroke.shape.computeBoundingBox() ?: return true
        AffineTransform.multiply(sceneToCanvas, item.strokeToScene, transform)
        for (x in listOf(box.xMin, box.xMax)) for (y in listOf(box.yMin, box.yMax)) {
            val px = (transform.m00 * x + transform.m10 * y + transform.m20 - viewport.left) * scale
            val py = (transform.m01 * x + transform.m11 * y + transform.m21 - viewport.top) * scale
            if (!px.isFinite() || !py.isFinite()) return false
            left = minOf(left, px); top = minOf(top, py)
            right = maxOf(right, px); bottom = maxOf(bottom, py)
            val stroke = item.strokeToScene
            val sceneX = kotlin.math.abs(stroke.m00.toDouble() * x) + kotlin.math.abs(stroke.m10.toDouble() * y) + kotlin.math.abs(stroke.m20.toDouble())
            val sceneY = kotlin.math.abs(stroke.m01.toDouble() * x) + kotlin.math.abs(stroke.m11.toDouble() * y) + kotlin.math.abs(stroke.m21.toDouble())
            val termsX = kotlin.math.abs(sceneToCanvas.m00.toDouble()) * sceneX + kotlin.math.abs(sceneToCanvas.m10.toDouble()) * sceneY +
                kotlin.math.abs(sceneToCanvas.m20.toDouble()) + kotlin.math.abs(viewport.left.toDouble())
            val termsY = kotlin.math.abs(sceneToCanvas.m01.toDouble()) * sceneX + kotlin.math.abs(sceneToCanvas.m11.toDouble()) * sceneY +
                kotlin.math.abs(sceneToCanvas.m21.toDouble()) + kotlin.math.abs(viewport.top.toDouble())
            // Bound float error by the expanded absolute terms, not the possibly tiny result of
            // cancellation. Compose/Skia may associate the equivalent affine products differently.
            roundingError = maxOf(roundingError, maxOf(termsX, termsY) * scale / 262_144.0)
        }
        return true
    }
    for (index in previous.indices) {
        val old = previous[index]
        val current = next[index]
        if (old === current) continue
        if (old.scene === current.scene) {
            for (item in old.excluded) if (item !in current.excluded && !include(item)) return null
            for (item in current.excluded) if (item !in old.excluded && !include(item)) return null
        } else {
            for (item in old.scene.strokes) if (item !in old.excluded && !include(item)) return null
            for (item in current.scene.strokes) if (item !in current.excluded && !include(item)) return null
        }
    }
    if (left == Float.POSITIVE_INFINITY) return Rect.Zero
    // Raster-space integer clipping leaves the cached pixels outside the region untouched. Cover
    // Skia's antialiasing fringe and float composition error before rounding outwards.
    val magnitude = maxOf(kotlin.math.abs(left), kotlin.math.abs(top), kotlin.math.abs(right), kotlin.math.abs(bottom))
    val padding = 2f + scale + maxOf(Math.ulp(magnitude) * 32f, roundingError.toFloat())
    if (!padding.isFinite()) return null
    val result = Rect(floor((left - padding).toDouble()).coerceIn(0.0, width.toDouble()).toFloat(),
        floor((top - padding).toDouble()).coerceIn(0.0, height.toDouble()).toFloat(),
        ceil((right + padding).toDouble()).coerceIn(0.0, width.toDouble()).toFloat(),
        ceil((bottom + padding).toDouble()).coerceIn(0.0, height.toDouble()).toFloat())
    return result.takeUnless { !it.isEmpty && it.width * it.height >= width.toDouble() * height * .8 }
}
