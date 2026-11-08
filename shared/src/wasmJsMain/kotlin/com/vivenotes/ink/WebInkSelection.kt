package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkLassoSelection
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.PageBounds
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.model.ink.InkClipboard
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.InkSelectionBounds
import com.vivenotes.model.ink.InkSelectionChange
import com.vivenotes.model.newId
import kotlin.time.Clock

/** The first operation time after everything [page] has seen, as desktop clocks its edits. */
private fun operationClock(page: InkPage): Long = maxOf(Clock.System.now().toEpochMilliseconds(), page.latestOperationAt + 1)

/** An Object erase removing one piece of [rowId]; the mask is closed once encoded. */
private fun WebInkPages.pieceErase(piece: WebPieceErase, pageId: String, createdAt: Long): InkEdit.PartialErase {
    val row = piece.mask.use { kit.encodeErase(it, newId(), pageId, InkEraseMode.Object, createdAt, listOf(piece.rowId)) }
    return InkEdit.PartialErase(InkEraseEntity(row.id, pageId, EraserMode.Object, row.sizeDp, row.points, row.enc,
        row.createdAt), setOf(piece.rowId))
}

/**
 * The browser's ink selection: desktop's `DesktopInkSelection`, held as lasso path, rows and piece
 * keys of [page]'s replay, its every change a list of [InkEdit]s replayed from rows.
 */
internal class WebInkSelection(private val pages: WebInkPages, override val page: InkPage, val held: InkLassoSelection) :
    InkSelection {
    override val bounds = held.bounds.let { InkSelectionBounds(it.left, it.top, it.right, it.bottom) }
    override val targetIds: Set<String> get() = held.targetIds
    private val rows by lazy { pages.rows(page) }
    override val isGrouped: Boolean by lazy {
        rows.strokes.filter { it.id in targetIds }.map { it.groupId }.distinct().let { it.size == 1 && it.single() != null }
    }

    override fun move(dx: Float, dy: Float): InkSelectionChange? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        val delta = PageBounds.clampTranslation(held.bounds, dx, dy)
        if (delta.x == 0f && delta.y == 0f) return null
        val row = pages.kit.encodeMove(InkLassoMove(held.path, held.targetIds, held.projections, delta.x, delta.y),
            newId(), page.pageId, operationClock(page))
        val edit = InkEdit.Move(InkMoveEntity(row.id, page.pageId, row.dxDp, row.dyDp, points = row.points,
            enc = row.enc, createdAt = row.createdAt), held.targetIds)
        val after = pages.withEdits(page, listOf(edit))
        val path = held.path.map { InkPoint(it.x + delta.x, it.y + delta.y) }
        val selected = pages.pieces(after).filter { it.id in held.targetIds }.selectWithLasso(path)
        return InkSelectionChange(page, after, listOf(edit), listOf(edit.copy(active = false)),
            selected?.let { WebInkSelection(pages, after, it) }, delta.x, delta.y, this)
    }

    override fun delete(): InkSelectionChange? {
        val (erases, wholeRows) = pages.planDelete(pages.pieces(page), held.projections)
        var clock = operationClock(page)
        val edits = erases.mapTo(mutableListOf<InkEdit>()) { pages.pieceErase(it, page.pageId, clock++) }
        if (wholeRows.isNotEmpty()) edits += InkEdit.EraseStrokes(wholeRows.toSet())
        if (edits.isEmpty()) return null
        val undo = edits.asReversed().map { edit -> when (edit) {
            is InkEdit.PartialErase -> edit.copy(active = false)
            is InkEdit.EraseStrokes -> InkEdit.RestoreStrokes(edit.ids, page)
            else -> error("Unexpected selection delete")
        } }
        return InkSelectionChange(page, pages.withEdits(page, edits), edits, undo, source = this)
    }

    override fun recolor(argb: Int, followsTheme: Boolean): InkSelectionChange? {
        val sources = rows.strokes.filter { it.id in targetIds && (it.colorArgb != argb || it.colorFollowsTheme != followsTheme) }
        if (sources.isEmpty()) return null
        val edit = InkEdit.SetStrokeColors(sources.associate { it.id to StrokeColor(argb, followsTheme) })
        val undo = InkEdit.SetStrokeColors(sources.associate { it.id to StrokeColor(it.colorArgb, it.colorFollowsTheme) })
        val after = pages.withEdits(page, listOf(edit))
        val selected = pages.pieces(after).filter { it.id in targetIds }.selectWithLasso(held.path)
        return InkSelectionChange(page, after, listOf(edit), listOf(undo),
            selected?.let { WebInkSelection(pages, after, it) }, source = this)
    }

    override fun copy(): InkClipboard = WebInkClipboard(pages, page, held)

    override fun group(): InkSelectionChange? {
        if (targetIds.size < 2 || isGrouped) return null
        val groupId = newId()
        if (pages.pieces(page).any { it.id in targetIds && it.key !in held.projections }) {
            // A stored group belongs to rows. Give selected fragments independent rows first so
            // grouping them cannot pull an unselected sibling into future selections and moves.
            val removed = delete() ?: return null
            val copied = copy().paste(removed.after, 0f, 0f) ?: return null
            val ids = copied.selection?.targetIds ?: return null
            val edit = InkEdit.SetStrokeGroups(ids.associateWith { groupId })
            val after = pages.withEdits(copied.after, listOf(edit))
            val selected = pages.pieces(after).filter { it.id in ids }.selectWithLasso(held.bounds.heldLassoPath()) ?: return null
            return InkSelectionChange(page, after, listOf(InkEdit.Batch(removed.edits + copied.edits + edit)),
                listOf(InkEdit.Batch(copied.undo + removed.undo)), WebInkSelection(pages, after, selected), source = this,
                redo = listOf(InkEdit.Batch(removed.redo + copied.redo + edit)))
        }
        return setGroup(groupId)
    }

    override fun ungroup(): InkSelectionChange? = if (isGrouped) setGroup(null) else null

    private fun setGroup(group: String?): InkSelectionChange? {
        val sources = rows.strokes.filter { it.id in targetIds }
        if (sources.isEmpty() || sources.all { it.groupId == group }) return null
        val edit = InkEdit.SetStrokeGroups(sources.associate { it.id to group })
        val undo = InkEdit.SetStrokeGroups(sources.associate { it.id to it.groupId })
        val after = pages.withEdits(page, listOf(edit))
        return InkSelectionChange(page, after, listOf(edit), listOf(undo),
            WebInkSelection(pages, after, held.copy(path = held.bounds.heldLassoPath())), source = this)
    }
}

