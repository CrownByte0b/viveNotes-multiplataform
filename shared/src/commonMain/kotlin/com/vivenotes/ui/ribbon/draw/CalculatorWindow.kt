package com.vivenotes.ui.ribbon.draw

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.canvas.InkLayer
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
    const val Undo = "calculator-undo"
    const val Clear = "calculator-clear"
    const val Equals = "calculator-equals"
    const val Decimal = "calculator-decimal"
    const val Close = "calculator-close"
    const val Result = "calculator-result"
    const val Error = "calculator-error"
    const val OpenInPanel = "calculator-open-in-panel"
}

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
        catch (error: Exception) { answer = CalculatorAnswer("", error = error.message ?: "Unable to load the formula model") }
    }
    var requestVersion by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var decimal by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { requestVersion++; job?.cancel() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val width = minOf(480.dp, (maxWidth - 24.dp).coerceAtLeast(160.dp))
        var heightPx by remember { mutableIntStateOf(0) }
        var position by remember { mutableStateOf<Offset?>(null) }
        val maxX = (constraints.maxWidth - width.value * density).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - heightPx).toFloat().coerceAtLeast(0f)
        val placed = (position ?: Offset(maxX - 12f * density, 120f * density)).let {
            Offset(it.x.coerceIn(0f, maxX), it.y.coerceIn(0f, maxY))
        }
        val currentPlaced by rememberUpdatedState(placed)
        val carried = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val shown = remember { Animatable(1f) }
        val currentClose by rememberUpdatedState(onClose)
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 6.dp,
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
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectDragGestures { change, amount -> change.consume(); position = currentPlaced + amount }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Calculator", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClose, Modifier.testTag(CalculatorTags.Close)) { Text("Close") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(6.dp))
                    .clipToBounds().testTag(CalculatorTags.Pad).pointerInput(allowFinger) {
                        awaitEachGesture {
                            val down = awaitFirstDown(false, PointerEventPass.Initial)
                            if (down.type == PointerType.Touch && !allowFinger) {
                                down.consume()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }) {
                    InkLayer(history.last(), DpSize(maxWidth, 180.dp), Color.Black, tool = InkTool.Pen,
                        settings = settings.copy(pen = settings.pen(penIndex).copy(holdForStraightLine = false)),
                        onEdit = { _, rendered -> history = history + rendered })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ history = history.dropLast(1) }, enabled = history.size > 1 && !busy,
                        modifier = Modifier.testTag(CalculatorTags.Undo)) { Text("Undo") }
                    TextButton({ requestVersion++; job?.cancel(); history = history.take(1); answer = null; busy = false },
                        modifier = Modifier.testTag(CalculatorTags.Clear)) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ decimal = !decimal }, Modifier.testTag(CalculatorTags.Decimal)) { Text(if (decimal) "≈ ✓" else "≈") }
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
                                if (version == requestVersion) answer = CalculatorAnswer("", error = error.message ?: "Unable to read this formula")
                            } finally { if (version == requestVersion) busy = false }
                        }
                    }, enabled = history.size > 1 && !busy, modifier = Modifier.testTag(CalculatorTags.Equals)) { Text("=") }
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading handwriting…") }
                answer?.let { value ->
                    if (value.latex.isNotBlank()) EquationContent(value.latex, MaterialTheme.colorScheme.onSurface)
                    value.result?.let { result ->
                        Text(result.title, style = MaterialTheme.typography.labelLarge)
                        result.latex?.let { latex -> EquationContent(if (decimal) result.decimal ?: latex else latex,
                            MaterialTheme.colorScheme.onSurface, Modifier.testTag(CalculatorTags.Result)) }
                        result.message?.let { Text(it) }
                    }
                    value.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CalculatorTags.Error)) }
                    if (value.latex.isNotBlank()) TextButton({ onOpenInPanel(value.latex) }, Modifier.testTag(CalculatorTags.OpenInPanel)) {
                        Text("Open in panel")
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
