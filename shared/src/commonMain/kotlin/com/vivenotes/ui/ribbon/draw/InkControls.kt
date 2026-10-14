package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vivenotes.data.EraserMode
import com.vivenotes.model.ink.LineType
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.workspace.*
import kotlin.math.roundToInt

internal object InkControlsTags {
    const val Brush = "draw-ink-brush"
    const val Width = "draw-ink-width"
    const val CustomWidth = "draw-ink-custom-width"
    const val Color = "draw-ink-color"
    const val Automatic = "draw-ink-color-auto"
    const val CustomColor = "draw-ink-custom-color"
    const val Preview = "draw-ink-preview"
    const val Pressure = "draw-ink-pressure"
    const val Stabilization = "draw-ink-stabilization"
    const val Hold = "draw-ink-hold-straight"
    const val WheelButton = "draw-ink-wheel-button"
    const val Wheel = "draw-ink-wheel"
    const val WheelPreview = "draw-ink-wheel-preview"
    const val Brightness = "draw-ink-brightness"
    const val WheelDone = "draw-ink-wheel-done"
    fun brush(brush: InkBrush) = "draw-ink-brush-${brush.name}"
    fun width(width: Float) = "draw-ink-width-$width"
    fun color(argb: Int) = "draw-ink-color-$argb"
    fun line(type: LineType) = "draw-ink-line-${type.name}"
}

internal fun parseInkColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return hex.toIntOrNull(16)?.or(0xff000000.toInt())
}

@Composable
internal fun ToolKit(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(320.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
internal fun PenKit(index: Int, settings: InkSettings, canvasInk: Color, onChange: (InkSettings) -> Unit) {
    val pen = settings.pen(index)
    var wheel by remember { mutableStateOf(false) }
    var wheelPick by remember { mutableStateOf<Int?>(null) }
    fun update(value: InkPenSettings) = onChange(settings.withPen(index, value))
    ToolKit("Pen ${index + 1}") {
        if (wheel) {
            ColorWheelContent(if (pen.colorFollowsTheme) canvasInk.toArgbCompat() else pen.colorArgb,
                onPick = { wheelPick = it; update(pen.copy(colorArgb = it, colorFollowsTheme = false)) },
                onDone = {
                    wheelPick?.let { onChange(settings.copy(palette = settings.palette.withColorInFront(it))) }
                    wheel = false; wheelPick = null
                })
        } else {
            StrokePreview(pen, canvasInk)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(InkBrush.Marker, InkBrush.Calligraphy).forEach { kind ->
                    Choice(kind.label, pen.brush == kind, InkControlsTags.brush(kind)) { update(pen.copy(brush = kind)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineType.entries.forEach { type ->
                    Choice(type.name, pen.lineType == type, InkControlsTags.line(type)) { update(pen.copy(lineType = type)) }
                }
            }
            KitToggle("Hold for straight line", pen.holdForStraightLine, InkControlsTags.Hold) {
                update(pen.copy(holdForStraightLine = it))
            }
            KitSlider("Thickness", pen.sizeDp, 1f..8f, 0.5f, InkControlsTags.Width) { update(pen.copy(sizeDp = it)) }
            if (pen.brush == InkBrush.Calligraphy) KitStepper("Pressure", pen.pressure, InkControlsTags.Pressure) {
                update(pen.copy(pressure = it))
            }
            KitStepper("Stabilization", pen.stabilization, InkControlsTags.Stabilization) { update(pen.copy(stabilization = it)) }
            Text("Color", style = MaterialTheme.typography.labelLarge)
            ColorSwatches(settings.palette, if (pen.colorFollowsTheme) canvasInk.toArgbCompat() else pen.colorArgb) { argb ->
                update(pen.copy(colorArgb = argb, colorFollowsTheme = argb == canvasInk.toArgbCompat()))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { update(pen.copy(colorFollowsTheme = true)) },
                    modifier = Modifier.testTag(InkControlsTags.Automatic).semantics { selected = pen.colorFollowsTheme }) { Text("Automatic") }
                HoverTooltip("Custom color") {
                    Box(Modifier.size(32.dp).background(Brush.sweepGradient(HUE_STOPS), MaterialTheme.shapes.small)
                        .clickable(role = Role.Button) { wheelPick = null; wheel = true }
                        .testTag(InkControlsTags.WheelButton).semantics { contentDescription = "Custom color wheel" })
                }
            }
            CustomInkColorField(InkControlsTags.CustomColor) { argb ->
                onChange(settings.withPen(index, pen.copy(colorArgb = argb, colorFollowsTheme = false))
                    .copy(palette = settings.palette.withColorInFront(argb)))
            }
        }
    }
}

@Composable
private fun StrokePreview(pen: InkPenSettings, automatic: Color) {
    val color = if (pen.colorFollowsTheme) automatic else Color(pen.colorArgb)
    val paper = if (automatic == Color.White) Color(0xff242424) else Color.White
    Canvas(Modifier.fillMaxWidth().height(64.dp).background(paper, MaterialTheme.shapes.small).testTag(InkControlsTags.Preview)) {
        val path = Path().apply { moveTo(18.dp.toPx(), size.height * .7f)
            cubicTo(size.width * .3f, -size.height * .1f, size.width * .55f, size.height * 1.1f, size.width - 18.dp.toPx(), size.height * .3f) }
        drawPath(path, color, style = Stroke(width = pen.sizeDp.dp.toPx(), pathEffect = when (pen.lineType) {
            LineType.Solid -> null
            else -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
        }))
    }
}

@Composable
internal fun HighlighterKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    val value = settings.highlighter
    ToolKit("Highlighter") {
        Box(Modifier.fillMaxWidth().height(56.dp).background(Color.White, MaterialTheme.shapes.small)
            .testTag(InkControlsTags.Preview), contentAlignment = Alignment.Center) {
            Text("Highlight your notes", color = Color.Black,
                modifier = Modifier.drawBehind {
                    val band = value.sizeDp.dp.toPx()
                    drawRect(Color(value.colorArgb), Offset(0f, (size.height - band) / 2f), Size(size.width, band))
                }.padding(horizontal = 8.dp))
        }
        KitSlider("Thickness", value.sizeDp, 6f..40f, 1f, InkControlsTags.Width) {
            onChange(settings.copy(highlighter = value.copy(sizeDp = it)))
        }
        ColorSwatches(HighlighterColors, value.colorArgb) { onChange(settings.copy(highlighter = value.copy(colorArgb = it))) }
    }
}

@Composable
internal fun EraserKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    ToolKit("Eraser") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EraserMode.entries.forEach { mode ->
                Choice(mode.label, settings.eraser.mode == mode, DrawRibbonTags.eraserMode(mode)) {
                    onChange(settings.copy(eraser = settings.eraser.copy(mode = mode)))
                }
            }
        }
        Text(if (settings.eraser.mode == EraserMode.Normal) "Cut through ink" else "Erase touched ink pieces",
            style = MaterialTheme.typography.bodySmall)
        KitSlider("Size", settings.eraser.sizeDp, 4f..64f, 1f, "draw-eraser-size") {
            onChange(settings.copy(eraser = settings.eraser.copy(sizeDp = it)))
        }
    }
}

