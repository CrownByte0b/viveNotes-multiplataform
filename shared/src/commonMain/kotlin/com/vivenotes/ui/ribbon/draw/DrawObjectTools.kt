package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.GraphSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.workspace.PendingDrawEquation

internal object EquationToolkitTags {
    const val EDIT = "draw-equation-edit"
    const val MATH = "object-tooltip-equation-math"
    const val GRAPH = "object-tooltip-equation-graph"
}

/**
 * The equation's half of the toolkit: edit its LaTeX, open it in the Math pane, and — when the engine
 * says it has a curve — place its graph below it.
 *
 * [onOpenInMath] is null where there is no pane to open, and then there is no Math button.
 */
@Composable
internal fun EquationObjectTools(
    equation: Outline.Equation,
    onChange: (PendingDrawEquation) -> Unit,
    graphable: GraphableFormulas = GraphableFormulas.None,
    onGraph: (Outline.Equation) -> Unit = {},
    onOpenInMath: ((Outline.Equation) -> Unit)? = null,
) {
    var open by remember(equation.id) { mutableStateOf(false) }
    Box {
        TooltipIconButton(Strings.edit_equation, onClick = { open = true }, enabled = equation.lockGroup == null,
            tooltipPosition = TooltipAnchorPosition.Above, modifier = Modifier.size(32.dp).testTag(EquationToolkitTags.EDIT)) {
            Icon(ObjectSymbols.EditSquare, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        ScaledDropdownMenu(open, { open = false }) {
            EquationKit(equation.latex) { onChange(it); open = false }
        }
    }
    onOpenInMath?.let { openInMath -> EquationMathAction(onOpen = { openInMath(equation) }) }
    val canGraph by produceState(
        initialValue = graphable.known(equation.latex) ?: false,
        equation.latex,
        graphable,
    ) {
        value = graphable.check(equation.latex)
    }
    if (canGraph) EquationGraphAction(onGraph = { onGraph(equation) })
}

/**
 * Opens the held equation in the Math pane — solve, simplify, graph and the rest, on a formula that
 * is already typed rather than one read off ink.
 *
 * Dressed as ink's Math button on purpose, Σ and "Math": it is the same pane and the same engine, so
 * it is the same button. Unlike that one it needs no formula model, so it is never absent for want
 * of one.
 */
@Composable
private fun EquationMathAction(onOpen: () -> Unit) {
    val foreground = Color(0xFFE8EAED)
    HoverTooltip(Strings.math, position = TooltipAnchorPosition.Above) {
        TextButton(onClick = onOpen,
            colors = ButtonDefaults.textButtonColors(contentColor = foreground),
            contentPadding = PaddingValues(horizontal = 7.dp),
            modifier = Modifier.height(32.dp).testTag(EquationToolkitTags.MATH)) {
            Icon(ObjectSymbols.Functions, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(Strings.math, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Places a graph of the held equation below it — the Math pane's Graph result, for a formula that
 * is already on the page.
 *
 * Absent until the engine has said the formula is one it can graph, by the toolkit's rule that an
 * action a kind cannot perform is missing rather than dead: a matrix or an integral has no curve.
 */
@Composable
private fun EquationGraphAction(onGraph: () -> Unit) {
    TooltipIconButton(Strings.tooltip_graph_equation, onClick = onGraph,
        tooltipPosition = TooltipAnchorPosition.Above, modifier = Modifier.size(32.dp).testTag(EquationToolkitTags.GRAPH)) {
        Icon(GraphSymbols.AddChart, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
