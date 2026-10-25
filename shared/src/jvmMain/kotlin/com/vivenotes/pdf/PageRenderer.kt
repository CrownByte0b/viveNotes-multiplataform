// Ported from viveNotes Android pdf/PageRenderer.kt; Skia replaces Android Canvas and Paint.
package com.vivenotes.pdf

import org.jetbrains.skia.*
import androidx.compose.ui.graphics.asComposeCanvas
import com.vivenotes.data.automaticColorOr
import com.vivenotes.pdf.PdfBounds as InkBounds
import com.vivenotes.model.Outline
import com.vivenotes.model.RuleLines
import com.vivenotes.model.ink.LineType
import com.vivenotes.model.ink.ShapeContour
import com.vivenotes.model.ink.contours
import com.vivenotes.model.ink.fillRegion
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws one sheet of an export.
 *
 * One renderer serves both callers: a PDF sheet and a preview bitmap differ only in the scale they
 * are handed. Everything below works in page dp, the unit outlines, shapes, tables, equations and
 * ink all share, and the paint order is the canvas's composition order, which is what makes the
 * output match the screen.
 *
 * Text is the one exception and has to be: a font size mark measures itself against the device's
 * display metrics, so the layouts [PageMeasurer] built are in device pixels and are drawn through a
 * `1 / density` scale.
 */
class PageRenderer : AutoCloseable {

    private val density = 1f

    private val fill = Paint().apply { isAntiAlias = true }.apply { mode = PaintMode.FILL }
    private val line = Paint().apply { isAntiAlias = true }.apply { mode = PaintMode.STROKE }
    private val image = Paint().apply { isAntiAlias = true }
    private val inkRenderer = com.vivenotes.byteink.compose.InkPathRenderer()
    internal var useVisibilityIndex = true
    internal var work: PdfWorkCounts? = null
    fun clearCache() = inkRenderer.clearCache()
    override fun close() { clearCache(); fill.close(); line.close(); image.close() }

    /**
     * Paints [tile] onto [canvas] as a whole sheet: background, margins, and the rectangle of page
     * that lands inside them.
     *
     * [scale] is what one page dp is worth in the output — `PdfPaper.POINTS_PER_DP` for a PDF page,
     * pixels per dp for a preview. The canvas is left as it was found.
     */
    fun drawSheet(
        canvas: Canvas,
        page: MeasuredPage,
        tile: PdfTile,
        plan: PdfPagePlan,
        paper: PdfPaper,
        ruling: Boolean,
        scale: Float,
    ) {
        val checkpoint = canvas.save()
        canvas.scale(scale, scale)

        fill.color = page.colors.backgroundArgb
        fill.alpha = Color.getA(page.colors.backgroundArgb)
        canvas.drawRect(0f, 0f, paper.widthDp, paper.heightDp, fill)

        // A bound page is already a sheet, so its tile lands on the paper's own
        // corner. Everything else is a cut out of the canvas, and it lands inside the margins.
        val insetLeft = if (plan.bound) 0f else paper.marginLeftDp
        val insetTop = if (plan.bound) 0f else paper.marginTopDp

        canvas.translate(insetLeft, insetTop)
        canvas.clipRect(Rect.makeWH(tile.area.width, tile.area.height))
        canvas.translate(-tile.area.left, -tile.area.top)
        drawContent(canvas, page, tile, plan, ruling)

        canvas.restoreToCount(checkpoint)
    }

