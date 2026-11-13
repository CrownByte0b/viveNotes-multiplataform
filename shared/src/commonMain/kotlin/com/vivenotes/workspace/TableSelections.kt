package com.vivenotes.workspace

import com.vivenotes.model.Outline
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection

/** What a table's Select commands take hold of, counted from the cell they were asked in. */
enum class TableSelectionScope { Row, Column, Table }

/** The typed, unlocked table holding [cellId]: the only kind whose cells are edited. */
internal fun WorkspaceState.editedTable(cellId: String?): Outline.Table? {
    cellId ?: return null
    return selectedPage?.document?.outlines?.firstOrNull { outline ->
        outline is Outline.Table && !outline.inkOnly && outline.lockGroup == null && outline.locate(cellId) != null
    } as? Outline.Table
}

/**
 * Selects all the text of [cellId]'s row, its column or its whole table, as the menus of other
 * editors do: the selection Copy, Delete, typing and the Document ribbon then act on, cell by cell.
 *
 * Every cell of the [scope] is selected as a cell, an empty one too, so the canvas can tint the
 * block. The caret stays in [cellId] when it has text and otherwise moves to the last cell that has,
 * since the editor's keys act on the selection only from a cell with something selected in it.
 */
fun WorkspaceState.selectTableCells(cellId: String, scope: TableSelectionScope): WorkspaceState {
    val page = selectedPage ?: return this
    val table = editedTable(cellId) ?: return this
    val (row, column) = table.locate(cellId) ?: return this
    val cells = when (scope) {
        TableSelectionScope.Row -> table.rows[row].cells
        TableSelectionScope.Column -> table.rows.mapNotNull { it.cells.getOrNull(column) }
        TableSelectionScope.Table -> table.rows.flatMap { it.cells }
    }
    val ranges = cells.mapNotNull { cell ->
        page.document.textIndex[cell.id]?.let {
            SelectedTextRange(cell.id, TextSelection(0, RichTextBuffer(it.blocks).text.length))
        }
    }
    if (ranges.isEmpty()) return this
    val primary = ranges.firstOrNull { it.containerId == cellId && !it.range.collapsed }
        ?: ranges.lastOrNull { !it.range.collapsed }
        ?: ranges.firstOrNull { it.containerId == cellId } ?: ranges.last()
    return focusTextBox(primary.containerId).selectText(primary.range).copy(
        wordSelections = WordSelections(page.id, table.id, ranges - primary + primary, cells = cells.map { it.id }.toSet()),
        typingRun = null,
    )
}

/** The cells of table [tableId] selected whole, by [selectTableCells]; none once that selection is let go. */
fun WorkspaceState.selectedTableCells(tableId: String): Set<String> =
    activeWordSelections?.takeIf { it.ownerId == tableId }?.cells.orEmpty()

/**
 * Select all, from the keyboard, in text container [id]: the container's text, and in a table cell
 * whose text is already all selected, the whole table — the second press reaches further, as in
 * other editors' tables. A table already selected stays selected.
 */
fun WorkspaceState.selectAllFrom(id: String): WorkspaceState {
    val table = editedTable(id) ?: return selectAllTextBox(id)
    if (selectedTableCells(table.id).size == table.rowCount * table.columnCount) return this
    val length = richTextFor(id)?.text?.length ?: return selectAllTextBox(id)
    val whole = focusedTextOutlineId == id && editorSelection.min == 0 && editorSelection.max == length
    return if (whole && table.rowCount * table.columnCount > 1) selectTableCells(id, TableSelectionScope.Table)
        else selectAllTextBox(id)
}

/**
 * The plain text of a selection of whole cells: a line per row and a tab between cells, which a
 * spreadsheet or another table pastes back as cells. A line break inside a cell becomes a space, as
 * it would otherwise start a new row. Null when the selection is not one of cells.
 */
internal fun WorkspaceState.selectedCellsText(): String? {
    val group = activeWordSelections?.takeIf { it.cells.isNotEmpty() } ?: return null
    val table = selectedPage?.document?.outlines?.firstOrNull { it.id == group.ownerId } as? Outline.Table ?: return null
    val index = selectedPage?.document?.textIndex ?: return null
    val lines = table.rows.mapNotNull { row ->
        row.cells.filter { it.id in group.cells }.takeIf { it.isNotEmpty() }?.map { cell ->
            val text = index[cell.id]?.let { RichTextBuffer(it.blocks).text }.orEmpty()
            group.ranges.filter { it.containerId == cell.id }.sortedBy { it.range.min }.joinToString(" ") { target ->
                val range = target.range.clamped(text.length)
                text.substring(range.min, range.max)
            }.replace('\n', ' ').replace('\t', ' ')
        }
    }
    if (lines.all { line -> line.all(String::isEmpty) }) return ""
    return lines.joinToString("\n") { it.joinToString("\t") }
}
