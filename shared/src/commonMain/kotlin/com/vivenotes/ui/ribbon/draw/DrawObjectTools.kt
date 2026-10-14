package com.vivenotes.ui.ribbon.draw

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
        TextButton({ open = true }, Modifier.testTag("draw-table-edit")) { Text("Table") }
        ScaledDropdownMenu(open, { open = false }) {
            listOf("add-row" to "Add row", "remove-row" to "Remove last row",
                "add-column" to "Add column", "remove-column" to "Remove last column",
                "header-row" to "Header row", "header-column" to "Header column").forEach { (action, label) ->
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
        TextButton({ open = true }, enabled = equation.lockGroup == null, modifier = Modifier.testTag("draw-equation-edit")) { Text("Edit") }
        ScaledDropdownMenu(open, { open = false }) {
            EquationKit(equation.latex) { onChange(it); open = false }
        }
    }
}
