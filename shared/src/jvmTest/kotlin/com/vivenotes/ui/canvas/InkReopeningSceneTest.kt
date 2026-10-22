package com.vivenotes.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.data.LoadedInkCacheTest
import com.vivenotes.ink.InkPageReader
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.prepareCanvasScenes
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkReopeningSceneTest {
    @Test
    fun preparedScenesSurvivePageDisposalAndBackgroundSwitchWithExactPixels() = runDesktopComposeUiTest(width = 80, height = 80) {
        val row = LoadedInkCacheTest.row().copy(colorFollowsTheme = true)
        val page = InkPageReader.read("page", listOf(row), emptyList(), emptyList())
        Executors.newSingleThreadExecutor { r -> Thread(r, "ink-scene-preparation-test") }.asCoroutineDispatcher().use { worker ->
            runBlocking { withContext(worker) { page.desktopGeometry().prepareCanvasScenes() } }
        }
        val light = 0xff1b1b1b.toInt(); val dark = 0xffe6e6e6.toInt()
        val lightScene = page.desktopGeometry().base.sceneData(light)
        val darkScene = page.desktopGeometry().base.sceneData(dark)
        assertTrue(lightScene.preparationThread.startsWith("ink-scene-preparation-test"))
        assertTrue(darkScene.preparationThread.startsWith("ink-scene-preparation-test"))
        val visible = mutableStateOf(true)
        val color = mutableStateOf(light)
        val diagnostics = InkLayerDiagnostics()
        setContent {
            CompositionLocalProvider(LocalInkLayerDiagnostics provides diagnostics) {
                Box(Modifier.size(80.dp).background(Color.White).testTag("frame")) {
                    if (visible.value) InkLayer(page, DpSize(80.dp, 80.dp), Color(color.value))
                }
            }
        }
        val first = onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals(Color(light), first[20, 30])
        runOnIdle { visible.value = false }
        waitForIdle()
        assertEquals(0L, diagnostics.retainedPixelBytes)
        runOnIdle { visible.value = true }
        val reopened = onNodeWithTag("frame").captureToImage().toPixelMap()
        for (y in 0 until first.height) for (x in 0 until first.width) assertEquals(first[x, y], reopened[x, y])
        runOnIdle { color.value = dark }
        assertEquals(Color(dark), onNodeWithTag("frame").captureToImage().toPixelMap()[20, 30])
        assertEquals(0L, diagnostics.baseSceneBuilds)
        assertSame(lightScene, page.desktopGeometry().base.sceneData(light))
        assertSame(darkScene, page.desktopGeometry().base.sceneData(dark))
    }

    @Test
    fun preparedMapsUseExactSceneOccurrencesAndCustomColorsEvictWithinTwoVariants() {
        val page = InkPageReader.read("page", listOf(LoadedInkCacheTest.row()), emptyList(), emptyList())
        val segment = page.desktopGeometry().base
        val first = segment.sceneData(1)
        val projection = segment.projections.single()
        assertSame(first.scene.strokes.single(), first.byRow.getValue(projection.id).single())
        assertSame(first.scene.strokes.single(), first.byProjection.getValue(projection.projectionKey))
        assertEquals(projection.stroke.brush.colorIntArgb, first.scene.strokes.single().colorArgb)
        val oldSecond = segment.sceneData(2)
        assertSame(first, segment.sceneData(1)); segment.sceneData(3)
        assertSame(first, segment.sceneData(1))
        val second = segment.sceneData(2)
        assertNotSame(oldSecond, second)
        assertEquals(first.scene.strokes.single().colorArgb, second.scene.strokes.single().colorArgb)
    }
}
