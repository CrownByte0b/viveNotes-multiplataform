package com.vivenotes.data

import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.model.ink.InkPage

/** Immutable ink commands; undo toggles stored rows rather than rewriting their inputs. */
sealed interface InkEdit {
    data class AddStroke(val row: InkStrokeEntity,
        val geometry: com.vivenotes.model.ink.InkStrokeGeometry? = null) : InkEdit
    data class EraseStrokes(val ids: Set<String>) : InkEdit
    data class RestoreStrokes(val ids: Set<String>, val source: InkPage) : InkEdit
    data class PartialErase(val row: InkEraseEntity, val targetIds: Set<String>, val active: Boolean = true) : InkEdit
    data class Move(val row: com.vivenotes.data.db.InkMoveEntity, val targetIds: Set<String>, val active: Boolean = true) : InkEdit
}

data class PendingInkEdit(val id: String, val pageId: String, val edit: InkEdit)

interface InkWriter : InkSource {
    suspend fun applyInkEdit(pageId: String, edit: InkEdit)
    fun overlayPendingInk(page: com.vivenotes.model.ink.InkPage, edits: List<InkEdit>): com.vivenotes.model.ink.InkPage
}
