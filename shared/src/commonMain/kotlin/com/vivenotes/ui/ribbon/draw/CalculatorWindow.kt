package com.vivenotes.ui.ribbon.draw

import com.vivenotes.localization.Strings
import com.vivenotes.localization.MathText
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.canvas.InkLayer
import com.vivenotes.ui.components.HoverTooltip
import com.vivenotes.workspace.InkSettings
import com.vivenotes.workspace.InkTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

internal object CalculatorTags {
    const val Window = "calculator-window"
    const val Pad = "calculator-pad"
    const val Hint = "calculator-hint"
    const val Header = "calculator-header"
    const val Undo = "calculator-undo"
    const val Clear = "calculator-clear"
    const val Equals = "calculator-equals"
    const val Decimal = "calculator-decimal"
    const val Close = "calculator-close"
    const val Result = "calculator-result"
    const val Error = "calculator-error"
    const val OpenInPanel = "calculator-open-in-panel"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FloatingCalculator(settings: InkSettings, penIndex: Int, allowFinger: Boolean, math: DrawMath,
                                onOpenInPanel: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(listOf(InkPage("calculator", emptyList()))) }
    var answer by remember { mutableStateOf<CalculatorAnswer?>(null) }
    LaunchedEffect(math) {
        try { math.engine.warmUp() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    LaunchedEffect(math) {
        try { math.holdFormulaModel() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { answer = CalculatorAnswer("", error = error.message ?: Strings.unable_to_load_the_formula_model) }
    }
    var requestVersion by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var decimal by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { requestVersion++; job?.cancel() } }
    DrawToolkitTheme {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val density = LocalDensity.current.density
            val width = minOf(380.dp, (maxWidth - 24.dp).coerceAtLeast(160.dp))
            var heightPx by remember { mutableIntStateOf(0) }
            var position by remember { mutableStateOf<Offset?>(null) }
            val maxX = (constraints.maxWidth - width.value * density).coerceAtLeast(0f)
            val maxY = (constraints.maxHeight - heightPx).toFloat().coerceAtLeast(0f)
            val placed = (position ?: Offset(maxX - 12f * density, 140f * density)).let {
                Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY))
            }
            val currentPlaced by rememberUpdatedState(placed)
            val carried = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
            val shown = remember { Animatable(1f) }
            val currentClose by rememberUpdatedState(onClose)
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            var writing by remember { mutableStateOf(false) }
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.offset { IntOffset(placed.x.roundToInt(), placed.y.roundToInt()) }.width(width)
                    .onSizeChanged { heightPx = it.height }.testTag(CalculatorTags.Window)
                    .pointerInput(density) {
                        detectCalculatorThrow(onCarry = { amount -> scope.launch { carried.snapTo(carried.value + amount) } },
                            onRelease = { velocity -> scope.launch {
                                val speed = hypot(velocity.x, velocity.y)
                                if (speed >= 1000f * density) {
                                    val fling = Offset(velocity.x, velocity.y)
                                    launch { carried.animateTo(carried.value + fling / speed *
                                        hypot(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())) }
                                    shown.animateTo(0f); currentClose()
                                } else carried.animateTo(Offset.Zero)
                            } })
                    }.graphicsLayer { translationX = carried.value.x; translationY = carried.value.y; alpha = shown.value }) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectDragGestures { change, amount -> change.consume(); position = currentPlaced + amount }
                    }.padding(top = 4.dp).testTag(CalculatorTags.Header), verticalAlignment = Alignment.CenterVertically) {
                        Icon(DrawToolkitSymbols.DragIndicator, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(Strings.calculator, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        HoverTooltip(Strings.close_calculator) {
                            IconButton(onClose, Modifier.testTag(CalculatorTags.Close)) {
                                Icon(DrawToolkitSymbols.Close, Strings.close_calculator, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (dark) Color(0xFF1F1F1F) else Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .testTag(CalculatorTags.Pad).semantics { contentDescription = Strings.calculator_writing_pad }
                        .pointerInput(allowFinger) {
                            awaitEachGesture {
                                val down = awaitFirstDown(false, PointerEventPass.Initial)
                                if (down.type == PointerType.Touch && !allowFinger) {
                                    down.consume()
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                } else {
                                    writing = true
                                    try {
                                        do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                                        while (event.changes.any { it.pressed })
                                    } finally { writing = false }
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        if (history.size == 1 && !writing) Text(Strings.write_a_calculation_then_press,
                            color = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(CalculatorTags.Hint))
                        InkLayer(history.last(), DpSize(maxWidth, 200.dp), if (dark) Color.White else Color.Black, tool = InkTool.Pen,
                            settings = settings.copy(pen = settings.pen(penIndex).copy(holdForStraightLine = false)),
                            onEdit = { _, rendered -> history = history + rendered })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoverTooltip(Strings.undo_stroke) {
                            IconButton({ history = history.dropLast(1) }, enabled = history.size > 1 && !busy,
                                modifier = Modifier.testTag(CalculatorTags.Undo)) { Icon(DrawToolkitSymbols.Undo, Strings.undo_stroke) }
                        }
                        TextButton({ requestVersion++; job?.cancel(); history = history.take(1); answer = null; busy = false },
                            enabled = history.size > 1 || answer != null || busy,
                            modifier = Modifier.testTag(CalculatorTags.Clear)) { Text(Strings.clear) }
                        Spacer(Modifier.weight(1f))
                        HoverTooltip(Strings.show_decimal) {
                            ToggleButton(decimal, { decimal = it },
                                shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.ExtraSmallContainerHeight),
                                colors = ToggleButtonDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    checkedContainerColor = MaterialTheme.colorScheme.tertiary,
                                    checkedContentColor = MaterialTheme.colorScheme.onTertiary),
                                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
                                modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight)
                                    .testTag(CalculatorTags.Decimal).semantics { contentDescription = Strings.show_decimal }) {
                                Text("≈", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = {
                            val ink = history.last()
                            val version = ++requestVersion
                            busy = true
                            job = scope.launch {
                                try {
                                    val result = calculate(math.readFormula(ink), math.engine)
                                    if (version == requestVersion) answer = result
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    if (version == requestVersion) answer = CalculatorAnswer("", error = error.message ?: Strings.unable_to_read_this_formula)
                                } finally { if (version == requestVersion) busy = false }
                            }
                        }, enabled = history.size > 1 && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary),
                            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight),
                            modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight)
                                .testTag(CalculatorTags.Equals).semantics { contentDescription = Strings.calculate }) {
                            Text("=", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (busy) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Text(Strings.reading_handwriting, style = MaterialTheme.typography.bodyMedium)
                    }
                    answer?.let { value ->
                        Spacer(Modifier.height(8.dp))
                        if (value.latex.isNotBlank()) EquationContent(value.latex, MaterialTheme.colorScheme.onSurface)
                        value.result?.let { result ->
                            Text(MathText.translate(result.title), style = MaterialTheme.typography.labelLarge)
                            result.latex?.let { latex -> EquationContent(MathText.translateVerdict(if (decimal) result.decimal ?: latex else latex),
                                MaterialTheme.colorScheme.onSurface, Modifier.testTag(CalculatorTags.Result)) }
                            result.message?.let { Text(MathText.translate(it)) }
                        }
                        value.error?.let { Text(MathText.translate(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CalculatorTags.Error)) }
                        if (value.latex.isNotBlank()) TextButton({ onOpenInPanel(value.latex) }, Modifier.testTag(CalculatorTags.OpenInPanel)) {
                            Text(Strings.open_in_panel)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectCalculatorThrow(onCarry: (Offset) -> Unit, onRelease: (Velocity) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(false, PointerEventPass.Initial)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val tracker = VelocityTracker()
        var carrying = false
        var total = Offset.Zero
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.type == PointerType.Stylus || it.type == PointerType.Eraser }) break
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val pan = event.calculatePan()
                total += pan
                if (carrying || total.getDistance() > viewConfiguration.touchSlop) {
                    carrying = true; onCarry(pan)
                    tracker.addPosition(event.changes.first().uptimeMillis, total)
                    event.changes.forEach { it.consume() }
                }
            } else if (carrying) { event.changes.forEach { it.consume() } }
        } while (currentEvent.changes.any { it.pressed })
        if (carrying) onRelease(tracker.calculateVelocity())
    }
}
