package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class EraseInputObservationTest {
    @Test
    fun adjacentPointsThatCollapseAtDisplayDensityKeepTheAcceptedRequestAndLaterTime() {
        val page = InkPageReader.read("page", listOf(selectionLine(y = 50f)), emptyList(), emptyList())
        val gesture = DesktopPartialEraseGesture(page, 1.5f, 8f, now = 10L)
        val first = InkPointerSample(100.00001525878906f, 75f, 0L, InputToolType.MOUSE)
        val collapsed = first.copy(x = 100.0000228881836f)
        assertNotEquals(first.x, collapsed.x)
        assertEquals(first.x / 1.5f, collapsed.x / 1.5f)
        assertTrue(gesture.observe(first))
        val token = gesture.observation(page)
        val accepted = token.request()

        assertFalse(gesture.observe(collapsed), "Native input identity uses normalized coordinates")

        assertSame(token, gesture.observation(page))
        assertSame(accepted, gesture.request())
        assertEquals(1, gesture.inputSnapshotCount)
        assertTrue(gesture.observe(collapsed.copy(uptimeMillis = 1L)))
        val edit = assertNotNull(gesture.request().prepare()).first
        val mask = assertNotNull(ViveInkCodec.decodeErase(StoredInkErase(edit.row.id, page.pageId,
            edit.row.mode.name, edit.row.sizeDp, edit.row.points, edit.row.enc, edit.row.createdAt,
            targetIds = edit.targetIds.toList())))
        assertEquals(2, mask.inputs.size)
        assertEquals(1L, mask.inputs[1].elapsedTimeMillis)
    }

    @Test
    fun returningToAnEarlierPointAtTheSameTimePreservesEveryNonadjacentNativeInput() {
        val page = InkPageReader.read("page", listOf(selectionLine(y = 50f)), emptyList(), emptyList())
        val gesture = DesktopPartialEraseGesture(page, 1f, 8f, now = 10L)
        val first = InkPointerSample(30f, 50f, 0L, InputToolType.MOUSE)
        assertTrue(gesture.observe(first))
        assertTrue(gesture.observe(first.copy(x = 60f)))
        assertTrue(gesture.observe(first), "The resolved native validator accepts A to B to A")
        assertTrue(gesture.observe(first.copy(uptimeMillis = 1L)))
        val edit = assertNotNull(gesture.request().prepare()).first
        val mask = assertNotNull(ViveInkCodec.decodeErase(StoredInkErase(edit.row.id, page.pageId,
            edit.row.mode.name, edit.row.sizeDp, edit.row.points, edit.row.enc, edit.row.createdAt,
            targetIds = edit.targetIds.toList())))
        assertEquals(4, mask.inputs.size)
        assertEquals(listOf(30f, 60f, 30f, 30f), (0 until mask.inputs.size).map { mask.inputs[it].x })
        assertEquals(listOf(0L, 0L, 0L, 1L), (0 until mask.inputs.size).map { mask.inputs[it].elapsedTimeMillis })
    }

    @Test
    fun inputRebasesNeverWaitForNativePreparationAndBothRequestsStillPrepareOnce() {
        val nativeEntered = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val blockFirstRead = AtomicBoolean(true)
        val sourceSamples = object : AbstractList<InkSample>() {
            override val size = 2
            override fun get(index: Int): InkSample {
                if (blockFirstRead.compareAndSet(true, false)) {
                    nativeEntered.countDown()
                    check(releaseNative.await(30, TimeUnit.SECONDS)) { "Native fixture was not released" }
                }
                return when (index) {
                    0 -> InkSample(10f, 50f)
                    1 -> InkSample(110f, 50f)
                    else -> throw IndexOutOfBoundsException("$index outside two fixture samples")
                }
            }
        }
        // The normal portable-page adapter reads these samples inside actual prepare(). This gates
        // its native/model critical section without exposing a production preparation hook.
        val source = VisibleInkStroke("source", ViveBrushes.MARKER, ViveBrushes.BRUSH_VERSION,
            6f, 0xff000000.toInt(), false, sourceSamples)
        val page = InkPage("page", listOf(source))
        val newer = page.copy(latestOperationAt = 100L)
        val gesture = DesktopPartialEraseGesture(page, 1f, 12f, now = 10L)
        gesture.observe(InkPointerSample(50f, 35f, 0L, InputToolType.MOUSE))
        gesture.observe(InkPointerSample(50f, 65f, 10L, InputToolType.MOUSE))
        val request = gesture.request()
        val native = Executors.newSingleThreadExecutor { Thread(it, "native-erase-preparation-test") }
        val input = Executors.newSingleThreadExecutor { Thread(it, "input-erase-rebase-test") }
        try {
            val preparing = native.submit<Pair<InkEdit.PartialErase, InkPage>?> { request.prepare() }
            assertTrue(nativeEntered.await(5, TimeUnit.SECONDS), "actual preparation must reach the gated source")
            val rebasing = input.submit<PartialEraseRequest> {
                assertSame(request, request.onPage(page))
                val rebased = request.onPage(newer)
                assertSame(rebased, request.onPage(newer))
                rebased
            }
            val rebased = rebasing.get(5, TimeUnit.SECONDS)
            assertEquals(1L, releaseNative.count, "rebasing completes while native preparation is still blocked")
            assertSame(newer, rebased.page)
            assertEquals(1, gesture.inputSnapshotCount)
            releaseNative.countDown()
            val original = assertNotNull(preparing.get(10, TimeUnit.SECONDS))
            assertSame(original, request.prepare())
            assertEquals(1, request.preparationCount)
            assertEquals(10L, original.first.row.createdAt)
            val next = assertNotNull(native.submit<Pair<InkEdit.PartialErase, InkPage>?> { rebased.prepare() }.get(10, TimeUnit.SECONDS))
            assertSame(next, rebased.prepare())
            assertEquals(1, rebased.preparationCount)
            assertEquals(101L, next.first.row.createdAt)
            assertEquals(original.first.targetIds, next.first.targetIds)
        } finally {
            releaseNative.countDown()
            input.shutdownNow()
            native.shutdownNow()
        }
    }

    @Test
    fun pointerEventsDoNotCopyPrefixesUntilAFrameOrReleaseAndTheFinalRequestIsImmutable() {
        val page = InkPageReader.read("page", listOf(selectionLine(y = 50f)), emptyList(), emptyList())
        val gesture = DesktopPartialEraseGesture(page, 2f, 8f, now = 10L)
        repeat(1_000) { index ->
            assertTrue(gesture.observe(InkPointerSample(40f + index / 100f, 100f, index.toLong(), InputToolType.MOUSE)))
            gesture.observation(page)
        }
        assertEquals(0, gesture.inputSnapshotCount, "event publication must retain the mutable input buffer without copying it")
        val frame = gesture.observation(page)
        assertSame(frame, gesture.observation(page))
        val prepared = frame.request()
        assertEquals(1, gesture.inputSnapshotCount)
        assertSame(prepared, frame.request())
        repeat(100) { index ->
            gesture.observe(InkPointerSample(50f + index / 100f, 100f, 1_000L + index, InputToolType.MOUSE))
            gesture.observation(page)
        }
        val release = gesture.observation(page).request()
        assertNotSame(prepared, release)
        assertEquals(2, gesture.inputSnapshotCount, "one frame and release freeze two exact prefixes despite 1,100 events")
        assertSame(release, gesture.request())
        assertSame(prepared, frame.request(), "an already frozen preview never observes later inputs")
        val edit = assertNotNull(release.prepare()).first
        val mask = assertNotNull(ViveInkCodec.decodeErase(StoredInkErase(edit.row.id, page.pageId,
            edit.row.mode.name, edit.row.sizeDp, edit.row.points, edit.row.enc, edit.row.createdAt, targetIds = edit.targetIds.toList())))
        assertEquals(1_100, mask.inputs.size)
        assertEquals(20f, mask.inputs[0].x, .02f)
        assertEquals(25.495f, mask.inputs[1_099].x, .02f)
        assertEquals(1_099L, mask.inputs[1_099].elapsedTimeMillis)
    }

    @Test
    fun duplicateAndRejectedInputsDoNotPublishNewObservationsAndRebaseDoesNotCopyInputs() {
        val page = InkPage("page", emptyList())
        val gesture = DesktopPartialEraseGesture(page, 1f)
        val first = InkPointerSample(10f, 20f, 30L, InputToolType.MOUSE)
        assertTrue(gesture.observe(first))
        val token = gesture.observation(page)
        assertFalse(gesture.observe(first))
        assertFalse(gesture.observe(first.copy(uptimeMillis = 29L)))
        assertFalse(gesture.observe(first.copy(toolType = InputToolType.STYLUS)))
        assertSame(token, gesture.observation(page))
        val request = token.request()
        val next = page.copy(latestOperationAt = 100L)
        val rebased = token.onPage(next)
        assertSame(rebased, token.onPage(next))
        assertSame(next, rebased.request().page)
        assertNotSame(request, rebased.request())
        assertEquals(1, gesture.inputSnapshotCount)
    }
}
