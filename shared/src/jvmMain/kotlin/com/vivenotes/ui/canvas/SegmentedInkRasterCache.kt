package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import kotlin.math.ceil

internal class InkSceneLayer(val scene: InkScene, val excluded: Set<InkSceneStroke>)

/** One physical viewport for ordered immutable segments, incrementally extended at sealed tails. */
internal class SegmentedInkRasterCache : AutoCloseable {
    private data class View(val viewport: Rect, val transform: ImmutableAffineTransform, val scale: Float)
    private var image: Image? = null
    private var view: View? = null
    private var layers: List<InkSceneLayer> = emptyList()
    private var renderer: InkPathRenderer? = null
    private var rendererVersion = 0L
    private var closed = false
    var retainedPixelBytes = 0L
        private set
    var rasterBuildCount = 0L
        private set
    var lastDrawn = 0
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
        val append = bytes <= 64L * 1024 * 1024 && image != null && view == requested && this.renderer === renderer &&
            rendererVersion == renderer.renderVersion && (layers === next || layers.size <= next.size &&
            layers.indices.all { sameLayer(layers[it], next[it]) })
        if (!append || layers.size != next.size) {
            val prior = image.takeIf { append }
            val firstLayer = if (append) layers.size else 0
            if (!append) clearCache()
            var drawn = 0
            val snapshot = Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(0)
                if (prior != null) Paint().use { copy ->
                    copy.blendMode = BlendMode.SRC
                    copy.isAntiAlias = false
                    surface.canvas.drawImage(prior, 0f, 0f, copy)
                }
                surface.canvas.scale(scale, scale)
                surface.canvas.translate(-viewport.left, -viewport.top)
                surface.canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
                for (index in firstLayer until next.size) {
                    val layer = next[index]
                    drawn += layer.scene.draw(surface.canvas.asComposeCanvas(), renderer, transform, drawingViewport, layer.excluded)
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
            retainedPixelBytes = bytes
            rasterBuildCount++
            lastDrawn = drawn
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
        } finally { if (bytes > 64L * 1024 * 1024) clearCache() }
        return drawn
    }

    fun clearCache() {
        image?.close()
        image = null
        view = null
        layers = emptyList()
        renderer = null
        retainedPixelBytes = 0L
        lastDrawn = 0
    }

    override fun close() { clearCache(); closed = true }
}
