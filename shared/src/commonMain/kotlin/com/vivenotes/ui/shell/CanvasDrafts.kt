package com.vivenotes.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.Outline
import com.vivenotes.model.PageSpace
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.ui.canvas.documentExtent
import com.vivenotes.ui.canvas.drawDocumentShape
import com.vivenotes.workspace.WorkspaceState

@Composable
internal fun CanvasDrafts(
    state: WorkspaceState,
    spaceDraft: SpaceCut?,
    shapeDraft: Pair<Offset, Offset>?,
    canvasSize: DpSize,
    visibleWindow: () -> Rect,
    shapeInk: Color,
) {
    spaceDraft?.let { cut ->
        Canvas(Modifier.documentExtent(canvasSize)) {
            val window = visibleWindow()
            val at = cut.at.dp.toPx()
            val end = (cut.at + cut.amount).dp.toPx()
            val horizontal = cut.axis == PageSpace.Axis.Horizontal
            val start = if (horizontal) Offset(at, window.top) else Offset(window.left, at)
            val finish = if (horizontal) Offset(at, window.bottom) else Offset(window.right, at)
            drawLine(Color(0xff3584e4), start, finish, 2.dp.toPx())
            if (horizontal) drawRect(Color(0xff3584e4).copy(alpha = .14f), Offset(minOf(at, end), window.top),
                androidx.compose.ui.geometry.Size(kotlin.math.abs(end - at), window.height))
            else drawRect(Color(0xff3584e4).copy(alpha = .14f), Offset(window.left, minOf(at, end)),
                androidx.compose.ui.geometry.Size(window.width, kotlin.math.abs(end - at)))
        }
    }
    shapeDraft?.let { (start, end) ->
        val settings = state.shapeSettings
        val preview = Outline.Shape(id = "draft", kind = settings.kind,
            segments = seedSegments(settings.kind, start.x, start.y, end.x, end.y) { "draft" },
            borderArgb = settings.borderArgb, borderFollowsTheme = settings.colorFollowsTheme,
            borderWidth = settings.borderWidth.toFloat(),
            lineType = settings.lineType, fillArgb = settings.fillArgb).withRecomputedBounds()
        Canvas(Modifier.offset(preview.x.dp, preview.y.dp)
            .size(preview.width.coerceAtLeast(1f).dp, preview.height.coerceAtLeast(1f).dp)) {
            drawDocumentShape(preview, canvasInk = shapeInk)
        }
    }
}
