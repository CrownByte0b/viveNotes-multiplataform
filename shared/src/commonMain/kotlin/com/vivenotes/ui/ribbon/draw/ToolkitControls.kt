package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.model.ink.LineType
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.ui.components.ScaledDropdownMenu
import kotlin.math.roundToInt

/** Android's panel palette and typography, scoped to the requested drawing controls. */
@Composable
internal fun DrawToolkitTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF007FFF), onPrimary = Color.White,
        primaryContainer = Color(0xFF00478E), onPrimaryContainer = Color(0xFFB3D9FF),
        secondary = Color(0xFF0063C6), tertiary = Color(0xFFFF8000), onTertiary = Color(0xFF1F1F1F),
        tertiaryContainer = Color(0xFF7A3D00), onTertiaryContainer = Color(0xFFFFD9B3),
        background = Color(0xFF1F1F1F), onBackground = Color(0xFFE6E6E6),
        surface = Color(0xFF2B2B2B), onSurface = Color(0xFFE6E6E6),
        surfaceVariant = Color(0xFF333333), onSurfaceVariant = Color(0xFFB8B8B8),
        surfaceContainer = Color(0xFF262626), surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF383838), outline = Color(0xFF4A4A4A), outlineVariant = Color(0xFF3A3A3A),
    ) else lightColorScheme(
        primary = Color(0xFF0063C6), onPrimary = Color.White,
        primaryContainer = Color(0xFFB3D9FF), onPrimaryContainer = Color(0xFF001C39),
        secondary = Color(0xFF0055AA), tertiary = Color(0xFFFF8000), onTertiary = Color(0xFF1F1F1F),
        tertiaryContainer = Color(0xFFFFDCC2), onTertiaryContainer = Color(0xFF3A1A00),
        background = Color(0xFFFAFAFA), onBackground = Color(0xFF1B1B1B),
        surface = Color.White, onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF4A4A4A),
        surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFEDEDED),
        surfaceContainerHighest = Color(0xFFE7E7E7), outline = Color(0xFFC6C6C6), outlineVariant = Color(0xFFDDDDDD),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(), typography = Typography(
        titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyMedium = TextStyle(fontSize = 14.sp), bodySmall = TextStyle(fontSize = 12.5.sp),
        labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
    ), content = content)
}

@Composable
internal fun DrawToolkitPopup(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DrawToolkitTheme { ScaledDropdownMenu(open, onDismiss, content = content) }
}

