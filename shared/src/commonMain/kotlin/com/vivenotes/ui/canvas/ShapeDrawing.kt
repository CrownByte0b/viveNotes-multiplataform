package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vivenotes.model.Outline
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.contours
import com.vivenotes.model.ink.fillRegion

/** Draw stored Android shape segments in page units, including hidden edges and fills. */
fun DrawScope.drawDocumentShape(shape: Outline.Shape, originX: Float = shape.x,
                                originY: Float = shape.y,
                                canvasInk: Color = Color.Black) {
    fun FloatArray.path(): Path = Path().apply {
        if (this@path.size < 2) return@apply
        moveTo((this@path[0] - originX) * density, (this@path[1] - originY) * density)
        for (index in 2 until this@path.size step 2)
            lineTo((this@path[index] - originX) * density, (this@path[index + 1] - originY) * density)
        close()
    }
    shape.fillArgb?.let { argb ->
        shape.fillRegion().forEach { region ->
            if (region.size >= 6) drawPath(region.path(), Color(argb))
        }
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
        val border = when (shape.borderFollowsTheme) {
            true -> canvasInk
            false -> Color(shape.borderArgb)
            null -> if (shape.borderArgb == 0xFF000000.toInt() ||
                shape.borderArgb == 0xFFFFFFFF.toInt()) canvasInk else Color(shape.borderArgb)
        }
        drawPath(path, border, style = Stroke(width = width,
            cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    }
}
