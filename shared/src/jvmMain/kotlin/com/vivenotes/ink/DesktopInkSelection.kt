package com.vivenotes.ink

import com.vivenotes.byteink.kit.*
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

internal fun InkBounds.selectionPath(): List<com.vivenotes.byteink.kit.InkPoint> = listOf(
    com.vivenotes.byteink.kit.InkPoint(left, top), com.vivenotes.byteink.kit.InkPoint(right, top),
    com.vivenotes.byteink.kit.InkPoint(right, bottom), com.vivenotes.byteink.kit.InkPoint(left, bottom))

internal class DesktopInkSelection(override val page: InkPage, val held: InkLassoSelection) : InkSelection {
    override val bounds = held.bounds.let { InkSelectionBounds(it.left, it.top, it.right, it.bottom) }
    override val targetIds get() = held.targetIds

    override val isGrouped: Boolean get() = page.desktopGeometry().sourceStrokes.filter { it.id in targetIds }
        .map { it.groupId }.distinct().let { it.size == 1 && it.single() != null }

    override fun forRecognition(): InkPage {
        val pieces = page.desktopGeometry().projections.filter { it.projectionKey in held.projections }
        return InkPage(page.pageId, page.strokes.filter { it.id in targetIds }, geometry = DesktopInkPage(pieces))
    }

    override fun group(): InkSelectionChange? {
        if (targetIds.size < 2 || isGrouped) return null
        val groupId = newId()
        val native = page.desktopGeometry()
        if (native.projections.any { it.id in targetIds && it.projectionKey !in held.projections }) {
            // A stored group belongs to rows. Give selected fragments independent rows first so
            // grouping them cannot pull an unselected sibling into future selections and moves.
            val removed = delete() ?: return null
            val copied = copy().paste(removed.after, 0f, 0f) ?: return null
            val ids = copied.selection?.targetIds ?: return null
            val edit = InkEdit.SetStrokeGroups(ids.associateWith { groupId })
            val after = copied.after.withDesktopEdit(edit)
            val selected = after.desktopGeometry().projections.filter { it.id in ids }
                .selectWithLasso(held.bounds.selectionPath()) ?: return null
            return InkSelectionChange(page, after,
                listOf(InkEdit.Batch(removed.edits + copied.edits + edit)),
                listOf(InkEdit.Batch(copied.undo + removed.undo)), DesktopInkSelection(after, selected), source = this,
                redo = listOf(InkEdit.Batch(removed.redo + copied.redo + edit)))
        }
        return setGroup(groupId)
    }

    override fun ungroup(): InkSelectionChange? = if (isGrouped) setGroup(null) else null

    private fun setGroup(group: String?): InkSelectionChange? {
        val sources = page.desktopGeometry().sourceStrokes.filter { it.id in targetIds }
        if (sources.isEmpty() || sources.all { it.groupId == group }) return null
        val edit = InkEdit.SetStrokeGroups(sources.associate { it.id to group })
        val undo = InkEdit.SetStrokeGroups(sources.associate { it.id to it.groupId })
        val after = page.withDesktopEdit(edit)
        return InkSelectionChange(page, after, listOf(edit), listOf(undo),
            DesktopInkSelection(after, held.copy(path = held.bounds.selectionPath())), source = this)
    }

    override fun copy(): InkClipboard = DesktopInkClipboard(page, held)

    override fun recolor(argb: Int): InkSelectionChange? {
        val sources = page.desktopGeometry().sourceStrokes.filter { it.id in targetIds &&
            (it.stroke.brush.colorIntArgb != argb || it.colorFollowsTheme != false) }
        if (sources.isEmpty()) return null
        val edit = InkEdit.SetStrokeColors(sources.associate { it.id to StrokeColor(argb, false) })
        val undo = InkEdit.SetStrokeColors(sources.associate {
            it.id to StrokeColor(it.stroke.brush.colorIntArgb, it.colorFollowsTheme)
        })
        val after = page.withDesktopEdit(edit)
        val selected = after.desktopGeometry().projections.filter { it.id in targetIds }.selectWithLasso(held.path)
        return InkSelectionChange(page, after, listOf(edit), listOf(undo),
            selected?.let { DesktopInkSelection(after, it) }, source = this)
    }

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

private class DesktopInkClipboard(original: InkPage, private val held: InkLassoSelection) : InkClipboard {
    override val bounds = held.bounds.let { InkSelectionBounds(it.left, it.top, it.right, it.bottom) }
    private val sources = original.desktopGeometry().sourceStrokes.filter { it.id in held.targetIds }
    private val operations = original.desktopGeometry().operations.filter { op -> op.targetIds.any { it in held.targetIds } }
        .sortedWith(compareBy(DecodedInkOperation::createdAt, DecodedInkOperation::id))

