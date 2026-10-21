package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.Block
import com.vivenotes.model.Outline
import com.vivenotes.ui.theme.ViveNotesTheme
import com.vivenotes.workspace.WorkspaceState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CanvasVideoMeasurementTest {
    @Test
    fun delayedOffscreenVideosFinishTheirActualMeasurementBeforeTheyAreCulled() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val release = CompletableDeferred<Unit>()
            val starts = AtomicInteger()
            val png = png()
            val source = object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray {
                    starts.incrementAndGet()
                    release.await()
                    return png
                }
            }
            var state by mutableStateOf(page(listOf(
                text("near", 130f, listOf(Block.of("Near"))),
                text("far", 4000f, videos(8)),
            )))
            setContent { ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, thumbnails = source) } }
            waitUntil(timeoutMillis = 5_000) { starts.get() == 1 }
            waitForIdle()
            onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertExists()
            val before = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value

            release.complete(Unit)

            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value > before + 1000f
            }
            waitForIdle()
            assertEquals(8, starts.get())
            onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
            onNodeWithTag(WorkspaceTestTags.textBox("far")).assertExists()
        }

    @Test
    fun aLongBoxStartingAboveTheViewportKeepsItsVisibleCardsAfterDelayedLoading() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val release = CompletableDeferred<Unit>()
            val began = CompletableDeferred<Unit>()
            val png = png()
            val source = object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray {
                    began.complete(Unit)
                    release.await()
                    return png
                }
            }
            var state by mutableStateOf(page(listOf(
                text("long", 130f, videos(20)),
                text("anchor", 2600f, listOf(Block.of("Anchor"))),
            )))
            setContent { ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, thumbnails = source) } }
            waitUntil(timeoutMillis = 5_000) { began.isCompleted }
            var wheelSteps = 0
            while (wheelSteps < 40) {
                val canvas = onNodeWithTag(WorkspaceTestTags.PageCanvas).getUnclippedBoundsInRoot()
                val anchor = onNodeWithTag(WorkspaceTestTags.textBox("anchor")).getUnclippedBoundsInRoot()
                if (anchor.top.value < canvas.bottom.value - 80f) break
                onNodeWithTag(WorkspaceTestTags.PageCanvas).performMouseInput { moveTo(center); scroll(2f) }
                waitForIdle()
                wheelSteps++
            }
            assertTrue(wheelSteps in 1 until 40, "real wheel input brings the far anchor into view")
            waitForIdle()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertExists()
            assertTrue(onNodeWithTag(WorkspaceTestTags.textBox("long")).getUnclippedBoundsInRoot().bottom.value <
                onNodeWithTag(WorkspaceTestTags.PageCanvas).getUnclippedBoundsInRoot().top.value - 256f,
                "the URL-only box is above the viewport and its overscan before the cards load")

            release.complete(Unit)

            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(WorkspaceTestTags.textBox("long")).getUnclippedBoundsInRoot().height.value > 3000f
            }
            waitForIdle()
            onNodeWithTag(WorkspaceTestTags.BodyEditor).assertExists()
            assertTrue((0 until 20).any { index ->
                runCatching {
                    onNodeWithTag("video-preview-${videoId(index)}", useUnmergedTree = true).assertIsDisplayed()
                }.isSuccess
            }, "cards crossing the viewport stay composed even though their box starts above it")
        }

    @Test
    fun missingFailedAndEquationPreviewsSettleAndReleaseOffscreenEditors() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val calls = AtomicInteger()
            val source = object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray? {
                    calls.incrementAndGet()
                    if (videoId == this@CanvasVideoMeasurementTest.videoId(1)) error("unavailable")
                    return null
                }
            }
            var state by mutableStateOf(page(listOf(
                text("near", 130f, listOf(Block.of("Near"))),
                text("missing", 4000f, listOf(Block.of("https://youtu.be/${videoId(0)}"))),
                text("failed", 4400f, listOf(Block.of("https://youtu.be/${videoId(1)}"))),
                text("formula", 4800f, listOf(Block.of("Area is \$x^2\$"))),
            )))
            setContent { ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, thumbnails = source) } }
            waitUntil(timeoutMillis = 5_000) { calls.get() == 2 }
            waitForIdle()
            listOf("missing", "failed", "formula").forEach { id ->
                onNodeWithTag(WorkspaceTestTags.textBox(id) + "-editor").assertDoesNotExist()
                assertTrue(onNodeWithTag(WorkspaceTestTags.textBox(id)).getUnclippedBoundsInRoot().height.value < 400f)
            }
        }

    @Test
    fun disablingThumbnailsWhileAReadFinishesLateCannotPublishStaleHeight() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val began = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = AtomicBoolean()
            val png = png()
            var source: VideoThumbnailSource? by mutableStateOf(object : VideoThumbnailSource {
                override suspend fun load(videoId: String): ByteArray {
                    began.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    returned.set(true)
                    return png
                }
            })
            var state by mutableStateOf(page(listOf(
                text("near", 130f, listOf(Block.of("Near"))),
                text("far", 4000f, videos(8)),
            )))
            setContent { ViveNotesTheme { WorkspaceScreen(state, { state = it(state) }, thumbnails = source) } }
            try {
                waitUntil(timeoutMillis = 5_000) { began.isCompleted }
                runOnIdle { source = null }
                waitForIdle()
                onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
                val settled = onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value
                release.complete(Unit)
                waitUntil(timeoutMillis = 5_000) { returned.get() }
                waitForIdle()
                assertEquals(settled, onNodeWithTag(WorkspaceTestTags.CanvasExtent).getUnclippedBoundsInRoot().height.value)
                onNodeWithTag(WorkspaceTestTags.textBox("far") + "-editor").assertDoesNotExist()
            } finally {
                release.complete(Unit)
            }
        }

    private fun page(outlines: List<Outline>): WorkspaceState = WorkspaceState.demo().let { state ->
        state.updatePage(state.selectedPageId) { page -> page.copy(document = page.document.copy(outlines = outlines)) }
            .clearCanvasFocus()
    }

    private fun text(id: String, top: Float, blocks: List<Block>) =
        Outline.Text(id, x = 20f, y = top, width = 500f, blocks = blocks)

    private fun videos(count: Int): List<Block> = List(count) { Block.of("https://youtu.be/${videoId(it)}") }
    private fun videoId(index: Int): String = "video${index.toString().padStart(6, '0')}"
    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
}
