package com.vivenotes.ui.ribbon.draw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.math.*
import com.vivenotes.ui.components.ToolPane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MathPane(initial: String, math: DrawMath, onClose: () -> Unit) {
    var source by remember(initial) { mutableStateOf(initial) }
    var analysis by remember { mutableStateOf<MathAnalysis?>(null) }
    var result by remember { mutableStateOf<MathOperationResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var decimal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var requestVersion by remember { mutableLongStateOf(0L) }
    LaunchedEffect(source, math) {
        val version = ++requestVersion
        val requested = source
        analysis = null; result = null; error = null
        busy = requested.isNotBlank()
        if (requested.isBlank()) return@LaunchedEffect
        try {
            delay(200)
            val current = math.engine.analyze(requested)
            if (version != requestVersion) return@LaunchedEffect
            analysis = current
            error = current.error
            val action = AUTOMATIC_MATH_ACTIONS.firstOrNull { id -> current.actions.any { it.id == id } }
            if (action != null && current.error == null) {
                val calculated = math.engine.execute(requested, action)
                if (version == requestVersion) result = calculated
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (version == requestVersion) error = failure.message ?: "Unable to calculate" }
        finally { if (version == requestVersion) busy = false }
    }
    ToolPane("Math", onClose) {
        OutlinedTextField(source, { requestVersion++; source = it; analysis = null; result = null; error = null; busy = it.isNotBlank() }, label = { Text("LaTeX source") }, minLines = 2, maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("draw-math-source"))
        if (source.isNotBlank()) EquationContent(source, MaterialTheme.colorScheme.onSurface)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        analysis?.let { current ->
            Text(current.summary, style = MaterialTheme.typography.bodySmall)
            current.actions.forEach { action ->
                TextButton(onClick = {
                    val version = ++requestVersion
                    val requested = source
                    busy = true
                    scope.launch {
                        try {
                            val calculated = math.engine.execute(requested, action.id)
                            if (version == requestVersion) { result = calculated; error = null }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { if (version == requestVersion) error = failure.message }
                        finally { if (version == requestVersion) busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.testTag("draw-math-action-${action.id}")) { Text(action.label) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { answer ->
            Text(answer.title, style = MaterialTheme.typography.titleSmall)
            answer.latex?.let { latex ->
                if (answer.decimal != null) TextButton({ decimal = !decimal }) { Text(if (decimal) "≈ ✓" else "≈") }
                EquationContent(if (decimal) answer.decimal ?: latex else latex, MaterialTheme.colorScheme.onSurface)
            }
            answer.message?.let { Text(it) }
            answer.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            answer.graph?.let { graph -> MathGraphPlot(graph) }
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
