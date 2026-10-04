package com.vivenotes.data

import com.vivenotes.data.db.InkStrokeEntity

/** One append or whole-row tombstone; existing inputs and operation bytes remain immutable. */
sealed interface InkEdit {
    data class AddStroke(val row: InkStrokeEntity,
        val geometry: com.vivenotes.model.ink.InkStrokeGeometry? = null) : InkEdit
    data class EraseStrokes(val ids: Set<String>) : InkEdit
}

data class PendingInkEdit(val id: String, val pageId: String, val edit: InkEdit)

interface InkWriter : InkSource {
    suspend fun applyInkEdit(pageId: String, edit: InkEdit)
    fun overlayPendingInk(page: com.vivenotes.model.ink.InkPage, edits: List<InkEdit>): com.vivenotes.model.ink.InkPage
}
