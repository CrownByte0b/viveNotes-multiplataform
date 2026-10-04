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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.InkEdit
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toByteInk
import com.vivenotes.ink.toEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.InkTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CPU/software draw diagnostics, not a physical input-to-photon measurement or a timing gate. */
@OptIn(ExperimentalTestApi::class)
class InkLayerPerformanceScenario {
    @Test
    fun rawFinished40kPageWetFinishErase() {
        if (!java.lang.Boolean.getBoolean("vivenotes.ink.phase2Scenario")) return
        val library = InkRuntime.load()
        val createStart = System.nanoTime()
        val fixture = rawPage()
        val fixtureNanos = System.nanoTime() - createStart
        val meshes = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fixture.desktopGeometry().projections.forEach { meshes += it.stroke.shape }
        assertEquals(PAGE_STROKES, fixture.desktopGeometry().projections.size)
        assertEquals(PAGE_STROKES, meshes.size, "the workload must exercise distinct finished meshes")
        val reports = buildList {
            for (density in listOf(1f, 2f)) {
                for (view in listOf("fit", "sparse")) {
                    val zoom = if (view == "fit") VIEWPORT_PX / (density * PAGE_DP) else 1.25f
                    for (additions in listOf(1, 32)) add(runCase(fixture, density, zoom, view, additions))
                }
            }
        }
        val report = buildJsonObject {
            put("schema", "vive-ink-phase2-ui/1")
            put("runtime", System.getProperty("java.runtime.version"))
            put("runtimeVendor", System.getProperty("java.vendor"))
            put("os", System.getProperty("os.name"))
            put("nativeSha256", library.sha256)
            put("nativeOrigin", library.origin.name)
            put("fixture", "40k canonical stored short strokes, 200x200 grid, distinct native meshes")
            put("fixtureEncodeAndReadNanos", fixtureNanos)
            put("timingScope", "pointer-handler start to next Canvas draw completion; may precede processing queued samples; includes scheduling and GC")
            put("pageCountScope", "observed page geometry snapshot identities, not constructor instrumentation")
            put("sceneCountScope", "InkScene construction, each constructs one spatial index")
            put("pendingAdditionScope", "authored canonical rows retained in page and queued edits without a storage acknowledgement")
            put("cases", JsonArray(reports))
        }
        val target = File(System.getProperty("vivenotes.ink.phase2Report",
            "build/reports/ink-phase2-scenario.json"))
        target.parentFile.mkdirs()
        target.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
        println("Ink scenario report: ${target.absolutePath}")
    }

