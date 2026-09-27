package com.vivenotes.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.PageStyle
import com.vivenotes.model.PrintMargins
import com.vivenotes.model.RuleLines
import com.vivenotes.ui.canvas.PageExtent
import com.vivenotes.ui.canvas.documentExtent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class CanvasPalette(
    val background: Color,
    val rule: Color,
    val ink: Color,
    val secondaryInk: Color,
)

/**
 * The page's colours. A page colour from the View tab wins; otherwise [canvasDark] — Switch
 * Background's override, or the theme until it is used — picks the canvas's own.
 */
internal fun canvasPalette(style: PageStyle, canvasDark: Boolean): CanvasPalette {
    val background = style.backgroundArgb?.let(::Color)
        ?: if (canvasDark) Color(0xFF1F1F1F) else Color.White
    val dark = background.luminance() < 0.45f
    return CanvasPalette(
        background = background,
        rule = if (dark) Color(0xFF2C3947) else Color(0xFFD8E4F0),
        ink = if (dark) Color(0xFFE6E6E6) else Color(0xFF1B1B1B),
        secondaryInk = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B),
    )
}

/**
 * The writable page: its colour, its ruling, and the marks describing the sheet, all in the same dp
 * coordinates as the outlines.
 *
 * A page bound by its sheet is a fixed rectangle on a larger canvas, so the ruling stops at the
 * paper's edge and the canvas beyond reads as off the page. A page the content has outgrown is
 * ruled to the canvas's edge, and the sheet shrinks to a dashed guide saying where it would end.
 *
 * Only what [window] — the visible page rectangle in unzoomed pixels, read while drawing so that
 * scrolling redraws this and recomposes nothing — can show is ruled: an endless page has no upper
 * bound on its lines. Ruling finer than [MinRuleSpacingPx] on screen is left out; at that density it
 * would be a flat wash that costs a dot per few pixels to draw.
 */
