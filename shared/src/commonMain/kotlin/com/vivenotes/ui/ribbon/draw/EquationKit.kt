package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.workspace.PendingDrawEquation
import io.ratex.compose.RaTeX
import io.ratex.compose.rememberBlockingRaTeXDisplayList
import io.ratex.measure

internal const val EXAMPLE_EQUATION = "{\\displaystyle \\int _{a}^{b}f'(t)\\,dt=f(b)-f(a)}"
internal object EquationTags {
    const val Tool = "draw-equation"
    const val Kit = "draw-equation-kit"
    const val Source = "equation-source"
    const val Preview = "equation-preview"
    const val Submit = "equation-submit"
}

@Composable
internal fun EquationKit(existing: String? = null, onSubmit: (PendingDrawEquation) -> Unit) {
    var source by remember(existing) { mutableStateOf(existing ?: EXAMPLE_EQUATION) }
    val parsed = rememberBlockingRaTeXDisplayList(source, color = MaterialTheme.colorScheme.onSurface)
    val display = parsed.getOrNull()
    val density = LocalDensity.current
    ToolKit(if (existing == null) "Insert equation" else "Edit equation") {
        OutlinedTextField(source, { source = it }, label = { Text("LaTeX") }, minLines = 2, maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag(EquationTags.Source))
        Text("Preview", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(EquationTags.Preview)) {
            if (display != null) RaTeX(displayList = display, fontSize = 20.sp)
        }
        parsed.exceptionOrNull()?.let { Text(it.message ?: "Unable to render this equation", color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (display != null) {
                val size = display.measure(with(density) { 20.dp.toPx() })
                onSubmit(PendingDrawEquation(source.trim(), size.widthPx / density.density, size.totalHeightPx / density.density))
            }
        }, enabled = source.isNotBlank() && display != null, modifier = Modifier.testTag(EquationTags.Submit)) {
            Text(if (existing == null) "Place on page" else "Update")
        }
    }
}

@Composable
internal fun EquationContent(latex: String, color: Color, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val display = rememberBlockingRaTeXDisplayList(latex, color = color).getOrNull()
    if (display == null) Text(latex, color = color, modifier = modifier)
    else Layout(content = { RaTeX(displayList = display, fontSize = 20.sp) }, modifier = modifier) { children, constraints ->
        val child = children.single().measure(Constraints())
        val scale = if (constraints.hasBoundedWidth) minOf(1f, constraints.maxWidth.toFloat() / child.width.coerceAtLeast(1)) else 1f
        val width = if (stretch && constraints.hasBoundedWidth) constraints.maxWidth else (child.width * scale).toInt()
        val height = if (stretch && constraints.hasBoundedHeight) constraints.maxHeight else (child.height * scale).toInt()
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            child.placeWithLayer(0, 0) {
                scaleX = width.toFloat() / child.width.coerceAtLeast(1)
                scaleY = height.toFloat() / child.height.coerceAtLeast(1)
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
