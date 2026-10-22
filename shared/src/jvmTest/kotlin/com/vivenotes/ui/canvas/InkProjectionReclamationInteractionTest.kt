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
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.reclamationFixture
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InkProjectionReclamationInteractionTest {
    @Test
    fun idleWholeRowReclamationPublishesAnEquivalentSnapshotOffTheDrawingThread() = runDesktopComposeUiTest(width = 128, height = 128) {
        val (_, source) = reclamationFixture()
        val current = mutableStateOf(source)
        var callbacks = 0
        var publicationThread: String? = null
        setContent {
            CompositionLocalProvider(LocalInkGeometryPublisher provides { before, prepared ->
                if (current.value === before) {
                    callbacks++
                    publicationThread = Thread.currentThread().name
                    current.value = prepared
                }
            }) {
                Box(Modifier.size(128.dp).background(Color.White).testTag("reclaimed-frame")) {
                    InkLayer(current.value, DpSize(128.dp, 128.dp), Color.Black)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { current.value !== source }
        runOnIdle {
            assertEquals(1, callbacks)
            assertEquals(144, current.value.desktopGeometry().segments().sumOf { it.projections.size })
            assertEquals(400, source.desktopGeometry().base.projections.size)
            assertNotNull(source.desktopGeometry().projectionReclamationThread)
            assertNotEquals(publicationThread, source.desktopGeometry().projectionReclamationThread)
            assertEquals(1, source.desktopGeometry().projectionReclamationBuildCount)
            assertEquals(0, source.desktopGeometry().basePartitionBuildCount)
            assertSame(source.strokes, current.value.strokes)
            assertSame(source.operations, current.value.operations)
        }
        val pixels = onNodeWithTag("reclaimed-frame").captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[60, 50])
        assertEquals(Color.White, pixels[60, 80])
    }

    @Test
    fun leavingThePageCancelsQueuedReclamationWithoutPublishingAnOldSnapshot() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ink-ui-reclaim-test") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            executor.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                runDesktopComposeUiTest(width = 128, height = 128) {
                    val (_, source) = reclamationFixture()
                    val current = mutableStateOf(source)
                    var callbacks = 0
                    setContent {
                        CompositionLocalProvider(LocalInkPreparationDispatcher provides dispatcher,
                            LocalInkGeometryPublisher provides { _, _ -> callbacks++ }) {
                            Box(Modifier.size(128.dp).background(Color.White).testTag("cancelled-reclaim-frame")) {
                                InkLayer(current.value, DpSize(128.dp, 128.dp), Color.Black)
                            }
                        }
                    }
                    runOnIdle { current.value = InkPage("another", emptyList()) }
                    waitForIdle()
                    release.countDown()
                    val drained = CountDownLatch(1)
                    executor.submit { drained.countDown() }
                    assertTrue(drained.await(5, TimeUnit.SECONDS))
                    runOnIdle {
                        assertEquals(0, callbacks)
                        assertEquals(0, source.desktopGeometry().projectionReclamationBuildCount)
                        assertNull(source.desktopGeometry().projectionReclamationThread)
                        assertEquals(0, source.desktopGeometry().basePartitionBuildCount)
                        assertEquals("another", current.value.pageId)
                    }
                    assertEquals(Color.White, onNodeWithTag("cancelled-reclaim-frame").captureToImage().toPixelMap()[60, 50])
                }
            } finally { release.countDown() }
        }
    }
}
