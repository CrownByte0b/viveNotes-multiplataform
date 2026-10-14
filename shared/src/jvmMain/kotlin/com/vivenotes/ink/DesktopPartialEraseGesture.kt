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

/** Observations stay on the input thread; immutable requests are modeled on a worker. */
internal class DesktopPartialEraseGesture(val page: InkPage, private val density: Float,
    private val sizeDp: Float = 24f, now: Long = System.currentTimeMillis(),
    private val mode: EraserMode = EraserMode.Normal) {
    private val id = newId()
    private val createdAt = maxOf(now, Math.addExact(page.latestOperationAt, 1L))
    private val inputs = MutableStrokeInputBatch()
    private var first: InkPointerSample? = null
    private var last: InkPointerSample? = null

    fun observe(sample: InkPointerSample): Boolean {
        val start = first ?: sample.also { first = it }
        val previous = last
        if (sample.toolType != start.toolType || (previous != null && sample.uptimeMillis < previous.uptimeMillis)) return false
        if (previous != null && sample.x == previous.x && sample.y == previous.y &&
            (sample.uptimeMillis - start.uptimeMillis).toFloat() * 0.001f ==
            (previous.uptimeMillis - start.uptimeMillis).toFloat() * 0.001f) return false
        inputs.add(start.toolType, sample.x / density, sample.y / density, sample.uptimeMillis - start.uptimeMillis)
        last = sample
        return true
    }

    fun request(): PartialEraseRequest = PartialEraseRequest(page, id, createdAt, sizeDp, inputs.toImmutable(), mode)
}

internal class PartialEraseRequest(val page: InkPage, val id: String, private val createdAt: Long,
    private val sizeDp: Float, private val inputs: StrokeInputBatch, private val mode: EraserMode = EraserMode.Normal) {
    fun onPage(page: InkPage): PartialEraseRequest = if (this.page === page) this else
        PartialEraseRequest(page, id, maxOf(createdAt, Math.addExact(page.latestOperationAt, 1L)), sizeDp, inputs, mode)

    fun prepare(): Pair<InkEdit.PartialErase, InkPage>? {
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
