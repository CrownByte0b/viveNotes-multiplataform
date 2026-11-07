package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vivenotes.graph.GraphDrag
import com.vivenotes.model.GraphInterval
import com.vivenotes.model.Outline

@Composable
internal actual fun GraphDrawing(
    graph: Outline.Graph, paper: Color, ink: Color, accent: Color,
    drag: GraphDrag?, traceX: Double?, area: GraphInterval?, selected: Boolean, modifier: Modifier,
) { /* Graph painting is a desktop service, like ink authoring. */ }
