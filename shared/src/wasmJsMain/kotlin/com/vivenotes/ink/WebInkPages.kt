package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.unionBounds
import com.vivenotes.byteink.portable.InkKit
import com.vivenotes.byteink.portable.InkStyle
import com.vivenotes.byteink.portable.InkTool as EngineTool
import com.vivenotes.byteink.portable.InkSample as EngineSample
import com.vivenotes.byteink.portable.WasmInkEngine
import com.vivenotes.data.InkEdit
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageGeometry
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import com.vivenotes.byteink.portable.InkPage as Replay

/** The browser's page geometry: the page's stored rows and the extent their replay paints. */
internal class WebInkGeometry(val rows: InkPageRows, override val rightDp: Float, override val bottomDp: Float) : InkPageGeometry

/**
 * Page replays on the browser's engine. A replay owns engine memory that only an explicit close
 * frees, while page snapshots are values kept by undo history, so snapshots hold rows alone and the
 * few most recent replays are kept here, the oldest closed when another is needed.
 */
internal class WebInkPages(val engine: WasmInkEngine, private val capacity: Int = 4) {
    val kit = InkKit(engine)
    private val replays = ArrayList<Pair<InkPageRows, Replay>>()

    /** The replay of [rows], valid until [capacity] other row sets have been replayed. */
    fun replay(rows: InkPageRows): Replay {
        val index = replays.indexOfFirst { it.first === rows }
        if (index >= 0) return replays.removeAt(index).also { replays += it }.second
        val replay = kit.load(rows.pageId, rows.strokes, rows.erases, rows.moves)
        replays += rows to replay
        while (replays.size > capacity) replays.removeAt(0).second.close()
        return replay
    }

    /** The common page for [rows], as desktop's `InkPageReader` builds it from the same rows. */
    fun page(rows: InkPageRows, latestOperationAt: Long? = null): InkPage {
        val replay = replay(rows)
        val visible = replay.sourceStrokes.mapNotNull { source ->
            val inputs = source.stroke.inputs()
            if (inputs.isEmpty()) null else VisibleInkStroke(source.id, source.stroke.style.familyId, source.brushVersion,
                source.stroke.style.size, source.stroke.style.colorArgb, source.colorFollowsTheme,
                inputs.map { InkSample(it.x, it.y, it.pressure) }, source.stroke.style.stabilization, source.stroke.style.epsilon)
        }
        val erases = rows.erases.filter { it.deletedAt == null }.mapNotNull { row ->
            val samples = kit.decodeErase(row)?.use { mask -> mask.inputs().map { InkSample(it.x, it.y, it.pressure) } }
            if (samples.isNullOrEmpty()) null else InkPageOperation.Erase(row.id, row.createdAt, row.targetIds.toSet(),
                row.mode == InkEraseMode.Object.stored, row.sizeDp, samples)
        }
        val moves = rows.moves.filter { it.deletedAt == null }.mapNotNull { row ->
            val path = kit.decodeMove(row) ?: return@mapNotNull null
            InkPageOperation.Move(row.id, row.createdAt, row.targetIds.toSet(), row.dxDp, row.dyDp, row.scaleX, row.scaleY,
                row.anchorX, row.anchorY, path.map { InkSample(it.x, it.y) })
        }
        val operations = (erases + moves).sortedWith(compareBy(InkPageOperation::createdAt, InkPageOperation::id))
        val bounds = replay.strokes.mapNotNull { it.pageBounds }.unionBounds()
        val geometry = WebInkGeometry(rows, bounds?.right ?: 0f, bounds?.bottom ?: 0f)
        return if (latestOperationAt == null) InkPage(rows.pageId, visible, operations, geometry)
        else InkPage(rows.pageId, visible, operations, geometry, maxOf(latestOperationAt, operations.maxOfOrNull { it.createdAt } ?: 0L))
    }

    /**
     * The rows behind [page]. A page from elsewhere — a preview or a test — has decoded strokes
     * only; they are encoded again as desktop's `desktopGeometry` does for such a page.
     */
    fun rows(page: InkPage): InkPageRows = (page.geometry as? WebInkGeometry)?.rows ?: InkPageRows(page.pageId,
        strokes = page.strokes.mapIndexed { seq, source ->
            val style = InkStyle(source.brushFamily, source.stabilization, source.colorArgb, source.sizeDp, source.epsilon)
            engine.createStroke(style, source.samples.inputs()).use { stroke ->
                kit.encodeStroke(stroke, source.id, page.pageId, seq, source.brushFamily, source.stabilization,
                    source.colorFollowsTheme, 0L).copy(brushVersion = source.brushVersion)
            }
        },
        erases = page.operations.filterIsInstance<InkPageOperation.Erase>().mapNotNull { row ->
            kit.reloadedEraseMask(row.samples.inputs(), row.sizeDp)?.use { mask ->
                kit.encodeErase(mask, row.id, page.pageId, if (row.objectMode) InkEraseMode.Object else InkEraseMode.Normal,
                    row.createdAt, row.targetIds.toList())
            }
        },
        moves = page.operations.filterIsInstance<InkPageOperation.Move>().map { row ->
            val path = row.path.ifEmpty { listOf(InkSample(-10000f, -10000f), InkSample(10000f, -10000f),
                InkSample(10000f, 10000f), InkSample(-10000f, 10000f)) }.map { InkPoint(it.x, it.y) }
            if (row.scaleX == 1f && row.scaleY == 1f) kit.encodeMove(InkLassoMove(path, row.targetIds, emptySet(),
                row.dxDp, row.dyDp), row.id, page.pageId, row.createdAt)
            else kit.encodeMove(InkLassoMove(path, row.targetIds, emptySet(), row.dxDp, row.dyDp), row.id, page.pageId,
                row.createdAt).copy(scaleX = row.scaleX, scaleY = row.scaleY, anchorX = row.anchorX, anchorY = row.anchorY)
        },
    )

    /** [page] after [edits], replayed from its rows. */
    fun withEdits(page: InkPage, edits: List<InkEdit>): InkPage =
        page(rows(page).withEdits(edits, ::rows), edits.latestOperationAt(page.latestOperationAt))
}

/** Decoded page-space samples as engine inputs, 10 ms apart, without pressure: desktop's rule. */
private fun List<InkSample>.inputs(): List<EngineSample> = mapIndexed { index, point ->
    EngineSample(point.x, point.y, index * 10L, EngineTool.MOUSE)
}
