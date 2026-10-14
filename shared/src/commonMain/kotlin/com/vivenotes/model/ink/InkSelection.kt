package com.vivenotes.model.ink

import com.vivenotes.data.InkEdit

data class InkSelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Immutable native selection, including the individual pieces of partially erased rows. */
interface InkSelection {
    val page: InkPage
    val bounds: InkSelectionBounds
    val targetIds: Set<String>
    fun move(dx: Float, dy: Float): InkSelectionChange?
    fun delete(): InkSelectionChange?
}

data class InkSelectionChange(
    val before: InkPage,
    val after: InkPage,
    val edits: List<InkEdit>,
    val undo: List<InkEdit>,
    val selection: InkSelection? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val source: InkSelection? = null,
)
