package com.vivenotes.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.vivenotes.graph.*
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.Outline
import com.vivenotes.pdf.PdfEquation

@Composable
internal actual fun GraphDrawing(
    graph: Outline.Graph, paper: Color, ink: Color, accent: Color,
    drag: GraphDrag?, traceX: Double?, area: GraphInterval?, selected: Boolean, modifier: Modifier,
) {
    val density = LocalDensity.current.density
    val painter = remember { GraphPainter() }
    DisposableEffect(painter) { onDispose { painter.close() } }
    val base = remember(graph.expression) { GraphExpression.parse(graph.expression) }
    val original = remember(graph.originalExpression) { graph.originalExpression?.let(GraphExpression::parse) }
    val window = graph.window()
    val points = remember(graph.expression, window, drag) {
        val f = drag?.let { it::evaluate } ?: base?.let { it::evaluate }
        f?.let { GraphAnalysis.pointsOfInterest(it, window) }.orEmpty()
    }
    var header by remember { mutableStateOf<PdfEquation?>(null) }
    LaunchedEffect(graph.latex, graph.dependent, graph.showEquation, ink, density) {
        header?.close(); header = null
        if (graph.showEquation && graph.latex.isNotBlank())
            header = runCatching { PdfEquation.create("${graph.dependent} = ${graph.latex}", 20f * density, ink.toArgb()) }.getOrNull()
    }
    DisposableEffect(Unit) { onDispose { header?.close() } }
    Canvas(modifier) {
        val f = drag?.let { it::evaluate } ?: base?.let { it::evaluate } ?: return@Canvas
        val under = when {
            !graph.showOriginal -> null
            drag != null -> base?.let { it::evaluate }
            graph.isMutated -> original?.let { it::evaluate }
            else -> null
        }
        drawIntoCanvas {
            painter.draw(it.skiaCanvas,
                GraphFrame(graph, 0f, 0f, size.width, size.height, density, window),
                GraphColors(paper.toArgb(), ink.toArgb(), graph.colorArgb ?: GraphPainter.DEFAULT_CURVE_ARGB, accent.toArgb()),
                f, under, points, header.takeIf { drag == null },
                handles = selected && graph.mutable && drag == null,
                active = drag?.let { it.pointX to it.pointY }, traceX = traceX,
                traceHandle = selected && drag == null, area = area, areaHandles = selected && drag == null)
        }
    }
}
