package com.vivenotes.workspace

/**
 * One change to one table on the open page, as the Table Class toolkit and its gutter handles ask
 * for it (Android's `NotesViewModel` table verbs).
 *
 * Rows and columns are addressed by index; the caller resolves which one from the held handle.
 * A lock stops what moves or resizes the table — dragging a column wider or a row taller — and
 * nothing else, so inserting, deleting, the border and the fill still apply to a locked table.
 */
sealed interface TableEdit {
    val tableId: String

    data class InsertRow(override val tableId: String, val at: Int) : TableEdit
    data class DeleteRow(override val tableId: String, val at: Int) : TableEdit
    data class InsertColumn(override val tableId: String, val at: Int) : TableEdit
    data class DeleteColumn(override val tableId: String, val at: Int) : TableEdit

    /** A drag on a column handle; only its first step is recorded, so one drag is one undo. */
    data class ColumnWidth(override val tableId: String, val column: Int, val width: Float,
                           val recordHistory: Boolean = true) : TableEdit

    /** A drag on a row handle: the row's floor, never its height, which its text may exceed. */
    data class RowMinHeight(override val tableId: String, val row: Int, val minHeight: Float,
                            val recordHistory: Boolean = true) : TableEdit

    data class BorderWidth(override val tableId: String, val width: Int) : TableEdit
    data class Fill(override val tableId: String, val argb: Int?) : TableEdit
    data class HeaderRow(override val tableId: String, val on: Boolean) : TableEdit
    data class HeaderColumn(override val tableId: String, val on: Boolean) : TableEdit

    /** The opt-in debug line for this edit: once per handle drag, never with cell contents. */
    val logLine: String?
        get() = when (this) {
            is InsertRow -> "insert row at=$at"
            is DeleteRow -> "delete row at=$at"
            is InsertColumn -> "insert column at=$at"
            is DeleteColumn -> "delete column at=$at"
            is ColumnWidth -> if (recordHistory) "resize column=$column" else null
            is RowMinHeight -> if (recordHistory) "resize row=$row" else null
            is BorderWidth -> "border width=$width"
            is Fill -> "fill ${if (argb == null) "none" else "set"}"
            is HeaderRow -> "header row=$on"
            is HeaderColumn -> "header column=$on"
        }
}
