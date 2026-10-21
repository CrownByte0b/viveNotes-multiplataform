package com.vivenotes.model.ink

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PreparedLassoRegionTest {
    @Test fun preparedEdgesPreserveExactOriginalContainmentAndBoundaryTolerance() {
        val random = Random(76181)
        repeat(1_000) {
            val path = List(random.nextInt(0, 50)) {
                InkSample(random.nextInt(-500, 500) / 3f, random.nextInt(-500, 500) / 7f)
            }
            val region = PreparedLassoRegion(path)
            repeat(25) {
                val left = random.nextInt(-500, 500) / 4f
                val top = random.nextInt(-500, 500) / 5f
                val bounds = InkSelectionBounds(left, top, left + random.nextInt(0, 50), top + random.nextInt(0, 50))
                assertEquals(path.referenceEncloses(bounds), region.encloses(bounds))
            }
        }
        val special = listOf(InkSample(Float.NaN, 0f), InkSample(Float.POSITIVE_INFINITY, 1f),
            InkSample(0f, Float.NEGATIVE_INFINITY), InkSample(0f, 0f))
        val bounds = InkSelectionBounds(0f, 0f, 2f, 2f)
        assertEquals(special.referenceEncloses(bounds), PreparedLassoRegion(special).encloses(bounds))
    }

    @Test fun repeatedOutlineCornersDoNotReadOrRebuildGestureEdges() {
        var reads = 0
        val values = listOf(InkSample(0f, 0f), InkSample(300f, 0f), InkSample(300f, 300f), InkSample(0f, 300f))
        val counted = object : AbstractList<InkSample>() {
            override val size: Int get() = values.size
            override fun get(index: Int): InkSample { reads++; return values[index] }
        }
        val region = PreparedLassoRegion(counted)
        val preparationReads = reads
        repeat(2_000) { assertEquals(true, region.encloses(InkSelectionBounds(20f, 20f, 200f, 200f))) }
        assertEquals(preparationReads, reads)
    }
}

private fun List<InkSample>.referenceEncloses(bounds: InkSelectionBounds): Boolean {
    if (size < 3) return false
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        var previous = last()
        for (current in this) {
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val length = dx * dx + dy * dy
            val fraction = if (length == 0f) 0f else ((x - previous.x) * dx + (y - previous.y) * dy) / length
            val nearestX = previous.x + fraction.coerceIn(0f, 1f) * dx
            val nearestY = previous.y + fraction.coerceIn(0f, 1f) * dy
            if ((x - nearestX) * (x - nearestX) + (y - nearestY) * (y - nearestY) <= 16f) return true
            if ((current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x) inside = !inside
            previous = current
        }
        return inside
    }
    return contains(bounds.left, bounds.top) && contains(bounds.right, bounds.top) &&
        contains(bounds.right, bounds.bottom) && contains(bounds.left, bounds.bottom)
}
