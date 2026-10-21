package com.vivenotes.ui.canvas

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreviewOffsetIndexTest {
    private fun originalDisplay(offset: Int, ranges: List<IntRange>): Int {
        var removed = 0
        for (range in ranges) {
            val end = range.last + 1
            if (offset <= range.first) return offset - removed
            if (offset < end) return range.first - removed + 1
            removed += end - range.first - 1
        }
        return offset - removed
    }
    private fun originalSource(offset: Int, ranges: List<IntRange>): Int {
        var removed = 0
        for (range in ranges) {
            val start = range.first - removed
            if (offset <= start) return offset + removed
            if (offset == start + 1) return range.last + 1
            removed += range.last - range.first
        }
        return offset + removed
    }

    @Test fun indexedOffsetsMatchReferenceWithAdjacentAndRepeatedEmptyRanges() {
        val random = Random(7184)
        repeat(250) {
            var next = 0
            val ranges = List(random.nextInt(0, 50)) {
                val start = next + random.nextInt(0, 5)
                val end = start + random.nextInt(0, 9)
                next = end
                start until end
            }
            val index = PreviewOffsetIndex(ranges)
            for (offset in -2..next + 5) {
                assertEquals(originalDisplay(offset, ranges), index.toDisplay(offset), "source $offset $ranges")
                assertEquals(originalSource(offset, ranges), index.toSource(offset), "display $offset $ranges")
                assertEquals(ranges.count { it.isEmpty() && it.first == offset }, index.insertionsAt(offset))
            }
        }
        assertFailsWith<IllegalArgumentException> { PreviewOffsetIndex(listOf(0 until 10, 5 until 7)) }
    }

    @Test fun replacementCoverageMatchesAnyRuleForOverlappingCandidates() {
        val ranges = listOf(8 until 8, 5 until 12, 1 until 9, 10 until 16)
        val coverage = PreviewReplacementCoverage(ranges)
        for (offset in -1..20) assertEquals(ranges.any { offset > it.first && offset <= it.last + 1 },
            coverage.coversLinkEnd(offset), "offset $offset")
    }
}
