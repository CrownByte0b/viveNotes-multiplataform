package com.vivenotes.pdf

import com.vivenotes.model.Outline
import kotlin.math.floor

internal fun FloatArray.firstAtLeast(value: Float): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < value) low = middle + 1 else high = middle
    }
    return low
}

internal class PdfDrawContent(outlines: List<Outline>, val groups: List<PdfGroup>) {
    val texts = outlines.filterIsInstance<Outline.Text>()
    val shapes = outlines.filterIsInstance<Outline.Shape>()
    val equations = outlines.filterIsInstance<Outline.Equation>()
    val pictures = outlines.filterIsInstance<Outline.Image>()
    val tables = outlines.filterIsInstance<Outline.Table>()
}

/** A bounded grid broad phase followed by the original strict overlap predicate. */
internal class PdfDrawIndex(page: MeasuredPage, plan: PdfPagePlan) {
    private class Entries {
        val outlines = ArrayList<Outline>()
        val groups = ArrayList<PdfGroup>()
    }
    private val content: Map<Pair<Int, Int>, PdfDrawContent>

    init {
        val tiles = plan.tiles.associateBy { it.column to it.row }
        val entries = tiles.mapValues { Entries() }
        val width = plan.tiles.firstOrNull()?.area?.width ?: 1f
        val height = plan.tiles.firstOrNull()?.area?.height ?: 1f
        val originX = plan.tiles.firstOrNull()?.let { it.area.left - it.column * width } ?: 0f
        val originY = plan.tiles.firstOrNull()?.let { it.area.top - it.row * height } ?: 0f
        val maxColumn = plan.tiles.maxOfOrNull { it.column } ?: 0
        val maxRow = plan.tiles.maxOfOrNull { it.row } ?: 0
        fun visit(bounds: PdfBounds?, add: (Entries) -> Unit) {
            if (bounds == null || width <= 0f || height <= 0f ||
                !bounds.left.isFinite() || !bounds.top.isFinite() ||
                !bounds.right.isFinite() || !bounds.bottom.isFinite()) {
                entries.values.forEach(add)
                return
            }
            val firstColumn = floor((bounds.left - originX) / width).toInt().coerceIn(0, maxColumn)
            val lastColumn = floor((bounds.right - originX) / width).toInt().coerceIn(0, maxColumn)
            val firstRow = floor((bounds.top - originY) / height).toInt().coerceIn(0, maxRow)
            val lastRow = floor((bounds.bottom - originY) / height).toInt().coerceIn(0, maxRow)
            for (column in firstColumn..lastColumn) for (row in firstRow..lastRow) {
                val key = column to row
                val tile = tiles[key] ?: continue
                if (bounds.overlaps(tile.area)) add(entries.getValue(key))
            }
        }
        for (outline in page.doc.outlines) {
            val shift = page.shiftOf(outline.id, plan)
            visit(page.boundsOf(outline.id)?.translated(shift.dx, shift.dy)) { it.outlines += outline }
        }
        for (group in page.groups) {
            val shift = plan.shiftFor(group.id)
            visit(group.bounds.translated(shift.dx, shift.dy)) { it.groups += group }
        }
        content = entries.mapValues { PdfDrawContent(it.value.outlines, it.value.groups) }
    }

    fun content(tile: PdfTile): PdfDrawContent = content.getValue(tile.column to tile.row)
}

internal class PdfWorkCounts {
    var measuredPages = 0
    var hydratedPages = 0
    var paragraphsDrawn = 0
    var tableCellsDrawn = 0
    var outlinesVisited = 0
    var groupsVisited = 0
    var peakPreparedPages = 0
}