    private fun drawContent(
        canvas: Canvas,
        page: MeasuredPage,
        tile: PdfTile,
        plan: PdfPagePlan,
        ruling: Boolean,
    ) {
        val visible = tile.area
        val content = if (useVisibilityIndex) page.visibleContent(plan, tile) else page.allContent
        // An outline's own rectangle decides whether it lands on this sheet; the *group's* shift
        // decides where. Asking the plan for an outline's own shift would find none — the plan moves
        // whole entities — and the one part of a diagram whose id it did not know would stay behind.
        fun shiftOf(id: String): PdfShift = page.shiftOf(id, plan)
        fun lands(id: String): Boolean {
            val bounds = page.boundsOf(id) ?: return true
            val shift = shiftOf(id)
            return bounds.translated(shift.dx, shift.dy).overlaps(visible)
        }

        if (ruling && page.style.ruleLines != RuleLines.None) {
            // A page bound to its sheet rules the sheet and stops. One whose content has outgrown
            // the sheet is writable everywhere, so the ruling goes wherever the cut does — which is
            // exactly what `EditorPane.PageSurface` does with the same two cases.
            val limit = page.style.pageSizeDp
                ?.takeIf { plan.bound }
                ?.let { (width, height) -> InkBounds(0f, 0f, width, height) }
            drawRuling(canvas, page.style.ruleLines, page.colors.ruleLineArgb, visible, limit)
        }

        content.texts.forEach { outline ->
            work?.let { it.outlinesVisited++ }
            val block = page.texts[outline.id] ?: return@forEach
            if (!lands(outline.id)) return@forEach
            drawLayout(canvas, block, shiftOf(outline.id), visible)
        }

        content.shapes.forEach { shape ->
            work?.let { it.outlinesVisited++ }
            val shift = shiftOf(shape.id)
            if (!shape.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            drawShape(canvas, shape, page.colors.textArgb, shift)
        }

        content.equations.forEach { equation ->
            work?.let { it.outlinesVisited++ }
            val shift = shiftOf(equation.id)
            if (!equation.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            drawEquation(canvas, equation, page, shift)
        }

        content.pictures.forEach { picture ->
            work?.let { it.outlinesVisited++ }
            val shift = shiftOf(picture.id)
            if (!picture.pageRect().translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            val bitmap = page.images[picture.attachmentId]
            val target = Rect(
                picture.x + shift.dx,
                picture.y + shift.dy,
                picture.x + picture.width + shift.dx,
                picture.y + picture.height + shift.dy,
            )
            if (bitmap == null) {
                // A picture whose bytes cannot be reached is not a picture; its frame is still where
                // the writer put it, so the space it holds is kept rather than silently closed up.
                line.color = page.colors.ruleLineArgb
                line.strokeWidth = 1f
                line.pathEffect = null
                canvas.drawRect(target, line)
            } else {
                picture.backgroundArgb?.let { argb ->
                    fill.color = argb
                    canvas.drawRect(target, fill)
                }
                canvas.drawImageRect(bitmap, target, image)
            }
        }

        page.header?.takeIf { it.bounds.overlaps(visible) }?.let { drawHeader(canvas, it, page) }

        content.tables.forEach { table ->
            work?.let { it.outlinesVisited++ }
            val grid = page.tables[table.id] ?: return@forEach
            if (!lands(table.id)) return@forEach
            drawTable(canvas, table, grid, page, shiftOf(table.id), visible)
        }

        // Ink last, as it is on screen: a stroke is drawn over whatever it was written across.
        content.groups.forEach { group ->
            work?.let { it.groupsVisited++ }
            val shift = plan.shiftFor(group.id)
            if (!group.bounds.translated(shift.dx, shift.dy).overlaps(visible)) return@forEach
            group.strokes.forEach { stroke -> drawStroke(canvas, stroke, page.colors.textArgb, shift) }
        }
    }

    // --- ink ---------------------------------------------------------------------------------

    /**
     * A stroke as ByteInk's renderer draws it, at the page's transform plus the fit's [shift], in its
     * automatic or stored colour. The page is recorded as vector paths, so ink stays vector in the PDF.
     */
    private fun drawStroke(
        canvas: Canvas,
        stroke: com.vivenotes.byteink.kit.PageStroke,
        canvasInkArgb: Int,
        shift: PdfShift,
    ) {
        inkRenderer.draw(canvas.asComposeCanvas(), stroke.stroke,
            androidx.ink.geometry.ImmutableAffineTransform(stroke.scaleX, 0f, stroke.offsetX + shift.dx,
                0f, stroke.scaleY, stroke.offsetY + shift.dy),
            colorArgb = automaticColorOr(stroke.stroke.brush.colorIntArgb, stroke.colorFollowsTheme, canvasInkArgb))
    }

    // --- text --------------------------------------------------------------------------------

    private fun drawLayout(canvas: Canvas, block: PdfTextBlock, shift: PdfShift, visible: InkBounds) {
        val checkpoint = canvas.save()
        canvas.translate(block.leftDp + shift.dx, block.topDp + shift.dy)
        canvas.scale(1f / density, 1f / density)
        block.layout.drawVisible(canvas, if (useVisibilityIndex) visible.translated(
            -block.leftDp - shift.dx, -block.topDp - shift.dy) else null, work)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawHeader(canvas: Canvas, header: PdfPageHeader, page: MeasuredPage) {
        drawHeaderText(canvas, header.title, header.leftDp, header.titleBaselineDp, PageMeasurer.TITLE_SP,
            if (page.title.isEmpty()) page.colors.secondaryTextArgb else page.colors.textArgb, header.widthDp)
        if (header.subtitle.isNotEmpty()) drawHeaderText(canvas, header.subtitle, header.leftDp,
            header.subtitleBaselineDp, PageMeasurer.SUBTITLE_SP, page.colors.secondaryTextArgb)

        fill.color = page.colors.ruleLineArgb
        canvas.drawRect(
            header.leftDp,
            header.ruleTopDp,
            header.leftDp + header.ruleWidthDp,
            header.ruleTopDp + PageMeasurer.HEADER_RULE_HEIGHT_DP,
            fill,
        )
    }

    // --- shapes ------------------------------------------------------------------------------

    /** `ShapeLayer.drawShape`, on an `android.graphics.Canvas`. */
    private fun drawShape(canvas: Canvas, shape: Outline.Shape, canvasInkArgb: Int, shift: PdfShift) {
        val checkpoint = canvas.save()
        canvas.translate(shift.dx, shift.dy)

        shape.fillArgb?.let { argb ->
            fill.color = argb
            shape.fillRegion().forEach { region ->
                region.asClosedPath().use { canvas.drawPath(it, fill) }
            }
        }

        line.color = automaticColorOr(shape.borderArgb, shape.borderFollowsTheme, canvasInkArgb)
        line.strokeWidth = shape.borderWidth
        line.strokeCap = PaintStrokeCap.ROUND
        line.strokeJoin = PaintStrokeJoin.ROUND
        // By contour rather than by segment: a dash pattern restarts on every path it is given, so
        // stroking a rim's arcs one at a time doubles the dots at each joint.
        shape.segments.contours().forEach { contour ->
            val type = if (contour.hidden) LineType.Dotted else shape.lineType
            line.pathEffect = type.dashEffect(shape.borderWidth)
            contour.asPath().use { canvas.drawPath(it, line) }
            line.pathEffect?.close()
            line.pathEffect = null
        }
        line.pathEffect = null
        canvas.restoreToCount(checkpoint)
    }

    // --- equations ---------------------------------------------------------------------------

    /** `EquationLayer.drawEquation`: the formula stretched from its own metrics into a stored box. */
    private fun drawEquation(
        canvas: Canvas,
        equation: Outline.Equation,
        page: MeasuredPage,
        shift: PdfShift,
    ) {
        val left = equation.x + shift.dx
        val top = equation.y + shift.dy
        val renderer = page.equations[equation.id]
        if (renderer == null) {
            drawHeaderText(canvas, equation.latex, left, top + equation.height, PageMeasurer.EQUATION_BASE_DP,
                equation.colorArgb ?: page.colors.textArgb)
            return
        }
        val naturalWidth = renderer.widthPx
        val naturalHeight = renderer.heightPx + renderer.depthPx
        if (naturalWidth <= 0f || naturalHeight <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(left, top)
        canvas.scale(equation.width / naturalWidth, equation.height / naturalHeight)
        renderer.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    // --- tables ------------------------------------------------------------------------------

    /** `TableContainer`'s `drawBehind`, plus the cells its editors would have drawn. */
    private fun drawTable(
        canvas: Canvas,
        table: Outline.Table,
        grid: PdfTableGrid,
        page: MeasuredPage,
        shift: PdfShift,
        visible: InkBounds,
    ) {
        val checkpoint = canvas.save()
        canvas.translate(grid.leftDp + shift.dx, grid.topDp + shift.dy)
        val width = table.width
        val height = grid.heightDp

        table.fillArgb?.let {
            fill.color = it
            canvas.drawRect(0f, 0f, width, height, fill)
        }

        val rowTops = grid.rowTopsDp
        val localVisible = visible.translated(-grid.leftDp - shift.dx, -grid.topDp - shift.dy)

        val headerTint = page.colors.textArgb.withAlpha(HEADER_TINT_ALPHA)
        if (table.headerRow && table.rowCount > 0) {
            fill.color = headerTint
            canvas.drawRect(0f, 0f, width, rowTops[1], fill)
        }
        if (table.headerColumn && table.columnCount > 0) {
            fill.color = headerTint
            canvas.drawRect(0f, 0f, table.columns[0], height, fill)
        }

        val rule = table.borderWidth.coerceAtLeast(MIN_RULE_DP)
        line.color = automaticColorOr(table.borderArgb, table.borderFollowsTheme, page.colors.textArgb)
        line.strokeWidth = rule
        line.pathEffect = null
        line.strokeCap = PaintStrokeCap.BUTT
        var x = 0f
        for (index in 0..table.columnCount) {
            // Half a rule in at each end, so the outer rules are not clipped in half by the edge of
            // the box they bound — `TableContainer` draws them the same way.
            val at = x.coerceIn(rule / 2f, (width - rule / 2f).coerceAtLeast(rule / 2f))
            canvas.drawLine(at, 0f, at, height, line)
            if (index < table.columnCount) x += table.columns[index]
        }
        val firstRule = if (useVisibilityIndex) (rowTops.firstAtLeast(localVisible.top - rule) - 1).coerceAtLeast(0) else 0
        val endRule = if (useVisibilityIndex) (rowTops.firstAtLeast(localVisible.bottom + rule) + 1).coerceAtMost(rowTops.size) else rowTops.size
        for (index in firstRule until endRule) {
            val at = rowTops[index].coerceIn(rule / 2f, (height - rule / 2f).coerceAtLeast(rule / 2f))
            canvas.drawLine(0f, at, width, at, line)
        }

        val firstRow = if (useVisibilityIndex) (rowTops.firstAtLeast(localVisible.top - grid.paintOutset) - 1).coerceAtLeast(0) else 0
        val endRow = if (useVisibilityIndex) rowTops.firstAtLeast(localVisible.bottom + grid.paintOutset).coerceAtMost(table.rowCount) else table.rowCount
        for (rowIndex in firstRow until endRow) {
            val row = table.rows[rowIndex]
            var cellLeft = 0f
            row.cells.forEachIndexed { columnIndex, cell ->
                val layout = grid.cells[cell.id]
                if (layout != null) {
                    work?.let { it.tableCellsDrawn++ }
                    val inner = canvas.save()
                    canvas.translate(
                        cellLeft + PageMeasurer.CELL_PADDING_DP,
                        rowTops[rowIndex] + PageMeasurer.CELL_PADDING_DP,
                    )
                    canvas.scale(1f / density, 1f / density)
                    layout.drawVisible(canvas, if (useVisibilityIndex) localVisible.translated(
                        -cellLeft - PageMeasurer.CELL_PADDING_DP,
                        -rowTops[rowIndex] - PageMeasurer.CELL_PADDING_DP) else null, work)
                    canvas.restoreToCount(inner)
                }
                cellLeft += table.columns.getOrElse(columnIndex) { 0f }
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    // --- ruling ------------------------------------------------------------------------------

    /** `EditorPane.PageRuling`, in page dp and bounded by the tile rather than by a window. */
    private fun drawRuling(
        canvas: Canvas,
        rules: RuleLines,
        colorArgb: Int,
        visible: InkBounds,
        limit: InkBounds?,
    ) {
        val step = rules.spacingDp
        if (step <= 0f) return
        val area = limit?.let { visible.intersect(it) ?: return } ?: visible

        line.color = colorArgb
        line.strokeWidth = 1f
        line.pathEffect = null
        line.strokeCap = PaintStrokeCap.BUTT

        if (rules.hexagonal) {
            val side = step
            val hexWidth = side * HEXAGON_WIDTH_RATIO
            val rowStep = side * 1.5f
            val firstCenterY = side
            val firstRow = floor((area.top - firstCenterY - side) / rowStep).toInt().coerceAtLeast(0)
            val lastRow = ceil((area.bottom - firstCenterY + side) / rowStep).toInt()
            val hexagons = PathBuilder()
            for (row in firstRow..lastRow) {
                val centerY = firstCenterY + row * rowStep
                val rowOffset = if (row % 2 == 0) 0f else -hexWidth / 2f
                val firstCenterX = hexWidth / 2f + rowOffset
                val firstColumn = floor((area.left - firstCenterX - hexWidth / 2f) / hexWidth)
                    .toInt().coerceAtLeast(0)
                val lastColumn = ceil((area.right - firstCenterX + hexWidth / 2f) / hexWidth).toInt()
                for (column in firstColumn..lastColumn) {
                    val centerX = firstCenterX + column * hexWidth
                    hexagons.moveTo(centerX, centerY - side)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY - side / 2f)
                    hexagons.lineTo(centerX + hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX, centerY + side)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY + side / 2f)
                    hexagons.lineTo(centerX - hexWidth / 2f, centerY - side / 2f)
                    hexagons.closePath()
                }
            }
            line.color = colorArgb.withAlpha(Color.getA(colorArgb) / 255f * HEXAGON_RULE_ALPHA)
            hexagons.detach().use { canvas.drawPath(it, line) }
            hexagons.close()
            return
        }

        if (rules.dotted) {
            fill.color = colorArgb
            var dotY = maxOf(step, ceil(area.top / step) * step)
            while (dotY < area.bottom) {
                var dotX = maxOf(step, ceil(area.left / step) * step)
                while (dotX < area.right) {
                    canvas.drawCircle(dotX, dotY, DOTTED_RULE_RADIUS_DP, fill)
                    dotX += step
                }
                dotY += step
            }
            return
        }

        var y = maxOf(step, ceil(area.top / step) * step)
        while (y < area.bottom) {
            canvas.drawLine(area.left, y, area.right, y, line)
            y += step
        }
        if (!rules.squared) return
        var x = maxOf(step, ceil(area.left / step) * step)
        while (x < area.right) {
            canvas.drawLine(x, area.top, x, area.bottom, line)
            x += step
        }
    }

    private fun drawHeaderText(canvas: Canvas, text: String, x: Float, y: Float, size: Float,
        color: Int, maxWidth: Float = Float.MAX_VALUE) {
        org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL), size).use { font ->
            var shown = text
            if (font.measureTextWidth(shown) > maxWidth) {
                while (shown.isNotEmpty() && font.measureTextWidth("$shown…") > maxWidth) shown = shown.dropLast(1)
                shown += "…"
            }
            fill.color = color
            canvas.drawString(shown, x, y, font, fill)
        }
    }

    private companion object {
        const val DOTTED_RULE_RADIUS_DP = 0.8f
        const val HEXAGON_WIDTH_RATIO = 1.7320508f
        const val HEXAGON_RULE_ALPHA = 0.5f

        /** `TableContainer.HEADER_TINT_ALPHA`. */
        const val HEADER_TINT_ALPHA = 0.10f

        /** A rule thinner than this vanishes on paper the way a hairline does on screen. */
        const val MIN_RULE_DP = 0.4f
    }
}

private fun Outline.Shape.pageRect(): InkBounds {
    val pen = borderWidth / 2f
    return InkBounds(x - pen, y - pen, x + width + pen, y + height + pen)
}

private fun Outline.Equation.pageRect(): InkBounds = InkBounds(x, y, x + width, y + height)

private fun Outline.Image.pageRect(): InkBounds = InkBounds(x, y, x + width, y + height)

private fun InkBounds.intersect(other: InkBounds): InkBounds? {
    val left = maxOf(left, other.left)
    val top = maxOf(top, other.top)
    val right = minOf(right, other.right)
    val bottom = minOf(bottom, other.bottom)
    return if (left < right && top < bottom) InkBounds(left, top, right, bottom) else null
}

private fun Int.withAlpha(alpha: Float): Int =
    Color.makeARGB((alpha * 255f).toInt().coerceIn(0, 255), Color.getR(this), Color.getG(this), Color.getB(this))

/** `PenPanel.pathEffect`, as the platform effect rather than the Compose one. */
private fun LineType.dashEffect(width: Float): PathEffect? = when (this) {
    LineType.Solid -> null
    LineType.Dashed -> PathEffect.makeDash(floatArrayOf(width * 2.6f, width * 1.8f), 0f)
    LineType.Dotted -> PathEffect.makeDash(floatArrayOf(0.01f, width * 2f), 0f)
}

private fun FloatArray.asClosedPath(): Path = PathBuilder().use { builder -> builder.apply {
    if (size < 4) return@apply
    moveTo(this@asClosedPath[0], this@asClosedPath[1])
    for (index in 2 until size step 2) lineTo(this@asClosedPath[index], this@asClosedPath[index + 1])
    closePath()
}.detach() }

private fun ShapeContour.asPath(): Path = PathBuilder().use { builder -> builder.apply {
    val points = polyline()
    if (points.size < 4) return@apply
    moveTo(points[0], points[1])
    for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
    if (isClosed) closePath()
}.detach() }