@Composable
internal fun RulerKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    ToolKit("Ruler") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RulerKind.entries.forEach { kind ->
                Choice(kind.name, settings.ruler.kind == kind, "draw-ruler-${kind.name}") {
                    onChange(settings.copy(ruler = settings.ruler.copy(kind = kind)))
                }
            }
        }
        if (settings.ruler.kind == RulerKind.Protractor)
            KitSlider("Diameter", settings.ruler.diameterDp.toFloat(), 240f..1200f, 40f, "draw-ruler-size") {
                onChange(settings.copy(ruler = settings.ruler.copy(diameterDp = it.roundToInt())))
            }
    }
}

@Composable
internal fun Choice(label: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.shapes.small).border(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.shapes.small).clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp).testTag(tag).semantics { selected = chosen })
}

@Composable
internal fun KitToggle(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange, Modifier.testTag(tag))
    }
}

@Composable
internal fun KitStepper(label: String, value: Int, tag: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onChange(value - 1) }, enabled = value > 0, modifier = Modifier.testTag("$tag-minus")) { Text("−") }
            Text(if (value == 0) "Off" else "$value", Modifier.testTag(tag))
            TextButton({ onChange(value + 1) }, enabled = value < 5, modifier = Modifier.testTag("$tag-plus")) { Text("+") }
        }
    }
}

@Composable
internal fun KitSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, tag: String, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text("$label · ${draft.toString().removeSuffix(".0")} dp", style = MaterialTheme.typography.bodyMedium)
    Slider(draft, { draft = (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(range) },
        valueRange = range, steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
        onValueChangeFinished = { if (draft != value) onChange(draft) },
        modifier = Modifier.testTag(tag).semantics { contentDescription = label })
}

@Composable
internal fun ColorSwatches(colors: List<Int>, selectedArgb: Int, onPick: (Int) -> Unit) {
    colors.chunked(6).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { color ->
                Box(Modifier.size(32.dp).background(Color.White, MaterialTheme.shapes.small)
                    .background(Color(color), MaterialTheme.shapes.small)
                    .border(if (color == selectedArgb) 2.dp else 1.dp,
                        if (color == selectedArgb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        MaterialTheme.shapes.small).clickable(role = Role.Button) { onPick(color) }
                    .testTag(InkControlsTags.color(color)).semantics {
                        contentDescription = "Color #${(color and 0xffffff).toString(16).padStart(6, '0')}"
                        selected = color == selectedArgb
                    })
            }
        }
    }
}

private fun Color.toArgbCompat(): Int = this.toArgb()

@Composable
private fun CustomInkColorField(tag: String, onChange: (Int) -> Unit) {
    var hex by remember { mutableStateOf("") }
    val parsed = parseInkColor(hex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(hex, { if (it.length <= 7) hex = it }, label = { Text("Color hex") },
            singleLine = true, isError = hex.isNotEmpty() && parsed == null,
            modifier = Modifier.size(140.dp, 58.dp).testTag(tag))
        TextButton({ parsed?.let(onChange) }, enabled = parsed != null,
            modifier = Modifier.testTag("$tag-apply")) { Text("Apply") }
    }
}
