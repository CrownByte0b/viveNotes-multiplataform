package com.vivenotes.workspace

import com.vivenotes.model.Outline
import com.vivenotes.model.newId

/** The canvas clipboard retains complete document objects, including a text box's styled blocks. */
data class CanvasClipboard(
    val texts: List<Outline.Text> = emptyList(),
    val objects: List<Outline> = emptyList(),
) {
    val outlines: List<Outline> get() = texts + objects
    val isEmpty: Boolean get() = texts.isEmpty() && objects.isEmpty()
}

data class StructuralSnapshot(val pageId: String, val document: com.vivenotes.model.PageDoc)

fun Outline.isPrimeObject(): Boolean = when (this) {
    is Outline.Shape, is Outline.Table, is Outline.Equation, is Outline.Image -> true
    is Outline.Text, is Outline.Ink -> false
}

/** A new paste must never reuse outline, block, row, or cell IDs. */
fun Outline.duplicateAt(newX: Float, newY: Float): Outline = when (this) {
    is Outline.Text -> copy(id = newId(), x = newX, y = newY,
        blocks = blocks.map { it.copy(id = newId()) })
    is Outline.Shape -> translated(newX - x, newY - y).copy(id = newId(), lockGroup = null,
        segments = segments.map { it.copy(id = newId()) })
    is Outline.Equation -> copy(id = newId(), x = newX, y = newY, lockGroup = null)
    is Outline.Image -> copy(id = newId(), x = newX, y = newY, lockGroup = null)
    is Outline.Table -> copy(id = newId(), x = newX, y = newY, lockGroup = null,
        rows = rows.map { row -> row.copy(id = newId(), cells = row.cells.map { cell ->
            cell.copy(id = newId(), blocks = cell.blocks.map { it.copy(id = newId()) })
        }) })
    is Outline.Ink -> this
}

fun Outline.withLockGroup(group: String?): Outline = when (this) {
    is Outline.Shape -> copy(lockGroup = group)
    is Outline.Table -> copy(lockGroup = group)
    is Outline.Equation -> copy(lockGroup = group)
    is Outline.Image -> copy(lockGroup = group)
    is Outline.Text, is Outline.Ink -> this
}

fun Outline.movedBy(dx: Float, dy: Float): Outline = when (this) {
    is Outline.Shape -> translated(dx, dy)
    is Outline.Table -> translated(dx, dy)
    is Outline.Equation -> translated(dx, dy)
    is Outline.Image -> translated(dx, dy)
    is Outline.Text, is Outline.Ink -> this
}

fun Outline.primeHeight(): Float = when (this) {
    is Outline.Shape -> height
    is Outline.Table -> height
    is Outline.Equation -> height
    is Outline.Image -> height
    is Outline.Text -> minHeight
    is Outline.Ink -> height
}
