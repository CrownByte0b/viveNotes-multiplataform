package com.vivenotes.ui.canvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PictureAssetLoaderTest {
    @BeforeTest fun backend() { registerSkikoComposeImplementation() }

    @Test
    fun onlyTwoFlightsRunAndUnchangedVisibleRequestsShareThem() = runTest {
        val gates = List(6) { CompletableDeferred<Unit>() }
        val calls = mutableListOf<Int>()
        var running = 0
        var peak = 0
        val loader = PictureAssetLoader(this) { target, _ ->
            val index = target.attachmentId.toInt()
            calls += index
            running++
            peak = maxOf(peak, running)
            try { gates[index].await(); PictureAsset.Ready(ImageBitmap(8, 8)) } finally { running-- }
        }
        val targets = List(6) { PictureDemand(it.toString(), 8, 8) }
        loader.update(targets, "page")
        runCurrent()
        assertEquals(listOf(0, 1), calls)
        loader.update(targets + targets.first(), "page")
        runCurrent()
        assertEquals(listOf(0, 1), calls)
        gates.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals((0..5).toList(), calls)
        assertEquals(2, peak)
        assertEquals(6, loader.assets.size)
        loader.close()
    }

    @Test
    fun pageAndResolutionChangesDiscardLateNoncancellableResults() = runTest {
        val old = CompletableDeferred<Unit>()
        val loader = PictureAssetLoader(this) { target, _ ->
            if (target.attachmentId == "old") withContext(NonCancellable) { old.await() }
            PictureAsset.Ready(ImageBitmap(target.widthPx, target.heightPx))
        }
        loader.update(listOf(PictureDemand("old", 8, 8)), "one")
        runCurrent()
        loader.update(listOf(PictureDemand("new", 8, 8)), "two")
        runCurrent()
        assertEquals(setOf("new"), loader.assets.keys)
        old.complete(Unit)
        runCurrent()
        assertEquals(setOf("new"), loader.assets.keys)
        val bitmap = (loader.assets.getValue("new") as PictureAsset.Ready).bitmap
        loader.close()
        assertEquals(8, bitmap.toPixelMap().width)
    }

    @Test
    fun expandingVisibleSetDownsizesExistingPixelsAndShowsAllImagesWithoutReloadLoop() = runTest {
        var fetches = 0
        val loader = PictureAssetLoader(this, maxBytes = 4 * 32 * 32 * 4L) { target, source ->
            val original = source ?: PictureAsset.Ready(ImageBitmap(32, 32)).also { fetches++ }
            resizePicture(original, target)
        }
        val targets = List(8) { PictureDemand(it.toString(), 32, 32) }
        loader.update(targets.take(4), "page")
        runCurrent()
        assertEquals(4, loader.assets.size)
        assertEquals(4, fetches)
        loader.update(targets, "page")
        runCurrent()
        assertEquals(8, loader.assets.size)
        assertEquals(8, fetches)
        assertTrue(loader.retainedBytes <= 4 * 32 * 32 * 4L)
        val retained = (loader.assets.getValue("0") as PictureAsset.Ready).bitmap
        loader.update(targets, "page")
        runCurrent()
        assertEquals(8, fetches)
        assertSame(retained, (loader.assets.getValue("0") as PictureAsset.Ready).bitmap)
        loader.close()
    }

    @Test
    fun zoomReusesAllAvailablePixelsOnEachAxisAndReloadsOnlyWhenMorePixelsExist() = runTest {
        var fetches = 0
        val loader = PictureAssetLoader(this) { target, source ->
            resizePicture(source ?: PictureAsset.Ready(ImageBitmap(16, 128)).also { fetches++ }, target)
        }
        loader.update(listOf(PictureDemand("narrow", 16, 32)), "page")
        runCurrent()
        val initial = (loader.assets.getValue("narrow") as PictureAsset.Ready).bitmap
        assertEquals(16 to 32, initial.width to initial.height)
        loader.update(listOf(PictureDemand("narrow", 64, 32)), "page")
        runCurrent()
        assertEquals(1, fetches)
        assertSame(initial, (loader.assets.getValue("narrow") as PictureAsset.Ready).bitmap)
        loader.update(listOf(PictureDemand("narrow", 64, 64)), "page")
        runCurrent()
        assertEquals(2, fetches)
        val taller = (loader.assets.getValue("narrow") as PictureAsset.Ready).bitmap
        assertEquals(16 to 64, taller.width to taller.height)
        loader.update(listOf(PictureDemand("narrow", 512, 64)), "page")
        runCurrent()
        assertEquals(2, fetches)
        assertSame(taller, (loader.assets.getValue("narrow") as PictureAsset.Ready).bitmap)
        loader.close()
    }

    @Test
    fun pendingAdmissionsReleaseDecodeSlotsForPinnedPicturesThatMustShrink() = runTest {
        val tiny = CompletableDeferred<Unit>()
        var activeDecodes = 0
        var peak = 0
        val loader = PictureAssetLoader(this, maxBytes = 128) { target, source ->
            activeDecodes++
            peak = maxOf(peak, activeDecodes)
            try {
                if (target.attachmentId in setOf("x", "y")) tiny.await()
                resizePicture(source ?: PictureAsset.Ready(ImageBitmap(
                    if (target.attachmentId in setOf("x", "y")) 1 else 4,
                    if (target.attachmentId in setOf("x", "y")) 1 else 4)), target)
            } finally { activeDecodes-- }
        }
        val a = PictureDemand("a", 4, 4)
        val b = PictureDemand("b", 4, 4)
        val x = PictureDemand("x", 1, 1)
        val y = PictureDemand("y", 1, 1)
        loader.update(listOf(a), "page")
        runCurrent()
        loader.update(listOf(b), "page")
        runCurrent()
        assertEquals(128L, loader.retainedBytes)
        loader.update(listOf(a, x, y), "page")
        runCurrent()
        loader.update(listOf(a, b, x, y), "page")
        tiny.complete(Unit)
        runCurrent()
        assertEquals(setOf("a", "b", "x", "y"), loader.assets.keys)
        assertTrue(loader.retainedBytes <= 128)
        assertEquals(2, peak)
        loader.close()
    }

    @Test
    fun failedFilesUseCooldownAndRemovedDemandCancelsItsFlight() = runTest {
        var now = 0L
        var calls = 0
        var cancelled = false
        val gate = CompletableDeferred<Unit>()
        val loader = PictureAssetLoader(this, nowMillis = { now }) { target, _ ->
            calls++
            if (target.attachmentId == "slow") try { gate.await() } finally { cancelled = true }
            PictureAsset.Broken("missing")
        }
        loader.update(listOf(PictureDemand("missing", 8, 8)), "page")
        runCurrent()
        loader.update(listOf(PictureDemand("missing", 16, 16)), "page")
        runCurrent()
        assertEquals(1, calls)
        now = 60_001
        loader.update(listOf(PictureDemand("missing", 16, 16)), "page")
        runCurrent()
        assertEquals(2, calls)
        loader.update(listOf(PictureDemand("slow", 8, 8)), "page")
        runCurrent()
        loader.update(emptyList(), "page")
        runCurrent()
        assertTrue(cancelled)
        assertFalse("slow" in loader.assets)
        loader.close()
    }
}
