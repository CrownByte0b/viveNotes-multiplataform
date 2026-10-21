package com.vivenotes.pdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfGroupingIndexTest {
    @Test
    fun indexedCutsMatchOriginalOrderAndTieRulesOnRandomLayouts() {
        val random = Random(8125)
        repeat(1_000) { case ->
            val boxes = List(random.nextInt(1, 65)) {
                val x = random.nextInt(-5, 16) * 20f
                val y = random.nextInt(-5, 16) * 20f
                PdfBounds(x, y, x + random.nextInt(0, 7) * 10f, y + random.nextInt(0, 7) * 10f)
            }
            val maxWidth = listOf(30f, 100f, 600f)[case % 3]
            val maxHeight = listOf(45f, 100f, 800f)[case / 3 % 3]
            val gapX = listOf(10f, 40f, 80f)[case / 9 % 3]
            val gapY = listOf(10f, 40f, 80f)[case / 27 % 3]
            val indices = boxes.indices.toList()
            assertEquals(reference(indices, boxes::get, maxWidth, maxHeight, gapX, gapY),
                segmentByWhitespace(indices, boxes::get, maxWidth, maxHeight, gapX, gapY), "case $case")
        }
    }

    @Test
    fun equalGapsKeepTheOriginalSingletonOrderAndReadEachBoundsOnce() {
        val boxes = List(4_000) { PdfBounds(0f, it * 60f, 10f, it * 60f + 10f) }
        var reads = 0
        val leaves = segmentByWhitespace(boxes.indices.toList(), { index -> reads++; boxes[index] }, 600f, 800f)
        assertEquals(boxes.size, reads)
        assertEquals(boxes.indices.map { listOf(it) }, leaves)
        val groups = groupContent(boxes.mapIndexed { index, box -> PdfAtom("atom-$index", box) }, 600f, 800f)
        assertEquals(boxes.indices.map { "group:atom-$it" }, groups.map { it.id })
        assertEquals(boxes, groups.map { it.bounds })
    }

    @Test
    fun fractionalEndpointsExactThresholdsAndZeroWidthAtomsMatchReference() {
        val boxes = listOf(PdfBounds(-2.25f, 0f, -2.25f, 12f), PdfBounds(37.75f, 0f, 50f, 12f),
            PdfBounds(0f, 52f, 20f, 52f), PdfBounds(0f, 92f, 20f, 104f))
        val indices = boxes.indices.toList()
        for (gap in listOf(0f, 40f, 40.000004f)) {
            assertEquals(reference(indices, boxes::get, 32f, 55f, gap, gap),
                segmentByWhitespace(indices, boxes::get, 32f, 55f, gap, gap))
        }
    }

    private data class Gap(val size: Float, val at: Float, val horizontal: Boolean)

    private fun <T> reference(items: List<T>, bounds: (T) -> PdfBounds, maxWidth: Float,
        maxHeight: Float, gapX: Float, gapY: Float): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val pending = ArrayDeque<List<T>>()
        pending += items
        val leaves = ArrayList<List<T>>()
        fun gap(block: List<T>, horizontal: Boolean): Gap? {
            val intervals = block.map { bounds(it).let { box ->
                if (horizontal) box.left to box.right else box.top to box.bottom
            } }.sortedBy { it.first }
            var reach = intervals.first().second
            var widest: Gap? = null
            for ((start, end) in intervals.drop(1)) {
                val size = start - reach
                if (size > 0f && (widest == null || size > widest.size)) widest = Gap(size, reach + size / 2f, horizontal)
                reach = maxOf(reach, end)
            }
            return widest
        }
        while (pending.isNotEmpty()) {
            val block = pending.removeFirst()
            if (block.size == 1) { leaves += block; continue }
            val union = block.map(bounds).union()
            val x = gap(block, true)
            val y = gap(block, false)
            val xScore = x?.let { it.size / gapX } ?: 0f
            val yScore = y?.let { it.size / gapY } ?: 0f
            val wide = union.width > maxWidth
            val tall = union.height > maxHeight
            val cut = when {
                xScore >= 1f || yScore >= 1f -> if (xScore >= yScore) x else y
                wide && x != null -> x
                tall && y != null -> y
                wide || tall -> x ?: y
                else -> null
            }
            if (cut == null) { leaves += block; continue }
            val (near, far) = block.partition { bounds(it).let { box -> if (cut.horizontal) box.left else box.top } < cut.at }
            if (near.isEmpty() || far.isEmpty()) leaves += block else { pending += near; pending += far }
        }
        return leaves
    }
}
