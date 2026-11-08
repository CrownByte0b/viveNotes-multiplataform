package com.vivenotes.ink

import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkMove
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.data.InkEdit

/**
 * A page's live ink as ByteInk rows, in draw order: what a replay turns into the page's pieces.
 * Each [InkEdit] is a change to these rows; the original point blobs are carried, never rewritten.
 * Desktop applies the same edits incrementally to its decoded page (`withDesktopEdits`); this is
 * their row-level meaning, which a full replay of the result must reproduce.
 */
internal data class InkPageRows(
    val pageId: String,
    val strokes: List<StoredInkStroke> = emptyList(),
    val erases: List<StoredInkErase> = emptyList(),
    val moves: List<StoredInkMove> = emptyList(),
) {
    /**
     * [edits] applied in order. A [InkEdit.RestoreStrokes] takes its rows from [restored], the rows
     * of the page it names.
     */
    fun withEdits(edits: List<InkEdit>, restored: (com.vivenotes.model.ink.InkPage) -> InkPageRows): InkPageRows =
        edits.fold(this) { rows, edit -> rows.withEdit(edit, restored) }

    private fun withEdit(edit: InkEdit, restored: (com.vivenotes.model.ink.InkPage) -> InkPageRows): InkPageRows = when (edit) {
        is InkEdit.Batch -> withEdits(edit.edits, restored)
        is InkEdit.AddStroke -> {
            require(edit.row.pageId == pageId) { "Stroke belongs to another page" }
            if (strokes.any { it.id == edit.row.id }) this else copy(strokes = strokes + edit.row.toByteInk())
        }
        is InkEdit.EraseStrokes -> if (strokes.none { it.id in edit.ids }) this
            else copy(strokes = strokes.filterNot { it.id in edit.ids })
        is InkEdit.RestoreStrokes -> {
            require(edit.source.pageId == pageId) { "Stroke restore belongs to another page" }
            // The source page's order, keeping rows still here as they are now; rows the source
            // never had stay after them.
            val source = restored(edit.source).strokes
            val current = strokes.associateBy { it.id }
            val sourceIds = source.mapTo(HashSet()) { it.id }
            copy(strokes = source.mapNotNull { current[it.id] ?: it.takeIf { it.id in edit.ids } } +
                strokes.filterNot { it.id in sourceIds })
        }
        is InkEdit.PartialErase -> {
            require(edit.row.pageId == pageId) { "Erase belongs to another page" }
            if (edit.active == erases.any { it.id == edit.row.id }) this else copy(erases =
                erases.filterNot { it.id == edit.row.id } + listOfNotNull(edit.row.takeIf { edit.active }?.let { row ->
                    StoredInkErase(row.id, pageId, row.mode.name, row.sizeDp, row.points, row.enc, row.createdAt,
                        targetIds = edit.targetIds.toList())
                }))
        }
        is InkEdit.Move -> {
            require(edit.row.pageId == pageId) { "Move belongs to another page" }
            if (edit.active == moves.any { it.id == edit.row.id }) this else copy(moves =
                moves.filterNot { it.id == edit.row.id } + listOfNotNull(edit.row.takeIf { edit.active }?.let { row ->
                    StoredInkMove(row.id, pageId, row.dxDp, row.dyDp, row.scaleX, row.scaleY, row.anchorX,
                        row.anchorY, row.points, row.enc, row.createdAt, targetIds = edit.targetIds.toList())
                }))
        }
        is InkEdit.SetStrokeColors -> copy(strokes = strokes.map { stroke ->
            edit.colors[stroke.id]?.let { stroke.copy(colorArgb = it.argb, colorFollowsTheme = it.followsTheme) } ?: stroke
        })
        is InkEdit.SetStrokeGroups -> copy(strokes = strokes.map { stroke ->
            if (stroke.id in edit.groups) stroke.copy(groupId = edit.groups[stroke.id]) else stroke
        })
    }
}

/** The page's operation clock after [edits]: inactive operations still advance it, so undo never resets it. */
internal fun List<InkEdit>.latestOperationAt(previous: Long): Long = fold(previous) { latest, edit -> when (edit) {
    is InkEdit.Batch -> edit.edits.latestOperationAt(latest)
    is InkEdit.PartialErase -> maxOf(latest, edit.row.createdAt)
    is InkEdit.Move -> maxOf(latest, edit.row.createdAt)
    else -> latest
} }
