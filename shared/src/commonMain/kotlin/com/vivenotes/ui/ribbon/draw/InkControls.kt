package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
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
    val color = if (pen.colorFollowsTheme) canvasInk else Color(pen.colorArgb)
    fun update(value: InkPenSettings) = onChange(settings.withPen(index, value))
    ReferenceToolKit(Strings.pen_value(index + 1), "draw-toolkit-pen-${index + 1}") {
        StrokePreview(pen, canvasInk)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(InkBrush.Marker to DrawToolkitSymbols.Edit, InkBrush.Calligraphy to DrawToolkitSymbols.Brush).forEach { (kind, icon) ->
                val selected = pen.brush == kind
                val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(role = Role.RadioButton) { update(pen.copy(brush = kind)) }
                    .testTag(InkControlsTags.brush(kind)).semantics { this.selected = selected },
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(kind.label, style = MaterialTheme.typography.labelMedium, color = content)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToolkitLineTypes(pen.lineType, InkControlsTags::line) { update(pen.copy(lineType = it)) }
        Spacer(Modifier.height(4.dp))
        ToolkitToggle(Strings.hold_for_straight_line, pen.holdForStraightLine, InkControlsTags.Hold,
            Strings.draw_a_line_and_pause_with_the_pen_still_down_for) {
            update(pen.copy(holdForStraightLine = it))
        }
        ToolkitSlider(Strings.thickness, pen.sizeDp, 1f..8f, .5f, InkControlsTags.Width, color) { update(pen.copy(sizeDp = it)) }
        Spacer(Modifier.height(2.dp))
        if (pen.brush == InkBrush.Calligraphy) ToolkitNumberPicker(Strings.pressure, pen.pressure, 0..5, InkControlsTags.Pressure,
            Strings.how_much_harder_pressing_thickens_the_line_0_turns_off_pressure) {
            update(pen.copy(pressure = it))
        }
        ToolkitNumberPicker(Strings.stabilization, pen.stabilization, 0..5, InkControlsTags.Stabilization,
            Strings.smooths_out_shake_a_little_goes_a_long_way_high_settings) { update(pen.copy(stabilization = it)) }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Strings.color, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(DrawToolkitSymbols.Add, Strings.add_color, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).alpha(.42f))
        }
        ToolkitPalette(settings.palette, color.toArgb(), InkControlsTags::color, InkControlsTags.WheelButton,
            InkControlsTags.CustomColor,
            onPick = { update(pen.copy(colorArgb = it, colorFollowsTheme = it == canvasInk.toArgb())) },
            onPromote = { onChange(settings.copy(palette = settings.palette.withColorInFront(it))) },
            onAutomatic = { update(pen.copy(colorFollowsTheme = true)) })
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StrokePreview(pen: InkPenSettings, automatic: Color) {
    val color = if (pen.colorFollowsTheme) automatic else Color(pen.colorArgb)
    Canvas(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(38.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).testTag(InkControlsTags.Preview)) {
        val path = Path().apply {
            moveTo(size.width * .14f, size.height * .70f)
            cubicTo(size.width * .34f, size.height * .12f, size.width * .48f, size.height * .18f, size.width * .58f, size.height * .52f)
            cubicTo(size.width * .66f, size.height * .80f, size.width * .74f, size.height * .86f, size.width * .86f, size.height * .62f)
        }
        drawPath(path, color, style = Stroke(width = pen.sizeDp.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = pen.lineType.toolkitPathEffect(pen.sizeDp.dp.toPx())))
    }
}

@Composable
internal fun HighlighterKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    val value = settings.highlighter
    ToolKit(Strings.highlighter) {
        Box(Modifier.fillMaxWidth().height(56.dp).background(Color.White, MaterialTheme.shapes.small)
            .testTag(InkControlsTags.Preview), contentAlignment = Alignment.Center) {
            Text(Strings.highlight_your_notes, color = Color.Black,
                modifier = Modifier.drawBehind {
                    val band = value.sizeDp.dp.toPx()
                    drawRect(Color(value.colorArgb), Offset(0f, (size.height - band) / 2f), Size(size.width, band))
                }.padding(horizontal = 8.dp))
        }
        KitSlider(Strings.thickness, value.sizeDp, 6f..40f, 1f, InkControlsTags.Width) {
            onChange(settings.copy(highlighter = value.copy(sizeDp = it)))
        }
        ColorSwatches(HighlighterColors, value.colorArgb) { onChange(settings.copy(highlighter = value.copy(colorArgb = it))) }
    }
}

@Composable
internal fun EraserKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    ToolKit(Strings.eraser) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EraserMode.entries.forEach { mode ->
                Choice(mode.label, settings.eraser.mode == mode, DrawRibbonTags.eraserMode(mode)) {
                    onChange(settings.copy(eraser = settings.eraser.copy(mode = mode)))
                }
            }
        }
        Text(if (settings.eraser.mode == EraserMode.Normal) Strings.cut_through_ink else Strings.erase_touched_ink_pieces,
            style = MaterialTheme.typography.bodySmall)
        KitSlider(Strings.size, settings.eraser.sizeDp, 4f..64f, 1f, "draw-eraser-size") {
            onChange(settings.copy(eraser = settings.eraser.copy(sizeDp = it)))
        }
    }
}

@Composable
internal fun RulerKit(settings: InkSettings, onChange: (InkSettings) -> Unit) {
    ToolKit(Strings.ruler) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RulerKind.entries.forEach { kind ->
                Choice(if (kind == RulerKind.Straight) Strings.straight else Strings.protractor, settings.ruler.kind == kind, "draw-ruler-${kind.name}") {
                    onChange(settings.copy(ruler = settings.ruler.copy(kind = kind)))
                }
            }
        }
        if (settings.ruler.kind == RulerKind.Protractor)
            KitSlider(Strings.diameter, settings.ruler.diameterDp.toFloat(), 240f..1200f, 40f, "draw-ruler-size") {
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
            Text(if (value == 0) Strings.off else "$value", Modifier.testTag(tag))
            TextButton({ onChange(value + 1) }, enabled = value < 5, modifier = Modifier.testTag("$tag-plus")) { Text("+") }
        }
    }
}

@Composable
internal fun KitSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, tag: String, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text(Strings.value_value_dp(label, draft.toString().removeSuffix(".0")), style = MaterialTheme.typography.bodyMedium)
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
                        contentDescription = Strings.color_value((color and 0xffffff).toString(16).padStart(6, '0'))
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
        OutlinedTextField(hex, { if (it.length <= 7) hex = it }, label = { Text(Strings.color_hex) },
            singleLine = true, isError = hex.isNotEmpty() && parsed == null,
            modifier = Modifier.size(140.dp, 58.dp).testTag(tag))
        TextButton({ parsed?.let(onChange) }, enabled = parsed != null,
            modifier = Modifier.testTag("$tag-apply")) { Text(Strings.apply) }
    }
}