/**
 * Desktop's `DesktopInkClipboard`: pasting copies the held rows with every operation that shaped
 * them, so cut pieces come out cut, then erases the copies' unheld pieces and moves the rest.
 */
private class WebInkClipboard(private val pages: WebInkPages, original: InkPage, private val held: InkLassoSelection) :
    InkClipboard {
    override val bounds = held.bounds.let { InkSelectionBounds(it.left, it.top, it.right, it.bottom) }
    private val rows = pages.rows(original)
    private val sources = rows.strokes.filter { it.id in held.targetIds }
    private val erases = rows.erases.filter { row -> row.deletedAt == null && row.targetIds.any { it in held.targetIds } }
    private val moves = rows.moves.filter { row -> row.deletedAt == null && row.targetIds.any { it in held.targetIds } }

    override fun paste(page: InkPage, dx: Float, dy: Float): InkSelectionChange? {
        if (!dx.isFinite() || !dy.isFinite() || sources.isEmpty()) return null
        val groups = mutableMapOf<String, String>()
        val ids = sources.associate { it.id to newId() }
        val copiedIds = ids.values.toSet()
        var clock = operationClock(page)
        val edits = sources.mapTo(mutableListOf<InkEdit>()) { source ->
            InkEdit.AddStroke(source.copy(id = ids.getValue(source.id), pageId = page.pageId, seq = 0, createdAt = clock,
                groupId = source.groupId?.let { groups.getOrPut(it, ::newId) }, deletedAt = null).toEntity())
        }
        // The copies' own operations, in the originals' order, each at the next tick of the clock.
        val operations: List<Triple<Long, String, (Long) -> InkEdit?>> = erases.map { row ->
            Triple(row.createdAt, row.id) { at: Long ->
                EraserMode.entries.firstOrNull { it.name == row.mode }?.let { mode ->
                    InkEdit.PartialErase(InkEraseEntity(newId(), page.pageId, mode, row.sizeDp, row.points, row.enc, at),
                        row.targetIds.mapNotNull { ids[it] }.toSet())
                }
            }
        } + moves.map { row ->
            Triple(row.createdAt, row.id) { at: Long ->
                InkEdit.Move(InkMoveEntity(newId(), page.pageId, row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX,
                    row.anchorY, row.points, row.enc, at), row.targetIds.mapNotNull { ids[it] }.toSet())
            }
        }
        operations.sortedWith(compareBy({ it.first }, { it.second })).forEach { (_, _, copy) ->
            copy(clock)?.let { edits += it; clock++ }
        }
        var after = pages.withEdits(page, edits)
        val copies = pages.pieces(after).filter { it.id in copiedIds }
        val wanted = copies.selectWithLasso(held.path) ?: return null
        val (masks, wholeRows) = pages.planDelete(copies, copies.map { it.key }.filterNot { it in wanted.projections }.toSet())
        if (wholeRows.isNotEmpty()) {
            masks.forEach { it.mask.close() }
            return null
        }
        val maskEdits = masks.map { pages.pieceErase(it, page.pageId, clock++) }
        if (maskEdits.isNotEmpty()) {
            edits += maskEdits
            after = pages.withEdits(after, maskEdits)
        }
        val remaining = pages.pieces(after).filter { it.id in copiedIds }.selectWithLasso(held.path) ?: return null
        var selection = WebInkSelection(pages, after, remaining)
        selection.move(dx, dy)?.let { move ->
            edits += move.edits
            after = move.after
            selection = move.selection as? WebInkSelection ?: return null
        }
        val changes = edits.filterNot { it is InkEdit.AddStroke }
        val undo = changes.asReversed().map { edit -> when (edit) {
            is InkEdit.PartialErase -> edit.copy(active = false)
            is InkEdit.Move -> edit.copy(active = false)
            else -> error("Unexpected clipboard operation")
        } } + InkEdit.EraseStrokes(copiedIds)
        return InkSelectionChange(page, after, listOf(InkEdit.Batch(edits.toList())), listOf(InkEdit.Batch(undo)), selection,
            redo = listOf(InkEdit.Batch(listOf(InkEdit.RestoreStrokes(copiedIds, after)) + changes)))
    }
}
