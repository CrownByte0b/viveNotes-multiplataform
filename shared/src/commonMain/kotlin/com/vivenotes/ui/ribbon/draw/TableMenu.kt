package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.canAddColumn
import com.vivenotes.model.canAddRow
import com.vivenotes.model.canRemoveColumn
import com.vivenotes.model.canRemoveRow
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.components.ContextSubmenu
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.ui.icons.DrawSymbols
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.TABLE_GUTTER
import com.vivenotes.workspace.TableEdit
import com.vivenotes.workspace.TableSelectionScope

/** Semantics identifiers for a table's rows in the right-click menus. */
object TableMenuTags {
    const val Copy = "table-menu-copy"
    const val Select = "table-menu-select"
    const val SelectCell = "table-menu-select-cell"
    const val SelectRow = "table-menu-select-row"
    const val SelectColumn = "table-menu-select-column"
    const val SelectTable = "table-menu-select-table"
    const val Insert = "table-menu-insert"
    const val InsertRowAbove = "table-menu-insert-row-above"
    const val InsertRowBelow = "table-menu-insert-row-below"
    const val InsertColumnLeft = "table-menu-insert-column-left"
    const val InsertColumnRight = "table-menu-insert-column-right"
    const val DeleteRow = "table-menu-delete-row"
    const val DeleteColumn = "table-menu-delete-column"
}

/** The row and column of [table] at page point [point], with its rows drawn [rows] high; null off the grid. */
internal fun Outline.Table.gridCellAt(rows: List<Float>, point: Offset): Pair<Int, Int>? {
    var left = x + TABLE_GUTTER
    val column = columns.indexOfFirst { width -> (point.x >= left && point.x < left + width).also { left += width } }
    var top = y + TABLE_GUTTER
    val row = rows.indexOfFirst { height -> (point.y >= top && point.y < top + height).also { top += height } }
    return if (row < 0 || column < 0) null else row to column
}

/**
 * Select ▸ in a cell's menu: the cell's text, or all the text of its row, its column or the table.
 * [onSelect] receives null for the cell itself.
 */
@Composable
internal fun ColumnScope.TableSelectMenuItems(table: Outline.Table, selectAllKeys: String?, onSelect: (TableSelectionScope?) -> Unit) {
    ContextSubmenu(Strings.select, ContextSymbols.SelectAll, Modifier.testTag(TableMenuTags.Select)) {
        ContextMenuItem(Strings.select_cell, ContextSymbols.SelectAll, shortcut = selectAllKeys,
            onClick = { onSelect(null) }, modifier = Modifier.testTag(TableMenuTags.SelectCell))
        ContextMenuItem(Strings.select_row, ObjectSymbols.TableRows,
            onClick = { onSelect(TableSelectionScope.Row) }, modifier = Modifier.testTag(TableMenuTags.SelectRow))
        ContextMenuItem(Strings.select_column, ObjectSymbols.ViewColumn,
            onClick = { onSelect(TableSelectionScope.Column) }, modifier = Modifier.testTag(TableMenuTags.SelectColumn))
        ContextMenuItem(Strings.select_table, DrawSymbols.Table,
            onClick = { onSelect(TableSelectionScope.Table) }, modifier = Modifier.testTag(TableMenuTags.SelectTable))
    }
}

/**
 * Insert ▸, Delete row and Delete column for the cell at [row], [column] of [table], shared by a
 * cell's menu and the object menu of a table that is locked or holds only ink. Inserting stops at
 * the table's limits, and the last row or column is not deleted; nothing applies on a page that is
 * not [editable].
 */
@Composable
internal fun ColumnScope.TableGridMenuItems(table: Outline.Table, row: Int, column: Int, editable: Boolean, onEdit: (TableEdit) -> Unit) {
    ContextSubmenu(Strings.insert, NavigationSymbols.Add, Modifier.testTag(TableMenuTags.Insert), enabled = editable) {
        ContextMenuItem(Strings.insert_row_above, ObjectSymbols.AddRowAbove, enabled = table.canAddRow,
            onClick = { onEdit(TableEdit.InsertRow(table.id, row)) }, modifier = Modifier.testTag(TableMenuTags.InsertRowAbove))
        ContextMenuItem(Strings.insert_row_below_short, ObjectSymbols.AddRowBelow, enabled = table.canAddRow,
            onClick = { onEdit(TableEdit.InsertRow(table.id, row + 1)) }, modifier = Modifier.testTag(TableMenuTags.InsertRowBelow))
        ContextMenuItem(Strings.insert_column_left, ObjectSymbols.AddColumnLeft, enabled = table.canAddColumn,
            onClick = { onEdit(TableEdit.InsertColumn(table.id, column)) }, modifier = Modifier.testTag(TableMenuTags.InsertColumnLeft))
        ContextMenuItem(Strings.insert_column_right_short, ObjectSymbols.AddColumnRight, enabled = table.canAddColumn,
            onClick = { onEdit(TableEdit.InsertColumn(table.id, column + 1)) }, modifier = Modifier.testTag(TableMenuTags.InsertColumnRight))
    }
    ContextMenuItem(Strings.delete_row, ObjectSymbols.Remove, enabled = editable && table.canRemoveRow,
        onClick = { onEdit(TableEdit.DeleteRow(table.id, row)) }, modifier = Modifier.testTag(TableMenuTags.DeleteRow))
    ContextMenuItem(Strings.delete_column, ObjectSymbols.Remove, enabled = editable && table.canRemoveColumn,
        onClick = { onEdit(TableEdit.DeleteColumn(table.id, column)) }, modifier = Modifier.testTag(TableMenuTags.DeleteColumn))
}