@Composable
internal fun CanvasPaper(
    style: PageStyle,
    palette: CanvasPalette,
    extent: PageExtent,
    canvasSize: DpSize,
    zoom: Float,
    window: () -> Rect,
    showMargins: Boolean,
    selectionRect: Pair<Offset, Offset>?,
) {
    Box {
        Spacer(
            Modifier
                .drawBehind {
                    val sheet = extent.sheet
                    val paper = if (extent.bound && sheet != null) {
                        Size(sheet.width.toPx(), sheet.height.toPx())
                    } else size
                    drawRect(palette.background, size = paper)
                    val visible = window().intersect(Rect(Offset.Zero, paper))
                    if (!visible.isEmpty) drawRuling(style.ruleLines, palette.rule, visible, zoom)
                }
                .documentExtent(canvasSize)
                .testTag(WorkspaceTestTags.CanvasBackground),
        )
        extent.sheet?.let { sheet ->
            // Anchored to the page's corner, so it still describes the sheet on a page whose
            // writable area has grown past it.
            Spacer(
                Modifier
                    .size(sheet)
                    .testTag(if (extent.bound) WorkspaceTestTags.PageSheet else WorkspaceTestTags.SheetGuide)
                    .drawBehind {
                        if (extent.bound) {
                            drawRect(palette.rule, style = Stroke(width = 1.dp.toPx()))
                        } else {
                            drawRect(palette.secondaryInk, style = Stroke(width = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
                        }
                        // Nothing prints yet, so the guides are what makes a margin observable.
                        // They are drawn only while the Paper Size pane that edits them is open.
                        if (showMargins) drawMarginGuides(style.margins, palette.secondaryInk)
                    },
            )
        }
        selectionRect?.let { (a, b) ->
            Spacer(
                Modifier
                    .drawBehind {
                        val left = minOf(a.x, b.x).dp.toPx()
                        val top = minOf(a.y, b.y).dp.toPx()
                        val rectangle = Size(abs(a.x - b.x).dp.toPx(), abs(a.y - b.y).dp.toPx())
                        drawRect(Color(0xFF1B6FA8).copy(alpha = 0.12f), Offset(left, top), rectangle)
                        drawRect(Color(0xFF1B6FA8), Offset(left, top), rectangle,
                            style = Stroke(width = 2.dp.toPx()))
                    }
                    .documentExtent(canvasSize),
            )
        }
    }
}

/** Below this many screen pixels between rules, the ruling is not drawn. */
internal const val MinRuleSpacingPx = 3f

private const val DottedRuleRadiusDp = 0.8f
private const val HexagonWidthRatio = 1.7320508f
private const val HexagonRuleAlpha = 0.5f

/**
 * Android's `PageRuling`: lines snap to the page's own grid, not the window's, so they stay where
 * the page puts them however far it has been scrolled.
 */
private fun DrawScope.drawRuling(rules: RuleLines, color: Color, visible: Rect, zoom: Float) {
    val step = rules.spacingDp.dp.toPx()
    if (step <= 0f || step * zoom < MinRuleSpacingPx) return
    when {
        rules.hexagonal -> {
            val side = step
            val hexWidth = side * HexagonWidthRatio
            val rowStep = side * 1.5f
            val firstRow = floor((visible.top - side - side) / rowStep).toInt().coerceAtLeast(0)
            val lastRow = ceil((visible.bottom - side + side) / rowStep).toInt()
            val hexagons = Path()
            for (row in firstRow..lastRow) {
                val centerY = side + row * rowStep
                // Odd rows shift left, so their partial first cell rules the page edge.
                val firstCenterX = hexWidth / 2f + if (row % 2 == 0) 0f else -hexWidth / 2f
                val firstColumn = floor((visible.left - firstCenterX - hexWidth / 2f) / hexWidth)
                    .toInt().coerceAtLeast(0)
                val lastColumn = ceil((visible.right - firstCenterX + hexWidth / 2f) / hexWidth).toInt()
                for (column in firstColumn..lastColumn) {
                    val centerX = firstCenterX + column * hexWidth
                    // Pointy-top: vertices at 12 and 6 o'clock, sides vertical.
                    hexagons.moveTo(centerX, centerY - side)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY - side / 2f)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX, centerY + side)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY - side / 2f)
                    hexagons.close()
                }
            }
            clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                drawPath(hexagons, color.copy(alpha = color.alpha * HexagonRuleAlpha), style = Stroke(width = 1f))
            }
        }
        rules.dotted -> {
            val radius = DottedRuleRadiusDp.dp.toPx()
            var y = maxOf(step, ceil(visible.top / step) * step)
            while (y < visible.bottom) {
                var x = maxOf(step, ceil(visible.left / step) * step)
                while (x < visible.right) {
                    drawCircle(color, radius, Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        else -> {
            var y = maxOf(step, ceil(visible.top / step) * step)
            while (y < visible.bottom) {
                drawLine(color, Offset(visible.left, y), Offset(visible.right, y), strokeWidth = 1f)
                y += step
            }
            if (!rules.squared) return
            var x = maxOf(step, ceil(visible.left / step) * step)
            while (x < visible.right) {
                drawLine(color, Offset(x, visible.top), Offset(x, visible.bottom), strokeWidth = 1f)
                x += step
            }
        }
    }
}

/** Dashed rules where the printable area would begin, inside the sheet this scope is sized to. */
private fun DrawScope.drawMarginGuides(margins: PrintMargins, color: Color) {
    fun inches(value: Float) = (value * PageStyle.DP_PER_INCH).dp.toPx()
    val inset = Rect(
        left = inches(margins.leftInches),
        top = inches(margins.topInches),
        right = size.width - inches(margins.rightInches),
        bottom = size.height - inches(margins.bottomInches),
    )
    // Margins wider than the sheet leave no printable area to mark.
    if (inset.width <= 0f || inset.height <= 0f) return
    drawRect(color.copy(alpha = 0.7f), inset.topLeft, inset.size,
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
}
