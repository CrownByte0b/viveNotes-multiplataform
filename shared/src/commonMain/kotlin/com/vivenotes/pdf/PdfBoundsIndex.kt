package com.vivenotes.pdf

import kotlin.math.floor

/** Mutable broad phase for fitting; exact overlap and same-id exclusion remain the caller's rules. */
internal class PdfBoundsIndex(private val cellWidth: Float, private val cellHeight: Float,
    initial: List<PdfBounds>) {
    private val boxes = initial.toMutableList()
    private val buckets = HashMap<Pair<Int, Int>, MutableSet<Int>>()
    private val wide = HashSet<Int>()
    private val membership = ArrayList<List<Pair<Int, Int>>?>(initial.size)

    init {
        initial.forEachIndexed { index, bounds ->
            membership += cells(bounds)
            add(index)
        }
    }

    fun update(index: Int, bounds: PdfBounds) {
        membership[index]?.forEach { key ->
            buckets[key]?.let { entries ->
                entries.remove(index)
                if (entries.isEmpty()) buckets.remove(key)
            }
        } ?: wide.remove(index)
        boxes[index] = bounds
        membership[index] = cells(bounds)
        add(index)
    }

    fun anyOverlap(bounds: PdfBounds, include: (Int) -> Boolean): Boolean {
        val cells = cells(bounds)
        if (cells == null) return boxes.indices.any { include(it) && bounds.overlaps(boxes[it]) }
        if (wide.any { include(it) && bounds.overlaps(boxes[it]) }) return true
        return cells.any { key -> buckets[key].orEmpty().any { include(it) && bounds.overlaps(boxes[it]) } }
    }

    private fun add(index: Int) {
        membership[index]?.forEach { buckets.getOrPut(it) { HashSet() }.add(index) } ?: wide.add(index)
    }

    private fun cells(bounds: PdfBounds): List<Pair<Int, Int>>? {
        if (!bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() ||
            !bounds.bottom.isFinite() || bounds.left > bounds.right || bounds.top > bounds.bottom) return null
        val left = floor(bounds.left / cellWidth).toInt()
        val right = floor(bounds.right / cellWidth).toInt()
        val top = floor(bounds.top / cellHeight).toInt()
        val bottom = floor(bounds.bottom / cellHeight).toInt()
        val count = (right.toLong() - left + 1L) * (bottom.toLong() - top + 1L)
        if (count !in 1L..16L) return null
        return buildList {
            for (column in left..right) for (row in top..bottom) add(column to row)
        }
    }
}
