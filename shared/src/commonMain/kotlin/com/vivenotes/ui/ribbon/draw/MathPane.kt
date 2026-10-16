package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivenotes.math.*
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class MathPaneState(initial: String) {
    var source by mutableStateOf(initial)
    var analysis by mutableStateOf<MathAnalysis?>(null)
    var result by mutableStateOf<MathOperationResult?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var copied by mutableStateOf(false)
    var requestVersion = 0L
}

@Composable
internal fun MathPane(initial: String, math: DrawMath, showInterpretation: Boolean = false,
                      log: DebugLog = remember { DebugLog() }, onClose: () -> Unit) {
    val state = remember(initial, math) { MathPaneState(initial) }
    var decimal by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, state.source, math) {
        val version = ++state.requestVersion
        val requested = state.source
        state.analysis = null; state.result = null; state.error = null
        state.busy = requested.isNotBlank()
        if (requested.isBlank()) return@LaunchedEffect
        try {
            delay(200)
            val current = math.engine.analyze(requested)
            if (version != state.requestVersion) return@LaunchedEffect
            state.analysis = current
            state.error = current.error
            val action = AUTOMATIC_MATH_ACTIONS.firstOrNull { id -> current.actions.any { it.id == id } }
            if (action != null && current.error == null) {
                val calculated = math.engine.execute(requested, action)
                if (version == state.requestVersion) state.result = calculated
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (version == state.requestVersion) state.error = failure.message ?: "Unable to calculate" }
        finally { if (version == state.requestVersion) state.busy = false }
    }
    MathPaneFrame(onClose) {
        MathSection("LaTeX") {
            BasicTextField(state.source, {
                state.requestVersion++
                state.source = it; state.copied = false; state.analysis = null; state.result = null; state.error = null; state.busy = it.isNotBlank()
            }, minLines = 1, maxLines = 6,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp).testTag(MathPaneTags.Source))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MathPanelButton({
                    clipboard.setText(AnnotatedString(state.source))
                    state.copied = true
                    log.event("math") { "LaTeX copied" }
                }, enabled = state.source.isNotBlank(), modifier = Modifier.testTag(MathPaneTags.Copy)) { Text("Copy") }
                if (state.copied) Text("Copied", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(MathPaneTags.Copied))
            }
        }
        MathSection("Preview") { MathEquationPreview(state.source, MathPaneTags.Preview) }
        state.analysis?.let { current ->
            if (showInterpretation) MathSection("Understood as") {
                Text(buildString {
                    append(current.summary)
                    if (current.variables.isNotEmpty()) append(" · Variables: ${current.variables.joinToString()}")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MathEquationPreview(current.normalizedLatex, MathPaneTags.Interpretation, scale = .85f)
            }
            MathSection("Actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    current.actions.forEach { action ->
                        MathPanelButton(onClick = {
                            val version = ++state.requestVersion
                            val requested = state.source
                            state.busy = true
                            log.event("math") { "action started" }
                            scope.launch {
                                try {
                                    val calculated = math.engine.execute(requested, action.id)
                                    if (version == state.requestVersion) {
                                        state.result = calculated; state.error = null
                                        log.event("math") { if (calculated.error == null) "action completed" else "action failed" }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    if (version == state.requestVersion) {
                                        state.error = failure.message ?: "Unable to calculate"
                                        log.event("math") { "action failed" }
                                    }
                                } finally { if (version == state.requestVersion) state.busy = false }
                            }
                        }, enabled = !state.busy && current.error == null, mathAction = true,
                            modifier = Modifier.testTag(MathPaneTags.action(action.id))) { Text(action.label) }
                    }
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        state.error?.let { MathSection("Operation failed") { MathError(it) } }
        state.result?.let { answer ->
            MathSection(answer.title) {
                answer.latex?.takeIf(String::isNotBlank)?.let { latex ->
                    MathEquationPreview(if (decimal) answer.decimal ?: latex else latex, MathPaneTags.Result)
                    if (answer.decimal != null) MathDecimalToggle(decimal, onChange = {
                        decimal = it
                        log.event("math") { "decimal display=$it" }
                    })
                }
                answer.message?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                answer.error?.let { MathError(it) }
                answer.graph?.let { graph -> MathGraphPlot(graph) }
            }
        }
    }
}

@Composable
private fun MathGraphPlot(graph: MathGraph) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("draw-math-graph")) {
        val xs = graph.xValues.filter { it.isFinite() }
        val ys = graph.yValues.filterNotNull().filter { it.isFinite() }
        if (xs.isEmpty() || ys.isEmpty()) return@Canvas
        val xMin = xs.min(); val xMax = xs.max(); val yMin = ys.min(); val yMax = ys.max()
        fun x(value: Double) = ((value - xMin) / (xMax - xMin).coerceAtLeast(.000001) * size.width).toFloat()
        fun y(value: Double) = (size.height - (value - yMin) / (yMax - yMin).coerceAtLeast(.000001) * size.height).toFloat()
        if (xMin <= 0.0 && xMax >= 0.0) drawLine(Color.Gray, Offset(x(0.0), 0f), Offset(x(0.0), size.height))
        if (yMin <= 0.0 && yMax >= 0.0) drawLine(Color.Gray, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
        val path = Path()
        var penDown = false
        graph.xValues.zip(graph.yValues).forEach { (a, b) ->
            if (!a.isFinite() || b == null || !b.isFinite()) penDown = false else {
                if (!penDown) path.moveTo(x(a), y(b)) else path.lineTo(x(a), y(b))
                penDown = true
            }
        }
        drawPath(path, ink, style = Stroke(2.dp.toPx()))
    }
}
