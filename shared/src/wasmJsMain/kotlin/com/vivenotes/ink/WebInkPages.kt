package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBounds
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkLassoSelection
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.InkProjectionKey
import com.vivenotes.byteink.kit.unionBounds
import com.vivenotes.byteink.portable.InkStroke
import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.model.ink.InkSelection
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

/**
 * The browser's page geometry: the page's stored rows and the extent their replay paints. Selection
 * questions are answered from [pages]' replay of the rows, by desktop's rules.
 */
internal class WebInkGeometry(private val pages: WebInkPages, val rows: InkPageRows, override val rightDp: Float,
                              override val bottomDp: Float) : InkPageGeometry {
    override fun selectAt(page: InkPage, x: Float, y: Float, reach: Float): InkSelection? {
        if (!x.isFinite() || !y.isFinite() || !reach.isFinite() || reach < 0f) return null
        val pieces = pages.pieces(page)
        val hit = pieces.lastOrNull { it.covers(InkBounds(x - reach, y - reach, x + reach, y + reach)) } ?: return null
        val candidates = if (hit.groupId == null) listOf(hit) else pieces.filter { it.groupId == hit.groupId }
        val box = candidates.mapNotNull { it.bounds }.unionBounds() ?: return null
        return WebInkSelection(pages, page, InkLassoSelection(box.heldLassoPath(), candidates.mapTo(LinkedHashSet()) { it.id },
            candidates.mapTo(LinkedHashSet()) { it.key }, box))
    }

    override fun selectInRect(page: InkPage, left: Float, top: Float, right: Float, bottom: Float): InkSelection? {
        if (listOf(left, top, right, bottom).any { !it.isFinite() } || right <= left || bottom <= top) return null
        return pages.pieces(page).selectWithLasso(InkBounds(left, top, right, bottom).selectionPath())
            ?.let { WebInkSelection(pages, page, it) }
    }

    override fun selectInLasso(page: InkPage, path: List<InkSample>): InkSelection? {
        if (!com.vivenotes.model.ink.inkLassoCloses(path)) return null
        return pages.pieces(page).selectWithLasso(path.map { InkPoint(it.x, it.y) })?.let { WebInkSelection(pages, page, it) }
    }

    override fun selectPastLine(page: InkPage, cut: com.vivenotes.model.SpaceCut): InkSelection? {
        val moving = pages.pieces(page).groupBy { it.groupId ?: it.id }.values.filter { unit ->
            val boxes = unit.mapNotNull { it.bounds }
            boxes.isNotEmpty() && boxes.all { cut.moves(it.left, it.top) }
        }.flatten()
        val box = moving.mapNotNull { it.bounds }.unionBounds() ?: return null
        return moving.selectWithLasso(InkBounds(box.left - 6f, box.top - 6f, box.right + 6f, box.bottom + 6f).selectionPath())
            ?.let { WebInkSelection(pages, page, it) }
    }
}

/** What an eraser gesture shows before it lifts: pieces it hides, and the cut shapes that stand in for some. */
internal class WebErasePreview(val hidden: Set<InkProjectionKey>, val cut: Map<InkProjectionKey, InkStroke>)

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
        val geometry = WebInkGeometry(this, rows, bounds?.right ?: 0f, bounds?.bottom ?: 0f)
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

    /** The replay's pieces, keyed by row and place in the row; valid until another replay is needed. */
    fun pieces(rows: InkPageRows): List<WebInkPiece> {
        val ordinals = HashMap<String, Int>()
        return replay(rows).strokes.map { projection ->
            val ordinal = ordinals[projection.id] ?: 0
            ordinals[projection.id] = ordinal + 1
            WebInkPiece(InkProjectionKey(projection.id, ordinal), projection)
        }
    }

    fun pieces(page: InkPage): List<WebInkPiece> = pieces(rows(page))

    /**
     * Desktop's `PartialEraseRequest`: an eraser gesture through [samples] as the stored row Android
     * replays, targeting every row whose ink the decoded mask touches, and the page it leaves. Null
     * when it touches nothing.
     */
    fun erase(page: InkPage, samples: List<EngineSample>, sizeDp: Float, mode: EraserMode,
              now: Long): Pair<InkEdit.PartialErase, InkPage>? {
        if (samples.isEmpty()) return null
        val id = com.vivenotes.model.newId()
        val createdAt = maxOf(now, page.latestOperationAt + 1)
        val stored = kit.eraseMask(samples, sizeDp).use { mask ->
            kit.encodeErase(mask, id, page.pageId, if (mode == EraserMode.Object) InkEraseMode.Object else InkEraseMode.Normal,
                createdAt, emptyList())
        }
        val targets = (kit.decodeErase(stored) ?: return null).use { mask ->
            pieces(page).filter { it.touches(mask) }.mapTo(LinkedHashSet()) { it.id }
        }
        if (targets.isEmpty()) return null
        val edit = InkEdit.PartialErase(InkEraseEntity(id, page.pageId, mode, sizeDp, stored.points, stored.enc, createdAt), targets)
        return edit to withEdits(page, listOf(edit))
    }

    /**
     * What an unfinished eraser gesture does to [rows]' pieces: an Object erase hides what it
     * touches; a Normal one shows each touched piece with the mask cut out. The caller owns the cuts.
     */
    fun erasePreview(rows: InkPageRows, samples: List<EngineSample>, sizeDp: Float, mode: EraserMode): WebErasePreview {
        if (samples.isEmpty()) return WebErasePreview(emptySet(), emptyMap())
        return kit.eraseMask(samples, sizeDp).use { mask ->
            val touched = pieces(rows).filter { it.touches(mask) }
            WebErasePreview(touched.mapTo(HashSet()) { it.key },
                if (mode == EraserMode.Object) emptyMap()
                else touched.associate { it.key to it.projection.stroke.subtract(mask, it.projection.transform) })
        }
    }
}

/** Decoded page-space samples as engine inputs, 10 ms apart, without pressure: desktop's rule. */
private fun List<InkSample>.inputs(): List<EngineSample> = mapIndexed { index, point ->
    EngineSample(point.x, point.y, index * 10L, EngineTool.MOUSE)
}
