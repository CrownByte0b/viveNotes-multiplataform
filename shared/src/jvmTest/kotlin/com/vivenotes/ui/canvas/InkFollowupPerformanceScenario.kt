package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.kit.*
import com.vivenotes.ink.DesktopInkPage
import com.vivenotes.ink.InkProjectionWork
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

/** Separate opt-in CPU diagnostics. It never changes or writes the frozen three-fork harness. */
class InkFollowupPerformanceScenario {
    @Test
    fun pairedCanonicalReplayAndFullRasterReferences() {
        org.junit.Assume.assumeTrue(System.getenv("VIVE_INK_FOLLOWUP") == "1")
        @OptIn(InternalComposeUiApi::class)
        registerSkikoComposeImplementation()
        val target = File(requireNotNull(System.getenv("VIVE_INK_FOLLOWUP_REPORT")))
        require(!target.exists()) { "Choose a new followup report path; existing captures are immutable" }
        require(!Regex("/benchmarks/results/(2026-10-21|2026-10-21-optimized|2026-10-21-optimized-final)(/|$)")
            .containsMatchIn(target.canonicalPath.replace(File.separatorChar, '/'))) { "Frozen capture directories cannot receive followup reports" }
        val native = InkRuntime.load()
        val runner = BaselineMeasurements(minWarmupOps = 8, samples = 7)
        var largest: DesktopInkPage? = null
        var largestHash = ""
        for (count in listOf(1_000, 10_000, 40_000)) {
            val fixture = performanceInkAppendFixture(count)
            val initial = fixture.initial.desktopGeometry()
            val distinct = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            initial.sourceStrokes.forEach { distinct.add(it.stroke.shape) }
            assertEquals(count, distinct.size)
            val history = (0 until 16).map { erase(initial, it * 2, "history-$it", it + 1L) }
            val page = DesktopInkPage(ViveInkPage.replay(initial.sourceStrokes, history), initial.base.sourceIds,
                initial.sourceStrokes, history)
            val masks = List(8) { erase(initial, count / 2 + it, "current-preview", 100L) }
            val operations = masks.map { history + it }
            val hash = fingerprint("local-normal-v1:${fixture.fingerprint127}:history16:preview8:targets=mid+0..7")
            masks.forEachIndexed { index, operation ->
                val work = InkProjectionWork()
                val local = page.withNormalErase(operation, work)
                val full = ViveInkPage.replay(page.sourceStrokes, operations[index])
                assertEquals(full.map { it.id to it.pageBounds }, local.projections.map { it.id to it.pageBounds })
                assertTrue(work.targetProjections <= 4)
                assertTrue(work.rebuiltProjections <= 132)
            }
            // Both paired cases include producing new native page/index snapshots, with identical
            // decoded sources, operations and eight distinct current masks; fixture setup is excluded.
            runner.measure("ink.normal_preview8_full_replay_reference", count, hash, fixedOps = 1) {
                var result: DesktopInkPage? = null
                operations.forEach { ops -> result = DesktopInkPage(ViveInkPage.replay(page.sourceStrokes, ops),
                    page.base.sourceIds, page.sourceStrokes, ops) }
                result
            }
            runner.measure("ink.normal_preview8_local_projection", count, hash, fixedOps = 1) {
                var result: DesktopInkPage? = null
                masks.forEach { result = page.withNormalErase(it) }
                result
            }
            largest = initial
            largestHash = fixture.fingerprint127
        }
        val page = checkNotNull(largest)
        for (density in listOf(1f, 2f)) for (mode in listOf("fit", "sparse")) {
            val scale = if (mode == "fit") 512f / (density * 2_400f) else 1.25f
            val viewport = Rect(17.25f / scale, 29.75f / scale, (17.25f + 512f) / scale, (29.75f + 512f) / scale)
            val transform = ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
            val scene = InkScene(page.projections.map { InkSceneStroke(it.stroke, it.strokeToPageTransform()) })
            val visible = scene.visibleStrokes(viewport, transform).filter { item ->
                val box = item.stroke.shape.computeBoundingBox() ?: return@filter false
                val x = (box.xMin + box.xMax) / 2f * density
                val y = (box.yMin + box.yMax) / 2f * density
                x > viewport.left + 4f / scale && x < viewport.right - 4f / scale &&
                    y > viewport.top + 4f / scale && y < viewport.bottom - 4f / scale
            }
            assertTrue(visible.size >= 8)
            val exclusions = List(8) { index -> visible.take(index + 1).toSet() }
            val layers = exclusions.map { listOf(InkSceneLayer(scene, it)) }
            val hash = fingerprint("dirty-object-v1:$largestHash:$density:$mode:512px:scroll17.25,29.75:exclude-first-visible1..8")
            InkPathRenderer().let { renderer ->
                try {
                    InkSceneRasterCache().use { full -> SegmentedInkRasterCache().use { dirty ->
                        fun drawFull(set: Set<InkSceneStroke>): ByteArray = image(viewport, scale) {
                            full.draw(it.canvas.asComposeCanvas(), scene, renderer, viewport, transform, scale, set)
                        }
                        fun drawDirty(items: List<InkSceneLayer>): ByteArray = image(viewport, scale) {
                            dirty.draw(it.canvas.asComposeCanvas(), items, renderer, viewport, transform, scale)
                        }
                        drawFull(emptySet()); drawDirty(listOf(InkSceneLayer(scene, emptySet())))
                        exclusions.indices.forEach { assertContentEquals(drawFull(exclusions[it]), drawDirty(layers[it])) }
                        val width = ceil((viewport.right.toDouble() - viewport.left.toDouble()) * scale).toInt()
                        val height = ceil((viewport.bottom.toDouble() - viewport.top.toDouble()) * scale).toInt()
                        Surface.makeRasterN32Premul(width, height).use { destination ->
                            destination.canvas.scale(scale, scale)
                            destination.canvas.translate(-viewport.left, -viewport.top)
                            val canvas = destination.canvas.asComposeCanvas()
                            val fullStart = full.rasterBuildCount
                            val pathsStart = renderer.pathBuildCount
                            var fullVectors = 0L
                            runner.measure("ink.object_raster8_full_reference_d${density.toInt()}_$mode", 40_000, hash, fixedOps = 1) {
                                var drawn = 0
                                exclusions.forEach { drawn += full.draw(canvas, scene, renderer, viewport, transform, scale, it) }
                                fullVectors += drawn
                                drawn
                            }
                            counters(runner, full.rasterBuildCount - fullStart, renderer.pathBuildCount - pathsStart,
                                full.retainedPixelBytes, fullVectors, null, null)
                            val dirtyStart = dirty.rasterBuildCount
                            val dirtyPathsStart = renderer.pathBuildCount
                            val damageStart = dirty.dirtyBuildCount
                            var dirtyVectors = 0L
                            var changedPixels = 0L
                            runner.measure("ink.object_raster8_dirty_d${density.toInt()}_$mode", 40_000, hash, fixedOps = 1) {
                                var drawn = 0
                                layers.forEach {
                                    val before = dirty.rasterBuildCount
                                    drawn += dirty.draw(canvas, it, renderer, viewport, transform, scale)
                                    if (before != dirty.rasterBuildCount) changedPixels += dirty.lastUpdatedPixels
                                }
                                dirtyVectors += drawn
                                drawn
                            }
                            counters(runner, dirty.rasterBuildCount - dirtyStart, renderer.pathBuildCount - dirtyPathsStart,
                                dirty.retainedPixelBytes, dirtyVectors, dirty.dirtyBuildCount - damageStart, changedPixels)
                        }
                    } }
                } finally { renderer.clearCache() }
            }
        }
        val report = buildJsonObject {
            put("schema", "vive-desktop-ink-followup/1")
            put("runtime", System.getProperty("java.runtime.version"))
            put("vendor", System.getProperty("java.vendor"))
            put("vm", System.getProperty("java.vm.name"))
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            put("vmArgs", JsonArray(java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("nativeSha256", native.sha256)
            put("nativeOrigin", native.origin.name)
            put("referenceScope", "canonical public ByteInk replay/global page rebuild and original native full-viewport raster; same process paired references, not the historical frozen capture")
            put("timingScope", "warm synchronous CPU/software work for eight distinct masks or eight raster updates; fixture setup and PNG validation excluded; scheduling/input-to-photon not measured")
            put("memoryScope", "caller-thread JVM allocations; raster retained pixels exclude transient copy surfaces/native path and mesh memory")
            put("cases", JsonArray(runner.results))
        }
        target.absoluteFile.parentFile.mkdirs()
        target.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("Separate ink followup report: ${target.absolutePath}")
    }

    private fun erase(page: DesktopInkPage, index: Int, id: String, createdAt: Long): DecodedInkOperation.Erase {
        val box = page.sourceStrokes[index].pageBounds!!
        val input = MutableStrokeInputBatch().apply { add(InputToolType.MOUSE, box.center.x, box.center.y, 0L) }
        val mask = ViveBrushes.eraseMask(input, 3f)
        val stored = ViveInkCodec.encodeErase(mask, id, "performance-append-page", InkEraseMode.Normal,
            createdAt, listOf(page.sourceStrokes[index].id))
        return ViveInkPage.load(emptyList(), listOf(stored), emptyList()).operations.single() as DecodedInkOperation.Erase
    }

    private fun counters(runner: BaselineMeasurements, rasters: Long, paths: Long, retained: Long,
        vectors: Long, dirty: Long?, updatedPixels: Long?) {
        val extra = buildJsonObject {
            put("workCounterScope", "all warmup and measured operations; fixture validation excluded")
            put("rasterBuilds", rasters); put("pathBuilds", paths); put("retainedPixelBytes", retained)
            put("finishedStrokeDraws", vectors)
            dirty?.let { put("dirtyBuilds", it) }
            updatedPixels?.let { put("updatedPixels", it) }
        }
        runner.results[runner.results.lastIndex] = JsonObject(runner.results.last() + extra)
    }

    private fun image(viewport: Rect, scale: Float, draw: (Surface) -> Unit): ByteArray =
        Surface.makeRasterN32Premul(ceil((viewport.right.toDouble() - viewport.left.toDouble()) * scale).toInt(),
            ceil((viewport.bottom.toDouble() - viewport.top.toDouble()) * scale).toInt()).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            surface.canvas.scale(scale, scale); surface.canvas.translate(-viewport.left, -viewport.top)
            draw(surface)
            surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
        }
}
