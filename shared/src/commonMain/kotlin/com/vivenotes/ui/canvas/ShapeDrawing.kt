package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vivenotes.data.automaticColorOr
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.contours
import com.vivenotes.model.ink.fillRegion

/** Draw stored Android shape segments in page units, including hidden edges and fills. */
fun DrawScope.drawDocumentShape(shape: Outline.Shape, originX: Float = shape.x,
                                originY: Float = shape.y,
                                canvasInk: Color = Color.Black) {
    shape.fillArgb?.let { argb ->
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            shape.fillRegion().forEach { region ->
                if (region.size >= 6) {
                    moveTo((region[0] - originX) * density, (region[1] - originY) * density)
                    for (index in 2 until region.size step 2)
                        lineTo((region[index] - originX) * density, (region[index + 1] - originY) * density)
                    close()
                }
            }
        }
        drawPath(path, Color(argb))
    }
    val width = shape.borderWidth * density
    shape.segments.contours().forEach { contour ->
        val points = contour.polyline()
        if (points.size < 4) return@forEach
        val path = Path().apply {
            moveTo((points[0] - originX) * density, (points[1] - originY) * density)
            for (index in 2 until points.size step 2)
                lineTo((points[index] - originX) * density, (points[index + 1] - originY) * density)
            if (contour.isClosed) close()
        }
        val type = if (contour.hidden) LineType.Dotted else shape.lineType
        val effect = when (type) {
            LineType.Solid -> null
            LineType.Dashed -> PathEffect.dashPathEffect(floatArrayOf(width * 2.6f, width * 1.8f))
            LineType.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.01f, width * 2f))
        }
        val border = Color(automaticColorOr(shape.borderArgb, shape.borderFollowsTheme, canvasInk.toArgb()))
        drawPath(path, border, style = Stroke(width = width,
            cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    }
}
