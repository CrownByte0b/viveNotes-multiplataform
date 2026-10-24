package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.PendingDrawEquation

@Composable
internal fun EquationObjectTools(equation: Outline.Equation, onChange: (PendingDrawEquation) -> Unit) {
    var open by remember(equation.id) { mutableStateOf(false) }
    Box {
        TooltipIconButton(Strings.edit_equation, onClick = { open = true }, enabled = equation.lockGroup == null,
            tooltipPosition = TooltipAnchorPosition.Above, modifier = Modifier.size(32.dp).testTag("draw-equation-edit")) {
            Icon(ObjectSymbols.EditSquare, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(open, { open = false }) {
            EquationKit(equation.latex) { onChange(it); open = false }
        }
    }
}