@Composable
internal fun ReferenceToolKit(title: String, content: @Composable ColumnScope.() -> Unit) {
    DrawToolkitTheme {
        Column(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("draw-toolkit-${title.lowercase().replace(' ', '-')}").padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
internal fun ToolkitSetting(label: String, info: String? = null, trailing: @Composable () -> Unit) {
    var explaining by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (info != null) {
                    Spacer(Modifier.width(6.dp))
                    HoverTooltip("About $label") {
                        Icon(DrawToolkitSymbols.Info, "About $label", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp).clickable { explaining = !explaining })
                    }
                }
            }
            trailing()
        }
        if (explaining && info != null) Text(info, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
internal fun ToolkitToggle(label: String, value: Boolean, tag: String, info: String? = null, onChange: (Boolean) -> Unit) {
    ToolkitSetting(label, info) {
        Box(Modifier.size(31.2.dp, 19.2.dp), contentAlignment = Alignment.Center) {
            Switch(value, onChange, Modifier.requiredSize(52.dp, 32.dp).scale(.6f).testTag(tag)
                .semantics { contentDescription = label })
        }
    }
}

@Composable
internal fun ToolkitNumberPicker(label: String, value: Int, range: IntRange, tag: String, info: String? = null,
                                 onPick: (Int) -> Unit) {
    ToolkitSetting(label, info) {
        var open by remember { mutableStateOf(false) }
        Box {
            Box(Modifier.size(72.dp, 32.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(role = Role.DropdownList) { open = true }.testTag(tag)
                .semantics { contentDescription = label; stateDescription = value.toString() }, contentAlignment = Alignment.Center) {
                Text(value.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            ScaledDropdownMenu(open, { open = false }) {
                range.forEach { option ->
                    DropdownMenuItem(text = { Text(if (option == 0) "Off" else option.toString()) },
                        modifier = Modifier.testTag("$tag-option-$option"),
                        onClick = { open = false; onPick(option) })
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.ToolkitSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float,
                                      tag: String, previewColor: Color, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    var held by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(draft.toString().removeSuffix(".0"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("$tag-value"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolkitStepButton(DrawToolkitSymbols.Remove, "Decrease $label", value > range.start, "$tag-minus") {
            onChange((value - step).coerceAtLeast(range.start))
        }
        Box(Modifier.weight(1f).pointerInput(Unit) {
            try {
                awaitPointerEventScope {
                    while (true) held = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            } finally { held = false }
        }) {
            Slider(draft, { draft = (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(range) },
                valueRange = range, steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0),
                onValueChangeFinished = { if (draft != value) onChange(draft) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag(tag)
                    .semantics { contentDescription = label })
            if (held) {
                val surface = MaterialTheme.colorScheme.surfaceContainerHighest
                val outline = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.matchParentSize().testTag("$tag-size-preview")) {
                    val radius = (draft + 8f).coerceAtLeast(28f).dp.toPx() / 2f
                    val fraction = (draft - range.start) / (range.endInclusive - range.start)
                    val center = Offset((6.dp.toPx() + (size.width - 12.dp.toPx()) * fraction)
                        .coerceIn(radius, (size.width - radius).coerceAtLeast(radius)), size.height / 2f)
                    drawCircle(surface, radius, center)
                    drawCircle(outline, radius, center, style = Stroke(1.dp.toPx()))
                    drawCircle(previewColor, draft.dp.toPx() / 2f, center)
                }
            }
        }
        ToolkitStepButton(DrawToolkitSymbols.Add, "Increase $label", value < range.endInclusive, "$tag-plus") {
            onChange((value + step).coerceAtMost(range.endInclusive))
        }
    }
}

@Composable
private fun ToolkitStepButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    HoverTooltip(label) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else .42f).testTag(tag), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

internal fun LineType.toolkitPathEffect(width: Float): PathEffect? = when (this) {
    LineType.Solid -> null
    LineType.Dashed -> PathEffect.dashPathEffect(floatArrayOf(width * 2.6f, width * 1.8f))
    LineType.Dotted -> PathEffect.dashPathEffect(floatArrayOf(.01f, width * 2f))
}

@Composable
internal fun ToolkitLineTypes(current: LineType, tag: (LineType) -> String, onPick: (LineType) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        LineType.entries.forEach { type ->
            val selected = type == current
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            HoverTooltip("${type.name} line") {
                Box(Modifier.size(58.dp, 34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(selected, onClick = { onPick(type) }).testTag(tag(type))
                    .semantics { contentDescription = "${type.name} line" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(38.dp, 12.dp)) {
                        val width = 2.dp.toPx()
                        drawLine(color, Offset(1.dp.toPx(), center.y), Offset(size.width - 1.dp.toPx(), center.y),
                            width, StrokeCap.Round, type.toolkitPathEffect(width))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToolkitPalette(palette: List<Int>, current: Int, colorTag: (Int) -> String, customTag: String,
                            hexTag: String, onPick: (Int) -> Unit, onPromote: (Int) -> Unit,
                            onAutomatic: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var mixed by remember { mutableStateOf<Int?>(null) }
    val latestPromote by rememberUpdatedState(onPromote)
    fun close() { open = false; mixed?.let(latestPromote); mixed = null }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        palette.forEach { argb ->
            ToolkitSwatch(argb == current, colorTag(argb), "Color #${(argb and 0xffffff).toString(16).padStart(6, '0')}",
                { onPick(argb) }) { drawRect(Color(argb)) }
        }
        Box {
            ToolkitSwatch(current !in palette, customTag, "Custom color", { mixed = null; open = true }) {
                drawRect(Brush.sweepGradient(HUE_STOPS, center))
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, size.minDimension / 2f))
            }
            DrawToolkitPopup(open, ::close) {
                ReferenceToolKit("Custom color") {
                    ColorWheelContent(current, onPick = { mixed = it; onPick(it) }, onDone = ::close)
                    if (onAutomatic != null) TextButton(onAutomatic, Modifier.testTag(InkControlsTags.Automatic)) { Text("Automatic") }
                    ToolkitHexField("Color", hexTag) { mixed = it; onPick(it) }
                }
            }
        }
    }
}

@Composable
private fun ToolkitSwatch(selected: Boolean, tag: String, label: String, onClick: () -> Unit, fill: DrawScope.() -> Unit) {
    HoverTooltip(label) {
        Box(Modifier.size(26.dp).selectable(selected, onClick = onClick).testTag(tag)
            .semantics { contentDescription = label }
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(50))
            .padding(if (selected) 4.dp else 2.dp).clip(RoundedCornerShape(50)).drawBehind(fill)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)))
    }
}

@Composable
internal fun ToolkitFill(fill: Int?, seed: Int, tag: String, noFillTag: String, hexTag: String,
                         onChange: (Int?) -> Unit, onPromote: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var mixed by remember { mutableStateOf<Int?>(null) }
    val latestPromote by rememberUpdatedState(onPromote)
    fun close() { open = false; mixed?.let(latestPromote); mixed = null }
    ToolkitSetting("Fill color") {
        Box {
            HoverTooltip(if (fill == null) "No fill" else "Fill color") {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(fill?.let(::Color) ?: Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .clickable { mixed = null; open = true }.testTag(tag)
                    .semantics { contentDescription = if (fill == null) "No fill" else "Fill color" }, contentAlignment = Alignment.Center) {
                    if (fill == null) Icon(DrawToolkitSymbols.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
            DrawToolkitPopup(open, ::close) {
                ReferenceToolKit("Fill color") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (fill == null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(fill == null, onClick = { open = false; mixed = null; onChange(null) })
                        .padding(horizontal = 10.dp, vertical = 8.dp).testTag(noFillTag), verticalAlignment = Alignment.CenterVertically) {
                        Icon(DrawToolkitSymbols.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp)); Text("No fill", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    ColorWheelContent(fill ?: seed, onPick = { mixed = it; onChange(it) }, onDone = ::close)
                    ToolkitHexField("Fill", hexTag, allowAlpha = true) { mixed = it; onChange(it) }
                }
            }
        }
    }
}

@Composable
private fun ToolkitHexField(label: String, tag: String, allowAlpha: Boolean = false, onPick: (Int) -> Unit) {
    var hex by remember { mutableStateOf("") }
    val parsed = if (allowAlpha) parseShapeColor(hex) else parseInkColor(hex)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(hex, { if (it.length <= 9) hex = it }, label = { Text("$label hex") },
            singleLine = true, isError = hex.isNotEmpty() && parsed == null,
            modifier = Modifier.size(140.dp, 58.dp).testTag(tag))
        TextButton({ parsed?.let(onPick) }, enabled = parsed != null, modifier = Modifier.testTag("$tag-apply")) { Text("Apply") }
    }
}
