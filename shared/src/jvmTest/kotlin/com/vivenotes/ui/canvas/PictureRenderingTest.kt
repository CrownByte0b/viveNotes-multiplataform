package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureLibrary
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PictureRenderingTest {
    @Test
    fun visiblePicturesLoadOffMainInTwoLanesAndRenderAtTheirTargetResolution() = runDesktopComposeUiTest {
        val bytes = png()
        val release = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val touchedEdt = AtomicBoolean()
        val library = library {
            if (EventQueue.isDispatchThread()) touchedEdt.set(true)
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count) { a, b -> maxOf(a, b) }
            // Counted last, so a test that sees two starts also sees the peak they reached.
            starts.incrementAndGet()
            try { release.await(); bytes } finally { active.decrementAndGet() }
        }
        var shown: Map<String, PictureAsset> = emptyMap()
        val targets = listOf("a", "b", "c").map { PictureDemand(it, 32, 16) }
        setContent {
            ViveNotesTheme(darkTheme = false) {
                val assets = rememberPictureAssets(targets, library, "page")
                SideEffect { shown = assets }
                Row { targets.forEach { target ->
                    Box(Modifier.size(64.dp, 32.dp).testTag("picture-${target.attachmentId}")) {
                        PictureContent(assets[target.attachmentId])
                    }
                } }
            }
        }
        waitUntil(timeoutMillis = 5_000) { starts.get() == 2 }
        runOnIdle { assertEquals(2, peak.get()); assertFalse(touchedEdt.get()) }
        release.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { shown.size == 3 && shown.values.all { it is PictureAsset.Ready } }
        runOnIdle {
            assertEquals(3, starts.get())
            shown.values.forEach { asset ->
                val ready = asset as PictureAsset.Ready
                assertEquals(32 to 16, ready.bitmap.width to ready.bitmap.height)
                assertEquals(128 to 64, ready.sourceWidth to ready.sourceHeight)
            }
        }
        onNodeWithTag("picture-a").assertIsDisplayed()
        val pixels = onNodeWithTag("picture-a").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width * 3 / 4, pixels.height / 2].blue > .8f)
    }

    @Test
    fun pageSwitchAndLateBytesNeverPublishThePreviousPagesPicture() = runDesktopComposeUiTest {
        val bytes = png()
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val library = library { id ->
            if (id == "old") {
                began.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            bytes
        }
        var page by mutableStateOf("old")
        var shown: Map<String, PictureAsset> = emptyMap()
        setContent {
            ViveNotesTheme(darkTheme = false) {
                val assets = rememberPictureAssets(listOf(PictureDemand(page, 32, 32)), library, page)
                SideEffect { shown = assets }
                Box(Modifier.size(64.dp, 32.dp).testTag("picture")) { PictureContent(assets[page]) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { began.isCompleted }
        runOnIdle { page = "new" }
        waitUntil(timeoutMillis = 5_000) { shown["new"] is PictureAsset.Ready }
        release.complete(Unit)
        waitForIdle()
        runOnIdle { assertEquals(setOf("new"), shown.keys) }
        onNodeWithTag("picture").assertIsDisplayed()
    }

    @Test
    fun resizeKeepsAspectAlphaAndSmallSourceWithoutUpscaling() = runDesktopComposeUiTest {
        val bytes = png()
        val images = library { bytes }
        var ready: PictureAsset.Ready? = null
        setContent {
            val assets = rememberPictureAssets(listOf(PictureDemand("image", 32, 16)), images, "page")
            SideEffect { ready = assets["image"] as? PictureAsset.Ready }
            Box(Modifier.size(64.dp, 32.dp)) { PictureContent(assets["image"]) }
        }
        waitUntil(timeoutMillis = 5_000) { ready != null }
        runOnIdle {
            val asset = ready!!
            val pixels = asset.bitmap.toPixelMap()
            assertEquals(32 to 16, pixels.width to pixels.height)
            assertTrue(abs(pixels[4, 8].alpha - .5f) < .02f)
            assertTrue(pixels[4, 8].red > .9f)
            assertTrue(pixels[24, 8].blue > .9f)
            assertTrue(pixels[24, 8].alpha > .99f)
            val large = resizePicture(asset, PictureDemand("image", 512, 512))
            assertTrue(large.bitmap === asset.bitmap)
        }
    }

    @Test
    fun stretchedPlacementsKeepBothRequestedAxesAndNormalizedImageGeometry() = runDesktopComposeUiTest {
        val images = library { png() }
        var ready: PictureAsset.Ready? = null
        setContent {
            ViveNotesTheme(darkTheme = false) {
                val assets = rememberPictureAssets(listOf(PictureDemand("image", 32, 32)), images, "page")
                SideEffect { ready = assets["image"] as? PictureAsset.Ready }
                Box(Modifier.size(64.dp).testTag("stretched")) { PictureContent(assets["image"], background = 0xffffffff.toInt()) }
            }
        }
        waitUntil(timeoutMillis = 5_000) { ready != null }
        runOnIdle {
            val asset = ready!!
            assertEquals(32 to 32, asset.bitmap.width to asset.bitmap.height)
            assertEquals(128 to 64, asset.sourceWidth to asset.sourceHeight)
            val pixels = asset.bitmap.toPixelMap()
            assertTrue(abs(pixels[8, 24].alpha - .5f) < .02f)
            assertTrue(pixels[8, 24].red > .9f)
            assertTrue(pixels[24, 8].blue > .9f)
        }
        val displayed = onNodeWithTag("stretched").captureToImage().toPixelMap()
        assertTrue(displayed[displayed.width / 4, displayed.height * 3 / 4].red > .9f)
        assertTrue(displayed[displayed.width * 3 / 4, displayed.height / 4].blue > .9f)
    }

    private fun library(read: suspend (String) -> ByteArray?) = object : PictureLibrary {
        override suspend fun choose(): ImportedPicture? = null
        override suspend fun import(bytes: ByteArray): ImportedPicture? = null
        override suspend fun bytes(attachmentId: String): ByteArray? = read(attachmentId)
    }

    private fun png(): ByteArray = Surface.makeRasterN32Premul(128, 64).use { surface ->
        surface.canvas.clear(Color.BLUE)
        Paint().use { paint ->
            paint.color = 0x80ff0000.toInt()
            surface.canvas.clear(Color.TRANSPARENT)
            surface.canvas.drawRect(Rect.makeXYWH(0f, 0f, 64f, 64f), paint)
            paint.color = Color.BLUE
            surface.canvas.drawRect(Rect.makeXYWH(64f, 0f, 64f, 64f), paint)
        }
        surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
    }
}
