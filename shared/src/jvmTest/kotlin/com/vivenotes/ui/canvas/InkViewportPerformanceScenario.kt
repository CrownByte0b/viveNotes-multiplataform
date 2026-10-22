package com.vivenotes.ui.canvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.performance.BaselineMeasurements
import com.vivenotes.performance.fingerprint
import com.vivenotes.performance.performanceInkAppendFixture
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.test.*

class InkViewportPerformanceScenario {
    @Test
    fun viewportSequencesMatchFreshRastersAndReportActualWork() {
        org.junit.Assume.assumeTrue(System.getenv("VIVE_VIEWPORT_BENCHMARK") == "1")
        @OptIn(InternalComposeUiApi::class)
        registerSkikoComposeImplementation()
        val output = File(requireNotNull(System.getenv("VIVE_VIEWPORT_REPORT")))
        require(!output.exists())
        val native = InkRuntime.load()
        val fixture = performanceInkAppendFixture(40_000)
        val page = fixture.initial.desktopGeometry()
        val meshes = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        page.sourceStrokes.forEach { meshes += it.stroke.shape }
        assertEquals(40_000, meshes.size)
        val scene = InkScene(page.projections.map { InkSceneStroke(it.stroke, it.strokeToPageTransform()) })
        val layers = listOf(InkSceneLayer(scene, emptySet()))
        val runner = BaselineMeasurements(minWarmupOps = 8, samples = 7)
        for (density in listOf(1f, 2f)) for (mode in listOf("fit", "sparse")) {
            val scale = if (mode == "fit") 512f / (density * 2_400f) else 1.25f
            val transform = ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
            val sequences = linkedMapOf(
                "stationary" to List(8) { view(scale, 0f) },
                "pan_new" to List(8) { view(scale, it * 24f) },
                "pan_return" to List(8) { view(scale, (it % 2) * 24f) },
                "zoom_return" to listOf(1f, 1.25f, 1.5f, 1.25f, 1f, 1.25f, 1.5f, 1f).map { view(scale * it, 0f) },
                "fractional_pan" to List(8) { view(scale, it * 3.75f) },
            )
            for ((name, views) in sequences) {
                val renderer = InkPathRenderer()
                try {
                    SegmentedInkRasterCache().use { cache ->
                        for (view in views) {
                            val actual = pixels(view) { surface ->
                                cache.draw(surface.canvas.asComposeCanvas(), layers, renderer, view.rect, transform, view.scale)
                            }
                            val expected = InkSceneRasterCache().use { fresh -> pixels(view) { surface ->
                                fresh.draw(surface.canvas.asComposeCanvas(), scene, renderer, view.rect, transform, view.scale)
                            } }
                            assertContentEquals(expected, actual, "$name density=$density mode=$mode view=$view")
                        }
                        var builds = 0L
                        var vectors = 0L
                        var allocatedPixels = 0L
                        var updatedPixels = 0L
                        var requests = 0L
                        var hits = 0L
                        var peakRetained = 0L
                        val pathStart = renderer.pathBuildCount
                        Surface.makeRasterN32Premul(512, 512).use { surface ->
                            val canvas = surface.canvas.asComposeCanvas()
                            val hash = fingerprint("viewport-v1:${fixture.fingerprint127}:$density:$mode:$name:$views")
                            runner.measure("ink.viewport_${name}_d${density.toInt()}_$mode", 40_000, hash, fixedOps = 1) {
                                cache.clearCache()
                                views.forEach { view ->
                                    requests++
                                    surface.canvas.clear(0)
                                    val save = surface.canvas.save()
                                    try {
                                        surface.canvas.scale(view.scale, view.scale)
                                        surface.canvas.translate(-view.rect.left, -view.rect.top)
                                        val before = cache.rasterBuildCount
                                        val drawn = cache.draw(canvas, layers, renderer, view.rect, transform, view.scale)
                                        val count = cache.rasterBuildCount - before
                                        builds += count
                                        if (count == 0L) hits++ else {
                                            vectors += drawn
                                            allocatedPixels += pixelsIn(view) * count
                                            updatedPixels += cache.lastUpdatedPixels
                                        }
                                        peakRetained = maxOf(peakRetained, cache.retainedPixelBytes)
                                    } finally { surface.canvas.restoreToCount(save) }
                                }
                                builds
                            }
                        }
                        val work = buildJsonObject {
                            put("requests", requests); put("rasterBuilds", builds); put("cacheHits", hits)
                            put("finishedStrokeDraws", vectors); put("allocatedSurfacePixels", allocatedPixels)
                            put("updatedPixels", updatedPixels); put("pathBuilds", renderer.pathBuildCount - pathStart)
                            put("peakRetainedPixelBytes", peakRetained)
                            put("exactFreshRasterPixels", true)
                            put("counterScope", "All eight warmup and seven measured eight-view sequences; each sequence clears the cache first; validation excluded")
                        }
                        val last = runner.results.lastIndex
                        runner.results[last] = JsonObject(runner.results[last] + work)
                    }
                } finally { renderer.clearCache() }
            }
        }
        val report = buildJsonObject {
            put("schema", "vive-viewport-raster/1")
            put("runtime", System.getProperty("java.runtime.version")); put("vendor", System.getProperty("java.vendor"))
            put("os", System.getProperty("os.name")); put("nativeSha256", native.sha256)
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            put("vmArgs", JsonArray(java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("timingScope", "Warm synchronous software raster sequences including cache clear, surface clear, image blits and view changes; setup and PNG validation excluded; no UI scheduling, GPU, FPS or physical input latency")
            put("pixelScope", "Allocated surface pixels and changed pixel rectangles, not Skia fragment work or total native memory; excludes destination surface and transient copies")
            put("cases", JsonArray(runner.results))
        }
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
    }

    private data class View(val rect: Rect, val scale: Float)
    private fun view(scale: Float, physicalX: Float) = View(Rect((17.25f + physicalX) / scale,
        29.75f / scale, (17.25f + physicalX + 512f) / scale, (29.75f + 512f) / scale), scale)

    private fun pixelsIn(view: View): Long =
        ceil((view.rect.right.toDouble() - view.rect.left) * view.scale).toLong() *
            ceil((view.rect.bottom.toDouble() - view.rect.top) * view.scale).toLong()

    private fun pixels(view: View, draw: (Surface) -> Unit): ByteArray = Surface.makeRasterN32Premul(
        ceil((view.rect.right.toDouble() - view.rect.left) * view.scale).toInt(),
        ceil((view.rect.bottom.toDouble() - view.rect.top) * view.scale).toInt()).use { surface ->
        surface.canvas.clear(0xffffffff.toInt())
        surface.canvas.scale(view.scale, view.scale)
        surface.canvas.translate(-view.rect.left, -view.rect.top)
        draw(surface)
        surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
    }
}