    override fun paste(page: InkPage, dx: Float, dy: Float): InkSelectionChange? {
        if (!dx.isFinite() || !dy.isFinite() || sources.isEmpty()) return null
        val groups = mutableMapOf<String, String>()
        val ids = sources.associate { it.id to newId() }
        val copiedIds = ids.values.toSet()
        var clock = maxOf(System.currentTimeMillis(), Math.addExact(page.latestOperationAt, 1L))
        val edits = sources.mapTo(mutableListOf<InkEdit>()) { source ->
            val group = source.groupId?.let { groups.getOrPut(it, ::newId) }
            InkEdit.AddStroke(ViveInkCodec.encodeCopy(source, source.stroke,
                ids.getValue(source.id), page.pageId, 0, clock, group).toEntity())
        }
        operations.forEach { operation ->
            val targets = operation.targetIds.mapNotNull { ids[it] }.toSet()
            edits += when (operation) {
                is DecodedInkOperation.Erase -> {
                    val row = ViveInkCodec.encodeErase(operation.mask, newId(), page.pageId, operation.mode, clock++, targets.toList())
                    InkEdit.PartialErase(InkEraseEntity(row.id, page.pageId,
                        if (operation.mode == InkEraseMode.Object) EraserMode.Object else EraserMode.Normal,
                        row.sizeDp, row.points, row.enc, row.createdAt), targets)
                }
                is DecodedInkOperation.Move -> {
                    val row = ViveInkCodec.encodeMove(InkLassoMove(operation.path, targets, emptySet(), operation.dx, operation.dy),
                        newId(), page.pageId, clock++)
                    InkEdit.Move(InkMoveEntity(row.id, page.pageId, row.dxDp, row.dyDp,
                        operation.scaleX, operation.scaleY, operation.anchor.x, operation.anchor.y,
                        row.points, row.enc, row.createdAt), targets)
                }
            }
        }
        var after = page.withDesktopEdits(edits)
        val copies = after.desktopGeometry().projections.filter { it.id in copiedIds }
        val wanted = copies.selectWithLasso(held.path) ?: return null
        val unselected = copies.filter { it.projectionKey !in wanted.projections }.map { it.projectionKey }.toSet()
        val plan = copies.planProjectionDelete(unselected)
        if (plan.wholeRows.isNotEmpty()) return null
        val masks = plan.erases.map { piece ->
            val row = ViveInkCodec.encodeErase(piece.mask, newId(), page.pageId, InkEraseMode.Object, clock++, listOf(piece.rowId))
            InkEdit.PartialErase(InkEraseEntity(row.id, page.pageId, EraserMode.Object, row.sizeDp,
                row.points, row.enc, row.createdAt), setOf(piece.rowId))
        }
        if (masks.isNotEmpty()) { edits += masks; after = after.withDesktopEdits(masks) }
        val remaining = after.desktopGeometry().projections.filter { it.id in copiedIds }.selectWithLasso(held.path) ?: return null
        var selection = DesktopInkSelection(after, remaining)
        selection.move(dx, dy)?.let { move ->
            edits += move.edits
            after = move.after
            selection = move.selection as DesktopInkSelection
        }
        val operations = edits.filterNot { it is InkEdit.AddStroke }
        val undo = operations.asReversed().map { edit -> when (edit) {
            is InkEdit.PartialErase -> edit.copy(active = false)
            is InkEdit.Move -> edit.copy(active = false)
            else -> error("Unexpected clipboard operation")
        } } + InkEdit.EraseStrokes(copiedIds)
        return InkSelectionChange(page, after, listOf(InkEdit.Batch(edits.toList())), listOf(InkEdit.Batch(undo)), selection,
            redo = listOf(InkEdit.Batch(listOf(InkEdit.RestoreStrokes(copiedIds, after)) + operations)))
    }
}
