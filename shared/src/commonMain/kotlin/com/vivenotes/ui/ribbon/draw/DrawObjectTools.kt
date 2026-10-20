package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.workspace.PendingDrawEquation

@Composable
internal fun EquationObjectTools(equation: Outline.Equation, onChange: (PendingDrawEquation) -> Unit) {
    var open by remember(equation.id) { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, enabled = equation.lockGroup == null,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(32.dp).testTag("draw-equation-edit")) { Text(Strings.edit) }
        ScaledDropdownMenu(open, { open = false }) {
            EquationKit(equation.latex) { onChange(it); open = false }
        }
    }
}
