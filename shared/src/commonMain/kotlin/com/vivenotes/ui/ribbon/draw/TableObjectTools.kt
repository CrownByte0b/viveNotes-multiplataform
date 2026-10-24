package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.localization.Strings
import com.vivenotes.model.Outline
import com.vivenotes.model.canAddColumn
import com.vivenotes.model.canAddRow
import com.vivenotes.model.canRemoveColumn
import com.vivenotes.model.canRemoveRow
import com.vivenotes.ui.canvas.SelectionColorPicker
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.TableEdit
import com.vivenotes.workspace.TableToolSettings

/**
 * The Table Class's half of the object toolkit (Android `ObjectTooltip` table extras): the border
 * width, the fill, then the row and column verbs.
 *
 * With a row or a column [held] there is one thing the verbs are about, so they are the two buttons
 * that draw them; with nothing held they are the Rows and Columns menus, acting at the far edge.
 * Inserting goes below or right only, and Delete is absent at the last row or column. The menus
 * also carry the desktop's header toggles, which had lived in the old Table menu.
 */
@Composable
internal fun TableObjectTools(table: Outline.Table, held: TableAxis?, onEdit: (TableEdit) -> Unit, onRelease: () -> Unit) {
    BorderWidthAction(table.borderWidth.toInt(), TableToolSettings.MIN_BORDER_WIDTH..TableToolSettings.MAX_BORDER_WIDTH,
        TableTags.BorderWidth, LocalContentColor.current) { onEdit(TableEdit.BorderWidth(table.id, it)) }
    SelectionColorPicker(onColor = { onEdit(TableEdit.Fill(table.id, it)) }, onNoFill = { onEdit(TableEdit.Fill(table.id, null)) },
        selectedColor = table.fillArgb) { open ->
        TooltipIconButton(Strings.change_fill_color, onClick = open, tooltipPosition = TooltipAnchorPosition.Above,
            modifier = Modifier.size(32.dp).testTag(TableTags.ObjectFill)) {
            val fill = table.fillArgb
            if (fill == null) Icon(ObjectSymbols.Block, contentDescription = null, modifier = Modifier.size(18.dp))
            else Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(fill)))
        }
    }
    when (held) {
        is TableAxis.Row -> {
            AxisButton(TableTags.RowBelow, ObjectSymbols.AddRowBelow, Strings.insert_row_below, enabled = table.canAddRow) {
                onEdit(TableEdit.InsertRow(table.id, held.index + 1))
            }
            if (table.canRemoveRow) AxisButton(TableTags.RowDelete, ObjectSymbols.Remove, Strings.delete_row, tint = DESTRUCTIVE) {
                onEdit(TableEdit.DeleteRow(table.id, held.index))
                onRelease()
            }
        }
        is TableAxis.Column -> {
            AxisButton(TableTags.ColumnRight, ObjectSymbols.AddColumnRight, Strings.insert_column_right, enabled = table.canAddColumn) {
                onEdit(TableEdit.InsertColumn(table.id, held.index + 1))
            }
            if (table.canRemoveColumn) AxisButton(TableTags.ColumnDelete, ObjectSymbols.Remove, Strings.delete_column, tint = DESTRUCTIVE) {
                onEdit(TableEdit.DeleteColumn(table.id, held.index))
                onRelease()
            }
        }
        null -> {
            GridMenu(TableTags.RowMenu, ObjectSymbols.TableRows, Strings.rows, listOfNotNull(
                GridMenuEntry(Strings.insert_below, TableTags.RowBelow, enabled = table.canAddRow) {
                    onEdit(TableEdit.InsertRow(table.id, table.rowCount))
                },
                GridMenuEntry(Strings.delete_row, TableTags.RowDelete) {
                    onEdit(TableEdit.DeleteRow(table.id, table.rowCount - 1))
                }.takeIf { table.canRemoveRow },
            ), GridMenuEntry(Strings.header_row, TableTags.HeaderRow, checked = table.headerRow) {
                onEdit(TableEdit.HeaderRow(table.id, !table.headerRow))
            })
            GridMenu(TableTags.ColumnMenu, ObjectSymbols.ViewColumn, Strings.columns, listOfNotNull(
                GridMenuEntry(Strings.insert_right, TableTags.ColumnRight, enabled = table.canAddColumn) {
                    onEdit(TableEdit.InsertColumn(table.id, table.columnCount))
                },
                GridMenuEntry(Strings.delete_column, TableTags.ColumnDelete) {
                    onEdit(TableEdit.DeleteColumn(table.id, table.columnCount - 1))
                }.takeIf { table.canRemoveColumn },
            ), GridMenuEntry(Strings.header_column, TableTags.HeaderColumn, checked = table.headerColumn) {
                onEdit(TableEdit.HeaderColumn(table.id, !table.headerColumn))
            })
        }
    }
}

@Composable
private fun AxisButton(tag: String, icon: ImageVector, label: String, enabled: Boolean = true,
                       tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    TooltipIconButton(label, onClick = onClick, enabled = enabled, tooltipPosition = TooltipAnchorPosition.Above,
        modifier = Modifier.size(32.dp).testTag(tag)) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = .38f), modifier = Modifier.size(18.dp))
    }
}

private class GridMenuEntry(val label: String, val tag: String, val enabled: Boolean = true,
                            val checked: Boolean? = null, val onClick: () -> Unit)

/** One icon opening the row or column verbs; the two differ only in words. */
@Composable
private fun GridMenu(tag: String, icon: ImageVector, label: String, entries: List<GridMenuEntry>, toggle: GridMenuEntry) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(label, onClick = { open = true }, tooltipPosition = TooltipAnchorPosition.Above,
            modifier = Modifier.size(32.dp).testTag(tag)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(open, { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label) }, enabled = entry.enabled,
                    modifier = Modifier.testTag(entry.tag), onClick = { open = false; entry.onClick() })
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(text = { Text(toggle.label) }, modifier = Modifier.testTag(toggle.tag),
                trailingIcon = { if (toggle.checked == true) Text("✓") },
                onClick = { open = false; toggle.onClick() })
        }
    }
}

/** The trash's red, worn by the minus that deletes one row or column rather than the object. */
private val DESTRUCTIVE = Color(0xFFFFA8A8)
