package com.vivenotes.ink

import com.vivenotes.byteink.kit.*
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelection
import com.vivenotes.model.ink.InkSelectionBounds
import com.vivenotes.model.ink.InkSelectionChange
import com.vivenotes.model.newId

internal fun InkBounds.selectionPath(): List<com.vivenotes.byteink.kit.InkPoint> = listOf(
    com.vivenotes.byteink.kit.InkPoint(left, top), com.vivenotes.byteink.kit.InkPoint(right, top),
    com.vivenotes.byteink.kit.InkPoint(right, bottom), com.vivenotes.byteink.kit.InkPoint(left, bottom))

internal class DesktopInkSelection(override val page: InkPage, val held: InkLassoSelection) : InkSelection {
    override val bounds = held.bounds.let { InkSelectionBounds(it.left, it.top, it.right, it.bottom) }
    override val targetIds get() = held.targetIds

    override fun move(dx: Float, dy: Float): InkSelectionChange? {
        if (!dx.isFinite() || !dy.isFinite()) return null
        val delta = PageBounds.clampTranslation(held.bounds, dx, dy)
        if (delta.x == 0f && delta.y == 0f) return null
        val row = ViveInkCodec.encodeMove(InkLassoMove(held.path, held.targetIds, held.projections, delta.x, delta.y),
            newId(), page.pageId, maxOf(System.currentTimeMillis(), Math.addExact(page.latestOperationAt, 1L)))
        val edit = InkEdit.Move(InkMoveEntity(row.id, page.pageId, row.dxDp, row.dyDp, points = row.points,
            enc = row.enc, createdAt = row.createdAt), held.targetIds)
        val after = page.withDesktopEdit(edit)
        val path = held.path.map { com.vivenotes.byteink.kit.InkPoint(it.x + delta.x, it.y + delta.y) }
        val selected = after.desktopGeometry().projections.filter { it.id in held.targetIds }.selectWithLasso(path)
        return InkSelectionChange(page, after, listOf(edit), listOf(edit.copy(active = false)),
            selected?.let { DesktopInkSelection(after, it) }, delta.x, delta.y, this)
    }

    override fun delete(): InkSelectionChange? {
        val plan = page.desktopGeometry().projections.planProjectionDelete(held.projections)
        val edits = mutableListOf<InkEdit>()
        var clock = maxOf(System.currentTimeMillis(), Math.addExact(page.latestOperationAt, 1L))
        plan.erases.forEach { piece ->
            val row = ViveInkCodec.encodeErase(piece.mask, newId(), page.pageId, InkEraseMode.Object, clock++, listOf(piece.rowId))
            edits += InkEdit.PartialErase(InkEraseEntity(row.id, page.pageId, EraserMode.Object,
                row.sizeDp, row.points, row.enc, row.createdAt), setOf(piece.rowId))
        }
        if (plan.wholeRows.isNotEmpty()) edits += InkEdit.EraseStrokes(plan.wholeRows.toSet())
        if (edits.isEmpty()) return null
        val undo = edits.asReversed().map { edit -> when (edit) {
            is InkEdit.PartialErase -> edit.copy(active = false)
            is InkEdit.EraseStrokes -> InkEdit.RestoreStrokes(edit.ids, page)
            else -> error("Unexpected selection delete")
        } }
        return InkSelectionChange(page, page.withDesktopEdits(edits), edits, undo, source = this)
    }
}
