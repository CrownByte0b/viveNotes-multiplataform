package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.workspace.ShapeToolSettings
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.vivenotes.model.ink.ShapeTracing
import com.vivenotes.model.ink.trace
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.workspace.PenColors
import kotlin.math.abs
import kotlin.math.sign

internal object ShapeMenuTags {
    const val ShapeTool = "draw-shape-tool"
    const val Preview = "draw-shape-preview"
    const val Grid = "draw-shape-grid"
    const val Fill = "draw-shape-fill"
    const val BorderWheel = "draw-shape-border-wheel"
    fun kind(kind: ShapeKind) = "draw-shape-${kind.name}"
    fun lineType(type: LineType) = "draw-shape-line-${type.name}"
    const val Width = "draw-shape-width"
    fun border(argb: Int) = "draw-shape-border-$argb"
    fun fill(argb: Int?) = "draw-shape-fill-${argb ?: "none"}"
}

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
            label = { Text(Strings.value_hex(label)) }, singleLine = true,
            modifier = Modifier.size(width = 140.dp, height = 58.dp).testTag(tag))
        TextButton(onClick = { parseShapeColor(hex)?.let(onColor) },
            enabled = parseShapeColor(hex) != null,
            modifier = Modifier.testTag("$tag-apply")) { Text(Strings.apply) }
    }
}

@Composable
internal fun ShapeMenu(open: Boolean, settings: ShapeToolSettings, onDismiss: () -> Unit,
                       palette: List<Int> = PenColors, canvasInk: Color = Color.Black,
                       onPaletteColor: (Int) -> Unit = {}, onChange: (ShapeToolSettings) -> Unit) {
    DrawToolkitPopup(open, onDismiss) { ShapeKit(settings, palette, canvasInk, onPaletteColor, onChange) }
}

@Composable
internal fun ShapeKit(settings: ShapeToolSettings, palette: List<Int>, canvasInk: Color,
                      onPaletteColor: (Int) -> Unit, onChange: (ShapeToolSettings) -> Unit) {
    var page by remember { mutableIntStateOf(settings.kind.page) }
    val color = if (settings.colorFollowsTheme) canvasInk else Color(settings.borderArgb)
    ReferenceToolKit(Strings.shape, "draw-toolkit-shape") {
        ShapePreview(settings, color)
        Spacer(Modifier.height(10.dp))
        val currentPage by rememberUpdatedState(page)
        Column(Modifier.fillMaxWidth().testTag(ShapeMenuTags.Grid).pointerInput(Unit) {
            var distance = 0f
            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                if (abs(distance) >= 28.dp.toPx()) page = (currentPage - distance.sign.toInt()).coerceIn(0, ShapeKind.PAGE_COUNT - 1)
            }) { change, amount -> distance += amount; change.consume() }
        }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShapeKind.onPage(page).chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { kind -> ShapeChoice(kind, settings.kind == kind) { onChange(settings.copy(kind = kind)) } }
                    repeat(6 - row.size) { Spacer(Modifier.size(44.dp)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(ShapeKind.PAGE_COUNT) { index ->
                val active = page == index
                HoverTooltip(Strings.shape_page_value(index + 1)) {
                    Box(Modifier.padding(horizontal = 5.dp).size(if (active) 9.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        .clickable { page = index }.testTag("draw-shape-page-$index")
                        .semantics { contentDescription = Strings.shape_page_value(index + 1); selected = active })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToolkitLineTypes(settings.lineType, ShapeMenuTags::lineType) { onChange(settings.copy(lineType = it)) }
        Spacer(Modifier.height(4.dp))
        ToolkitSlider(Strings.border_width, settings.borderWidth.toFloat(), ShapeToolSettings.MIN_BORDER_WIDTH.toFloat()..
            ShapeToolSettings.MAX_BORDER_WIDTH.toFloat(), 1f, ShapeMenuTags.Width, color) {
            onChange(settings.copy(borderWidth = it.toInt()))
        }
        Spacer(Modifier.height(2.dp))
        Text(Strings.border_color, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp))
        ToolkitPalette(palette, color.toArgb(), ShapeMenuTags::border, ShapeMenuTags.BorderWheel, "draw-shape-custom-border",
            onPick = { onChange(settings.copy(borderArgb = it, colorFollowsTheme = it == canvasInk.toArgb())) },
            onPromote = onPaletteColor, onAutomatic = { onChange(settings.copy(colorFollowsTheme = true)) })
        Spacer(Modifier.height(6.dp))
        ToolkitFill(settings.fillArgb, color.toArgb(), ShapeMenuTags.Fill, ShapeMenuTags.fill(null), "draw-shape-custom-fill",
            onChange = { onChange(settings.copy(fillArgb = it)) }, onPromote = onPaletteColor)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ShapePreview(settings: ShapeToolSettings, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(48.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag(ShapeMenuTags.Preview)) {
        val scale = minOf(size.width * .62f / 220.dp.toPx(), size.height * .70f / 150.dp.toPx())
        val width = 220.dp.toPx() * scale
        val height = 150.dp.toPx() * scale
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        drawToolkitTracing(trace(settings.kind, left, top, left + width, top + height), color,
            settings.borderWidth.dp.toPx() * scale, settings.lineType, settings.fillArgb?.let(::Color))
    }
}

@Composable
private fun ShapeChoice(kind: ShapeKind, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    HoverTooltip(kind.label) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected, onClick = onClick).testTag(ShapeMenuTags.kind(kind))
            .semantics { contentDescription = kind.label }) {
            Canvas(Modifier.size(44.dp)) {
                val inset = 9.dp.toPx()
                drawToolkitTracing(trace(kind, inset, inset, size.width - inset, size.height - inset),
                    color, 1.6.dp.toPx(), LineType.Solid)
            }
        }
    }
}

internal fun DrawScope.drawToolkitTracing(tracing: ShapeTracing, color: Color, width: Float, lineType: LineType, fill: Color? = null) {
    if (fill != null) tracing.fill.forEach { points ->
        if (points.size >= 6) drawPath(Path().apply {
            moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
            close()
        }, fill)
    }
    fun drawEdges(edges: List<FloatArray>, type: LineType) {
        edges.forEach { points ->
            if (points.size >= 4) drawPath(Path().apply {
                moveTo(points[0], points[1])
                for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
            }, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = type.toolkitPathEffect(width)))
        }
    }
    drawEdges(tracing.solid, lineType)
    drawEdges(tracing.hidden, LineType.Dotted)
}
