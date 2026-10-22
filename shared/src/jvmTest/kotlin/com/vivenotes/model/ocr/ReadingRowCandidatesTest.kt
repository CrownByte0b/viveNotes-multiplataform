package com.vivenotes.model.ocr

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingRowCandidatesTest {
    @Test
    fun separatedRowsDoNotRevisitAllEarlierRows() {
        val candidates = ReadingRowCandidates(400)
        var checked = 0
        repeat(400) { row ->
            assertEquals(null, candidates.first(row * 18f, 12f) { checked++; false })
            candidates.add(row * 18f, 12f)
        }
        assertEquals(0, checked, "The original first-row scan tested 79,800 references")
    }

    @Test
    fun candidatesKeepFirstCreatedMatchingRowAndRoundedSubtractionTies() {
        val random = Random(7481)
        repeat(600) {
            val referenceTops = List(80) {
                if (it % 5 == 0) Float.fromBits(0x4b000000 + random.nextInt(60))
                else random.nextInt(-200, 500).toFloat()
            }.sorted()
            val heights = List(80) { index -> when (index % 11) {
                0 -> Float.POSITIVE_INFINITY
                1 -> Float.NaN
                2 -> Float.MIN_VALUE
                else -> random.nextInt(1, 1000).toFloat()
            } }
            val candidates = ReadingRowCandidates(referenceTops.size)
            referenceTops.indices.forEach { candidates.add(referenceTops[it], heights[it]) }
            repeat(10) { query ->
                val top = referenceTops.last() + query * 2f
                val height = if (query == 9) Float.POSITIVE_INFINITY else random.nextInt(1, 1000).toFloat()
                fun matches(index: Int) = abs(top - referenceTops[index]) <= min(height, heights[index]) * .5f
                val expected = referenceTops.indices.firstOrNull(::matches)
                assertEquals(expected, candidates.first(top, height, ::matches))
            }
        }
    }

    @Test
    fun tinyQuerySkipsOldTallRowsWithoutLosingAnEligibleLaterRow() {
        val candidates = ReadingRowCandidates(400)
        repeat(399) { candidates.add(it.toFloat(), Float.MAX_VALUE) }
        candidates.add(399f, 2f)
        var checked = 0
        assertEquals(399, candidates.first(400f, 2f) { index -> checked++; index == 399 })
        assertTrue(checked <= 2)
    }

    @Test
    fun denseOverlappingCandidatesReturnTheFirstCreatedRow() {
        val candidates = ReadingRowCandidates(512)
        repeat(512) { candidates.add(100f, 20f) }
        var checked = 0
        repeat(400) {
            assertEquals(0, candidates.first(110f, 20f) { checked++; true })
        }
        assertEquals(400, checked, "overlap must not enumerate rows after the first exact match")
    }

    @Test
    fun correlatedAggregateBoundsDoNotReplaceExactLeafComparisons() {
        val tops = buildList {
            repeat(256) { pair -> add(pair * 4f); add(pair * 4f + 1f) }
            add(4096f)
        }
        val heights = tops.mapIndexed { index, top ->
            if (index % 2 == 0 && index < 512) (4096f - top) * 2f - .5f else 0f
        }
        val candidates = ReadingRowCandidates(tops.size)
        tops.indices.forEach { candidates.add(tops[it], heights[it]) }
        // Each pair's newest top and greatest height belong to different rows. Their aggregate
        // bound overlaps the query even though neither earlier leaf is an exact match.
        for (queryTop in listOf(4096f, 4096.25f, Float.MAX_VALUE)) {
            for (queryHeight in listOf(0f, Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY)) {
                fun matches(index: Int) = abs(queryTop - tops[index]) <= min(queryHeight, heights[index]) * .5f
                assertEquals(tops.indices.firstOrNull(::matches),
                    candidates.first(queryTop, queryHeight, ::matches), "$queryTop/$queryHeight")
            }
        }
    }
}