    private fun runCase(initial: InkPage, density: Float, zoom: Float, view: String,
        additions: Int): JsonObject {
        lateinit var report: JsonObject
        runDesktopComposeUiTest(width = VIEWPORT_PX, height = VIEWPORT_PX) {
            val page = mutableStateOf(initial)
            val tool = mutableStateOf<InkTool?>(InkTool.Pen)
            val diagnostics = InkLayerDiagnostics()
            val edits = mutableListOf<InkEdit>()
            var pageSnapshots = 1
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density),
                    LocalInkLayerDiagnostics provides diagnostics) {
                    ScenarioViewport(zoom, density) {
                        InkLayer(page.value, DpSize(PAGE_DP.dp, PAGE_DP.dp), Color.Black,
                            visibleWindow = { Rect(SCROLL_X / zoom, SCROLL_Y / zoom,
                                (SCROLL_X + VIEWPORT_PX) / zoom, (SCROLL_Y + VIEWPORT_PX) / zoom) },
                            tool = tool.value, zoom = zoom, onEdit = { edit, updated ->
                                if (updated.geometry !== page.value.geometry) pageSnapshots++
                                edits += edit
                                page.value = updated
                            })
                    }
                }
            }
            val frame = onNodeWithTag(FRAME_TAG)
            assertTrue(frame.captureToImage().toPixelMap().let { pixels ->
                (0 until pixels.height step 16).any { y ->
                    (0 until pixels.width step 16).any { x -> pixels[x, y] != Color.White }
                }
            }, "the scrolled page is drawn")
            val initialCounts = diagnostics.counts()
            var wetCounts = zeroCounts()
            var finishCounts = zeroCounts()
            repeat(additions) { index ->
                val y = 80f + index * 9f
                val beforeWet = diagnostics.counts()
                frame.performMouseInput {
                    updatePointerTo(Offset(48f, y)); press()
                    moveTo(Offset(220f, y), delayMillis = 32)
                    moveTo(Offset(450f, y), delayMillis = 48)
                }
                frame.captureToImage()
                wetCounts += diagnostics.counts() - beforeWet
                val beforeFinish = diagnostics.counts()
                frame.performMouseInput { release() }
                frame.captureToImage()
                finishCounts += diagnostics.counts() - beforeFinish
            }
            runOnIdle {
                assertEquals(additions, edits.filterIsInstance<InkEdit.AddStroke>().size)
                assertEquals(PAGE_STROKES + additions, page.value.desktopGeometry().projections.size)
                val added = edits.filterIsInstance<InkEdit.AddStroke>().last().row
                val reloaded = assertNotNull(ViveInkCodec.decode(added.toByteInk()))
                assertTrue(reloaded.inputs.size >= 3)
                assertEquals((450f + SCROLL_X) / zoom / density,
                    reloaded.inputs[reloaded.inputs.size - 1].x, 0.02f)
            }
            // A held page remains unchanged while the next wet gesture advances across frames.
            frame.performMouseInput { updatePointerTo(Offset(48f, 400f)); press() }
            frame.captureToImage()
            val beforeHeld = diagnostics.counts()
            repeat(6) { index ->
                frame.performMouseInput { moveTo(Offset(110f + index * 60f, 400f), delayMillis = 16) }
                frame.captureToImage()
            }
            val heldCounts = diagnostics.counts() - beforeHeld
            // Switching tools cancels this unfinished pen before the real erase gesture.
            runOnIdle { tool.value = InkTool.Eraser }
            frame.performMouseInput { release() }
            val beforeErase = diagnostics.counts()
            frame.performMouseInput { updatePointerTo(Offset(48f, 440f)); press() }
            frame.captureToImage()
            repeat(4) { index ->
                frame.performMouseInput { moveTo(Offset(140f + index * 100f, 440f), delayMillis = 16) }
                frame.captureToImage()
            }
            val erasePreviewCounts = diagnostics.counts() - beforeErase
            val beforeEraseFinish = diagnostics.counts()
            frame.performMouseInput { release() }
            frame.captureToImage()
            val eraseFinishCounts = diagnostics.counts() - beforeEraseFinish
            runOnIdle {
                val erase = assertNotNull(edits.filterIsInstance<InkEdit.EraseStrokes>().singleOrNull())
                assertTrue(erase.ids.isNotEmpty())
                assertEquals(PAGE_STROKES + additions - erase.ids.size,
                    page.value.desktopGeometry().projections.size)
                assertEquals(additions + 2, pageSnapshots)
                if (java.lang.Boolean.getBoolean("vivenotes.ink.phase2Optimized")) {
                    assertEquals(0L, heldCounts.scenes)
                    assertEquals(0L, heldCounts.rasters)
                    assertEquals(0L, heldCounts.finishedPaths)
                    assertEquals(0L, heldCounts.finishedStrokeDraws)
                    assertEquals(0L, erasePreviewCounts.scenes)
                    assertTrue(diagnostics.retainedPixelBytes in (512L * 512L * 4L)..(2L * 513L * 513L * 4L))
                }
                report = buildJsonObject {
                    put("view", view); put("density", density); put("zoom", zoom)
                    put("viewportPx", VIEWPORT_PX); put("scrollXPx", SCROLL_X); put("scrollYPx", SCROLL_Y)
                    put("finishedPageStrokes", PAGE_STROKES); put("pendingAdditions", additions)
                    put("pageSnapshotIdentities", pageSnapshots); put("erasedRows", erase.ids.size)
                    put("retainedRasterPixelBytes", diagnostics.retainedPixelBytes)
                    put("peakRetainedRasterPixelBytes", diagnostics.peakRetainedPixelBytes)
                    put("fullPageSceneBuilds", diagnostics.baseSceneBuilds)
                    put("initial", initialCounts.json()); put("wet", wetCounts.json())
                    put("finish", finishCounts.json()); put("heldWet", heldCounts.json())
                    put("erasePreview", erasePreviewCounts.json()); put("eraseFinish", eraseFinishCounts.json())
                    put("total", diagnostics.counts().json())
                    put("inputHandlerToDrawNanos", JsonArray(diagnostics.inputToDrawNanos.map(::JsonPrimitive)))
                }
            }
        }
        return report
    }

    private fun rawPage(): InkPage {
        val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff668877.toInt(), 2f)
        val rows = List(PAGE_STROKES) { index ->
            val x = index % 200 * CELL_DP
            val y = index / 200 * CELL_DP
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, x + 1f, y + 3f, 0L)
                add(InputToolType.MOUSE, x + 5f, y + 6f, 10L)
                add(InputToolType.MOUSE, x + 9f, y + 3f, 20L)
            }
            ViveInkCodec.encodeStroke(Stroke(brush, inputs), "fixture-$index", PAGE_ID, index,
                ViveBrushes.MARKER, 0, false, 0L).toEntity()
        }
        return InkPageReader.read(PAGE_ID, rows, emptyList(), emptyList())
    }

    @Composable
    private fun ScenarioViewport(zoom: Float, density: Float, content: @Composable () -> Unit) {
        Box(Modifier.size((VIEWPORT_PX / density).dp).clipToBounds().background(Color.White).testTag(FRAME_TAG)) {
            Layout(content = content) { measurables, _ ->
                val child = measurables.single().measure(Constraints())
                layout(VIEWPORT_PX, VIEWPORT_PX) {
                    child.placeWithLayer(0, 0) {
                        scaleX = zoom; scaleY = zoom
                        translationX = -SCROLL_X; translationY = -SCROLL_Y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    }

    private fun InkLayerCounts.json() = buildJsonObject {
        put("sceneAndSceneIndexBuilds", scenes); put("draws", draws); put("pathBuilds", paths)
        put("finishedPathBuilds", finishedPaths); put("rasterBuilds", rasters)
        put("finishedStrokeVectorDraws", finishedStrokeDraws); put("shapeAdvances", shapeAdvances)
        put("observations", observations)
    }

    private operator fun InkLayerCounts.plus(other: InkLayerCounts) = InkLayerCounts(
        scenes + other.scenes, draws + other.draws, paths + other.paths, finishedPaths + other.finishedPaths,
        rasters + other.rasters, finishedStrokeDraws + other.finishedStrokeDraws,
        shapeAdvances + other.shapeAdvances, observations + other.observations)

    private fun zeroCounts() = InkLayerCounts(0, 0, 0, 0, 0, 0, 0, 0)

    companion object {
        private const val PAGE_STROKES = 40_000
        private const val CELL_DP = 12f
        private const val PAGE_DP = CELL_DP * 200
        private const val VIEWPORT_PX = 512
        private const val SCROLL_X = 17.25f
        private const val SCROLL_Y = 29.75f
        private const val PAGE_ID = "performance-page"
        private const val FRAME_TAG = "ink-scenario-viewport"
    }
}
