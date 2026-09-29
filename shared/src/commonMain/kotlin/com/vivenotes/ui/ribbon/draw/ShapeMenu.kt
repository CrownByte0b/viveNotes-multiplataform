package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.canvas.drawDocumentShape
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.workspace.ShapeToolSettings
import kotlin.math.roundToInt

internal object ShapeMenuTags {
    const val ShapeTool = "draw-shape-tool"
    fun kind(kind: ShapeKind) = "draw-shape-${kind.name}"
    fun lineType(type: LineType) = "draw-shape-line-${type.name}"
    const val Width = "draw-shape-width"
    fun border(argb: Int) = "draw-shape-border-$argb"
    fun fill(argb: Int?) = "draw-shape-fill-${argb ?: "none"}"
}

internal val ShapeColors = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
    0xFF3584E4.toInt(), 0xFF2EC27E.toInt(), 0xFFE01B24.toInt(),
    0xFFFF7800.toInt(), 0xFF9141AC.toInt(), 0xFFF6D32D.toInt())

/** Accepts RGB and ARGB hex colours from desktop keyboard input. */
internal fun parseShapeColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val number = hex.toLongOrNull(16) ?: return null
    return (if (hex.length == 6) number or 0xFF000000 else number).toInt()
}

@Composable
internal fun CustomShapeColorField(label: String, tag: String, onColor: (Int) -> Unit) {
    var hex by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(hex, onValueChange = { if (it.length <= 9) hex = it },
            label = { Text("$label hex") }, singleLine = true,
            modifier = Modifier.size(width = 140.dp, height = 58.dp).testTag(tag))
        TextButton(onClick = { parseShapeColor(hex)?.let(onColor) },
            enabled = parseShapeColor(hex) != null,
            modifier = Modifier.testTag("$tag-apply")) { Text("Apply") }
    }
}

@Composable
internal fun ShapeMenu(open: Boolean, settings: ShapeToolSettings, onDismiss: () -> Unit,
                       onChange: (ShapeToolSettings) -> Unit) {
    var page by remember { mutableIntStateOf(settings.kind.page) }
    ScaledDropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Column(Modifier.padding(12.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shape", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Flat", "Solid").forEachIndexed { index, label ->
                    Text(label, modifier = Modifier.clickable { page = index }
                        .background(if (page == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(6.dp).testTag("draw-shape-page-$index"))
                }
            }
            ShapeKind.onPage(page).chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { kind ->
                        val shape = remember(kind) { Outline.Shape(id = "preview", kind = kind,
                            segments = seedSegments(kind, 4f, 4f, 28f, 28f) { "preview" }) }
                        Box(Modifier.size(44.dp)
                            .border(1.dp, if (kind == settings.kind) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { onChange(settings.copy(kind = kind)) }
                            .testTag(ShapeMenuTags.kind(kind))
                            .semantics { contentDescription = kind.label },
                            contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(36.dp)) { drawDocumentShape(shape, 0f, 0f) }
                        }
                    }
                }
            }
            Text("Line type", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LineType.entries.forEach { type ->
                    Text(type.label, Modifier
                        .border(1.dp, if (settings.lineType == type) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .clickable { onChange(settings.copy(lineType = type)) }
                        .padding(7.dp).testTag(ShapeMenuTags.lineType(type)))
                }
            }
            Text("Border width: ${settings.borderWidth}", style = MaterialTheme.typography.labelMedium)
            Slider(value = settings.borderWidth.toFloat(), onValueChange = {
                onChange(settings.copy(borderWidth = it.roundToInt()))
            }, valueRange = ShapeToolSettings.MIN_BORDER_WIDTH.toFloat()..
                ShapeToolSettings.MAX_BORDER_WIDTH.toFloat(), steps = 10,
                modifier = Modifier.fillMaxWidth().testTag(ShapeMenuTags.Width))
            Text("Border color", style = MaterialTheme.typography.labelMedium)
            Text("Automatic", Modifier.clickable {
                onChange(settings.copy(colorFollowsTheme = true))
            }.padding(4.dp).testTag("draw-shape-border-auto"))
            ShapeSwatches(ShapeColors, settings.borderArgb) {
                onChange(settings.copy(borderArgb = it, colorFollowsTheme = false))
            }
            CustomShapeColorField("Border", "draw-shape-custom-border") {
                onChange(settings.copy(borderArgb = it, colorFollowsTheme = false))
            }
            Text("Fill", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(26.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clickable { onChange(settings.copy(fillArgb = null)) }
                    .testTag(ShapeMenuTags.fill(null)), contentAlignment = Alignment.Center) { Text("×") }
                ShapeColors.forEach { argb ->
                    Box(Modifier.size(26.dp).background(Color(argb), MaterialTheme.shapes.small)
                        .border(1.dp, if (settings.fillArgb == argb) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .clickable { onChange(settings.copy(fillArgb = argb)) }
                        .testTag(ShapeMenuTags.fill(argb)))
                }
            }
            CustomShapeColorField("Fill", "draw-shape-custom-fill") {
                onChange(settings.copy(fillArgb = it))
            }
        }
    }
}

@Composable
private fun ShapeSwatches(colors: List<Int>, chosen: Int, onChoose: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { argb ->
            Box(Modifier.size(26.dp).background(Color(argb), MaterialTheme.shapes.small)
                .border(1.dp, if (chosen == argb) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .clickable { onChoose(argb) }.testTag(ShapeMenuTags.border(argb)))
        }
    }
}
