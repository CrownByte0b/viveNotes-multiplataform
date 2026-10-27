package com.vivenotes.model.ink

import com.vivenotes.data.InkEdit

@kotlinx.serialization.Serializable
data class InkSelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Immutable native selection, including the individual pieces of partially erased rows. */
interface InkSelection {
    val page: InkPage
    val bounds: InkSelectionBounds
    val targetIds: Set<String>
    val colorArgb: Int? get() = page.strokes.filter { it.id in targetIds }.map { it.colorArgb }.distinct().singleOrNull()
    val colorFollowsTheme: Boolean get() = page.strokes.filter { it.id in targetIds }.let { strokes ->
        strokes.isNotEmpty() && strokes.all { it.colorFollowsTheme == true ||
            it.colorFollowsTheme == null && it.colorArgb in setOf(0xff000000.toInt(), 0xffffffff.toInt()) }
    }
    fun move(dx: Float, dy: Float): InkSelectionChange?
    fun delete(): InkSelectionChange?
    fun recolor(argb: Int, followsTheme: Boolean = false): InkSelectionChange? = null
    fun copy(): InkClipboard? = null
    val isGrouped: Boolean get() = false
    fun group(): InkSelectionChange? = null
    fun ungroup(): InkSelectionChange? = null
    /** A read-only snapshot of the visible selected pieces, including their current transforms. */
    fun forRecognition(): InkPage? = null
}

/** An immutable, platform-owned copy of the visible selected pieces. */
interface InkClipboard {
    val bounds: InkSelectionBounds
    fun paste(page: InkPage, dx: Float, dy: Float): InkSelectionChange?
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
    val redo: List<InkEdit> = edits,
)
