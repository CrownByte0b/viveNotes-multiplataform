package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.vivenotes.model.*
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.workspace.PendingDrawEquation

@Composable
internal fun TableObjectTools(table: Outline.Table, onAction: (String) -> Unit) {
    var open by remember(table.id) { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, Modifier.testTag("draw-table-edit")) { Text(Strings.table) }
        ScaledDropdownMenu(open, { open = false }) {
            listOf("add-row" to Strings.add_row, "remove-row" to Strings.remove_last_row,
                "add-column" to Strings.add_column, "remove-column" to Strings.remove_last_column,
                "header-row" to Strings.header_row, "header-column" to Strings.header_column).forEach { (action, label) ->
                val enabled = table.lockGroup == null && when (action) {
                    "add-row" -> table.canAddRow; "remove-row" -> table.canRemoveRow
                    "add-column" -> table.canAddColumn; "remove-column" -> table.canRemoveColumn
                    else -> true
                }
                DropdownMenuItem(text = { Text(label) }, enabled = enabled,
                    modifier = Modifier.testTag("draw-table-edit-$action"),
                    trailingIcon = { if ((action == "header-row" && table.headerRow) ||
                        (action == "header-column" && table.headerColumn)) Text("✓") },
                    onClick = { onAction(action) })
            }
        }
    }
}

@Composable
internal fun EquationObjectTools(equation: Outline.Equation, onChange: (PendingDrawEquation) -> Unit) {
    var open by remember(equation.id) { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = equation.lockGroup == null, modifier = Modifier.testTag("draw-equation-edit")) { Text(Strings.edit) }
        ScaledDropdownMenu(open, { open = false }) {
            EquationKit(equation.latex) { onChange(it); open = false }
        }
    }
}
