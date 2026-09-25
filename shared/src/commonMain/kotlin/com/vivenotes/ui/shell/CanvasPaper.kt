package com.vivenotes.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.model.PageStyle
import kotlin.math.abs

internal data class CanvasPalette(
    val background: Color,
    val rule: Color,
    val ink: Color,
    val secondaryInk: Color,
)

internal fun canvasPalette(style: PageStyle, shellDark: Boolean): CanvasPalette {
    val background = style.backgroundArgb?.let(::Color)
        ?: if (shellDark) Color(0xFF1F1F1F) else Color.White
    val dark = background.luminance() < 0.45f
    return CanvasPalette(
        background = background,
        rule = if (dark) Color(0xFF2C3947) else Color(0xFFD8E4F0),
        ink = if (dark) Color(0xFFE6E6E6) else Color(0xFF1B1B1B),
        secondaryInk = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B),
    )
}

/** Android page ruling and sheet edge, drawn in the same dp coordinates as its outlines. */
@Composable
internal fun CanvasPaper(
    style: PageStyle,
    palette: CanvasPalette,
    sheetFits: Boolean,
    selectionRect: Pair<Offset, Offset>?,
) {
    Canvas(Modifier.fillMaxSize().testTag(WorkspaceTestTags.CanvasBackground)) {
        val sheet = style.pageSizeDp
        val sheetWidth = sheet?.first?.dp?.toPx() ?: size.width
        val sheetHeight = sheet?.second?.dp?.toPx() ?: size.height
        val paperWidth = if (sheetFits) sheetWidth else size.width
        val paperHeight = if (sheetFits) sheetHeight else size.height
        drawRect(palette.background, size = Size(paperWidth, paperHeight))
        val step = style.ruleLines.spacingDp.dp.toPx()
        if (step > 0f) {
            clipRect(right = paperWidth, bottom = paperHeight) {
                when {
                    style.ruleLines.hexagonal -> {
                        val hexWidth = step * 1.7320508f
                        val rowStep = step * 1.5f
                        val path = Path()
                        var row = 0
                        while (row * rowStep < paperHeight + step) {
                            val cy = step + row * rowStep
                            val shift = if (row % 2 == 0) 0f else -hexWidth / 2f
                            var col = 0
                            while (col * hexWidth < paperWidth + hexWidth) {
                                val cx = hexWidth / 2f + shift + col * hexWidth
                                path.moveTo(cx, cy - step)
                                path.lineTo(cx + hexWidth / 2f, cy - step / 2f)
                                path.lineTo(cx + hexWidth / 2f, cy + step / 2f)
                                path.lineTo(cx, cy + step)
                                path.lineTo(cx - hexWidth / 2f, cy + step / 2f)
                                path.lineTo(cx - hexWidth / 2f, cy - step / 2f)
                                path.close()
                                col++
                            }
                            row++
                        }
                        drawPath(path, palette.rule.copy(alpha = palette.rule.alpha * 0.5f),
                            style = Stroke(width = 1f))
                    }
                    style.ruleLines.dotted -> {
                        var y = step
                        while (y < paperHeight) {
                            var x = step
                            while (x < paperWidth) {
                                drawCircle(palette.rule, radius = 0.8.dp.toPx(), center = Offset(x, y))
                                x += step
                            }
                            y += step
                        }
                    }
                    else -> {
                        var y = step
                        while (y < paperHeight) {
                            drawLine(palette.rule, Offset(0f, y), Offset(paperWidth, y), 1f)
                            y += step
                        }
                        if (style.ruleLines.squared) {
                            var x = step
                            while (x < paperWidth) {
                                drawLine(palette.rule, Offset(x, 0f), Offset(x, paperHeight), 1f)
                                x += step
                            }
                        }
                    }
                }
            }
        }
        if (sheet != null) {
            drawRect(if (sheetFits) palette.rule else palette.secondaryInk,
                size = Size(sheetWidth, sheetHeight),
                style = Stroke(width = if (sheetFits) 1.dp.toPx() else 2f,
                    pathEffect = if (sheetFits) null
                        else PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
        }
        selectionRect?.let { (a, b) ->
            val left = minOf(a.x, b.x).dp.toPx()
            val top = minOf(a.y, b.y).dp.toPx()
            val rectangle = Size(abs(a.x - b.x).dp.toPx(), abs(a.y - b.y).dp.toPx())
            drawRect(Color(0xFF1B6FA8).copy(alpha = 0.12f), Offset(left, top), rectangle)
            drawRect(Color(0xFF1B6FA8), Offset(left, top), rectangle,
                style = Stroke(width = 2.dp.toPx()))
        }
    }
}
