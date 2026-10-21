package com.vivenotes.ink

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Observations stay on the input thread; immutable requests are modeled on a worker. */
internal class DesktopPartialEraseGesture(val page: InkPage, private val density: Float,
    private val sizeDp: Float = 24f, now: Long = System.currentTimeMillis(),
    private val mode: EraserMode = EraserMode.Normal) {
    private val id = newId()
    private val createdAt = maxOf(now, Math.addExact(page.latestOperationAt, 1L))
    private val inputs = MutableStrokeInputBatch()
    private var first: InkPointerSample? = null
    private var last: InkPointerSample? = null
    private var latestRequest: PartialEraseRequest? = null

    fun observe(sample: InkPointerSample): Boolean {
        val start = first ?: sample.also { first = it }
        val previous = last
        if (sample.toolType != start.toolType || (previous != null && sample.uptimeMillis < previous.uptimeMillis)) return false
        if (previous != null && sample.x == previous.x && sample.y == previous.y &&
            (sample.uptimeMillis - start.uptimeMillis).toFloat() * 0.001f ==
            (previous.uptimeMillis - start.uptimeMillis).toFloat() * 0.001f) return false
        inputs.add(start.toolType, sample.x / density, sample.y / density, sample.uptimeMillis - start.uptimeMillis)
        last = sample
        latestRequest = null
        return true
    }

    fun request(): PartialEraseRequest = latestRequest ?: PartialEraseRequest(page, id, createdAt, sizeDp,
        inputs.toImmutable(), mode).also { latestRequest = it }
}

internal class PartialEraseRequest(val page: InkPage, val id: String, private val createdAt: Long,
    private val sizeDp: Float, private val inputs: StrokeInputBatch, private val mode: EraserMode = EraserMode.Normal) {
    private var modeled = false
    private var prepared: Pair<InkEdit.PartialErase, InkPage>? = null
    internal var preparationCount = 0
        private set
    private var rebased: PartialEraseRequest? = null
    @Synchronized
    fun onPage(page: InkPage): PartialEraseRequest {
        if (this.page === page) return this
        rebased?.takeIf { it.page === page }?.let { return it }
        return PartialEraseRequest(page, id, maxOf(createdAt, Math.addExact(page.latestOperationAt, 1L)),
            sizeDp, inputs, mode).also { rebased = it }
    }

    @Synchronized
    fun prepare(): Pair<InkEdit.PartialErase, InkPage>? {
        if (modeled) return prepared
        val result = model()
        preparationCount++
        prepared = result
        modeled = true
        return result
    }

    private fun model(): Pair<InkEdit.PartialErase, InkPage>? {
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs, sizeDp), id, page.pageId,
            if (mode == EraserMode.Normal) InkEraseMode.Normal else InkEraseMode.Object, createdAt, emptyList())
        val mask = requireNotNull(ViveInkCodec.decodeErase(stored))
        val targets = page.desktopGeometry().index.touching(mask).mapTo(LinkedHashSet()) { it.id }
        if (targets.isEmpty()) return null
        val edit = InkEdit.PartialErase(InkEraseEntity(id, page.pageId, mode, sizeDp,
            stored.points, stored.enc, createdAt), targets)
        return edit to page.withDesktopEdit(edit)
    }
}

/** Latest preview and ordered completions share one native lane and identical-result cache. */
internal class DesktopErasePreparer {
    private val lane = Mutex()
    suspend fun prepare(request: PartialEraseRequest): Pair<InkEdit.PartialErase, InkPage>? =
        withContext(Dispatchers.Default) {
            lane.withLock {
                coroutineContext.ensureActive()
                request.prepare()
            }
        }
}
