package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.ribbon.RibbonCommand
import com.vivenotes.ui.ribbon.RibbonDivider
import com.vivenotes.workspace.InkBrush
import com.vivenotes.workspace.InkHighlighterSettings
import com.vivenotes.workspace.InkPenSettings
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool

internal object InkControlsTags {
    const val Brush = "draw-ink-brush"
    const val Width = "draw-ink-width"
    const val CustomWidth = "draw-ink-custom-width"
    const val Color = "draw-ink-color"
    const val Automatic = "draw-ink-color-auto"
    const val CustomColor = "draw-ink-custom-color"
    fun brush(brush: InkBrush) = "draw-ink-brush-${brush.name}"
    fun width(width: Float) = "draw-ink-width-$width"
    fun color(argb: Int) = "draw-ink-color-$argb"
}

internal val InkColors = listOf(
    0xff000000 to "Black", 0xffffffff to "White", 0xffe01b24 to "Red", 0xffff7800 to "Orange",
    0xffffe000 to "Yellow", 0xff2ec27e to "Green", 0xff3584e4 to "Blue", 0xff9141ac to "Purple",
    0xff00acc1 to "Cyan", 0xffd81b60 to "Pink",
).map { (color, label) -> color.toInt() to label }

internal fun parseInkColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return hex.toIntOrNull(16)?.or(0xff000000.toInt())
}

private fun Float.widthLabel() = toString().removeSuffix(".0")

@Composable
internal fun InkControls(tool: InkTool, settings: InkSettings, onChange: (InkSettings) -> Unit) {
    val highlighter = tool == InkTool.Highlighter
    RibbonDivider()
    if (!highlighter) InkBrushPicker(settings.pen.brush) {
        onChange(settings.copy(pen = settings.pen.copy(brush = it)))
    }
    val size = if (highlighter) settings.highlighter.sizeDp else settings.pen.sizeDp
    InkWidthPicker(size, highlighter) {
        onChange(if (highlighter) settings.copy(highlighter = settings.highlighter.copy(sizeDp = it))
            else settings.copy(pen = settings.pen.copy(sizeDp = it)))
    }
    InkColorPicker(if (highlighter) settings.highlighter.colorArgb else settings.pen.colorArgb,
        automatic = !highlighter && settings.pen.colorFollowsTheme, allowAutomatic = !highlighter) { color ->
        onChange(if (highlighter) settings.copy(highlighter = settings.highlighter.copy(colorArgb = requireNotNull(color)))
            else settings.copy(pen = settings.pen.copy(colorArgb = color ?: settings.pen.colorArgb,
                colorFollowsTheme = color == null)))
    }
    RibbonDivider()
}

@Composable
private fun InkBrushPicker(brush: InkBrush, onChange: (InkBrush) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HoverTooltip("Ink brush") {
            RibbonCommand(brush.label, onClick = { open = true }, dropdown = true,
                modifier = Modifier.testTag(InkControlsTags.Brush).semantics { contentDescription = "Ink brush" })
        }
        ScaledDropdownMenu(open, { open = false }) {
            InkBrush.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { open = false; onChange(choice) },
                    trailingIcon = { if (brush == choice) Text("✓") },
                    modifier = Modifier.testTag(InkControlsTags.brush(choice)).semantics { selected = brush == choice })
            }
        }
    }
}

@Composable
private fun InkWidthPicker(sizeDp: Float, highlighter: Boolean, onChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember(sizeDp, open) { mutableStateOf(sizeDp.widthLabel()) }
    val range = if (highlighter) InkHighlighterSettings.MIN_SIZE..InkHighlighterSettings.MAX_SIZE
        else InkPenSettings.MIN_SIZE..InkPenSettings.MAX_SIZE
    val valid = custom.toFloatOrNull()?.takeIf { it.isFinite() && it in range }
    val presets = if (highlighter) listOf(2f, 4f, 8f, 12f, 18f, 24f, 32f, 48f, 64f, 96f)
        else listOf(0.5f, 1f, 2f, 3f, 4f, 6f, 8f, 12f, 16f, 24f, 32f, 48f)
    Box {
        HoverTooltip("Ink width") {
            RibbonCommand("${sizeDp.widthLabel()} dp", onClick = { open = true }, dropdown = true,
                modifier = Modifier.testTag(InkControlsTags.Width).semantics { contentDescription = "Ink width" })
        }
        ScaledDropdownMenu(open, { open = false }) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, onValueChange = { custom = it }, label = { Text("Width (dp)") },
                    singleLine = true, isError = valid == null,
                    modifier = Modifier.size(140.dp, 58.dp).testTag(InkControlsTags.CustomWidth))
                TextButton(onClick = { valid?.let { open = false; onChange(it) } }, enabled = valid != null,
                    modifier = Modifier.testTag("${InkControlsTags.CustomWidth}-apply")) { Text("Apply") }
            }
            Text("${range.start.widthLabel()}–${range.endInclusive.widthLabel()} dp",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            presets.forEach { width ->
                DropdownMenuItem(text = { Text("${width.widthLabel()} dp") }, onClick = { open = false; onChange(width) },
                    trailingIcon = { if (sizeDp == width) Text("✓") },
                    modifier = Modifier.testTag(InkControlsTags.width(width)).semantics { selected = sizeDp == width })
            }
        }
    }
}

@Composable
private fun InkColorPicker(argb: Int, automatic: Boolean, allowAutomatic: Boolean, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hex by remember(argb, open) { mutableStateOf((argb and 0xffffff).toString(16).padStart(6, '0')) }
    val parsed = parseInkColor(hex)
    val swatch = if (automatic) MaterialTheme.colorScheme.onSurface else Color(argb).copy(alpha = 1f)
    Box {
        HoverTooltip("Ink color") {
            RibbonCommand(if (automatic) "Automatic" else "Color", onClick = { open = true }, dropdown = true,
                modifier = Modifier.testTag(InkControlsTags.Color).semantics { contentDescription = "Ink color" },
                icon = { Box(Modifier.size(16.dp).background(swatch, MaterialTheme.shapes.extraSmall)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)) })
        }
        ScaledDropdownMenu(open, { open = false }) {
            if (allowAutomatic) DropdownMenuItem(text = { Text("Automatic") },
                onClick = { open = false; onChange(null) }, trailingIcon = { if (automatic) Text("✓") },
                modifier = Modifier.testTag(InkControlsTags.Automatic).semantics { selected = automatic })
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InkColors.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (color, name) ->
                            val chosen = !automatic && (argb and 0xffffff) == (color and 0xffffff)
                            HoverTooltip(name) {
                                Box(Modifier.size(32.dp).background(Color(color), MaterialTheme.shapes.small)
                                    .border(if (chosen) 2.dp else 1.dp,
                                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        MaterialTheme.shapes.small)
                                    .clickable(role = Role.Button) { open = false; onChange(color) }
                                    .testTag(InkControlsTags.color(color))
                                    .semantics { contentDescription = "$name ink"; selected = chosen })
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(hex, onValueChange = { if (it.length <= 7) hex = it },
                        label = { Text("Hex color") }, singleLine = true, isError = parsed == null,
                        modifier = Modifier.size(140.dp, 58.dp).testTag(InkControlsTags.CustomColor))
                    TextButton(onClick = { parsed?.let { open = false; onChange(it) } }, enabled = parsed != null,
                        modifier = Modifier.testTag("${InkControlsTags.CustomColor}-apply")) { Text("Apply") }
                }
            }
        }
    }
}
