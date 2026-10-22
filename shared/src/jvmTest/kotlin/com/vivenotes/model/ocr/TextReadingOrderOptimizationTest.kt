package com.vivenotes.model.ocr

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TextReadingOrderOptimizationTest {
    private fun quad(top: Float, left: Float, height: Float) = TextDetection.Quad(listOf(
        TextDetection.Point(left, top), TextDetection.Point(left + 20f, top),
        TextDetection.Point(left + 20f, top + height), TextDetection.Point(left, top + height)), 1f)

    @Test
    fun everyRegionIsMeasuredOnceEvenWhenEveryLineCreatesAnotherRow() {
        val regions = List(400) { it to quad(it * 10f, 0f, 2f) }.reversed()
        var measurements = 0
        val ordered = TextDetection.readingOrder(regions) { measurements++; it.second }
        assertEquals((0 until 400).toList(), ordered.map { it.first })
        assertEquals(400, measurements)
    }

    @Test
    fun cachedMetricsKeepOriginalRowToleranceAndStableOrder() {
        val random = Random(174)
        repeat(200) {
            val regions = List(80) { index -> index to quad(random.nextInt(30).toFloat(),
                random.nextInt(20).toFloat(), random.nextInt(1, 15).toFloat()) }
            val sorted = regions.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            val rows = mutableListOf<MutableList<Pair<Int, TextDetection.Quad>>>()
            for (item in sorted) {
                val row = rows.firstOrNull { existing ->
                    val reference = existing.first().second
                    abs(item.second.top - reference.top) <= min(item.second.height, reference.height) * .5f
                }
                if (row == null) rows += mutableListOf(item) else row += item
            }
            val expected = rows.flatMap { it.sortedBy { it.second.left } }
            assertEquals(expected, TextDetection.readingOrder(regions) { it.second })
        }
    }

    @Test
    fun denseOneRowKeepsStableLeftTiesForEveryInputPermutation() {
        val regions = List(512) { index -> index to quad(100f, (index % 7).toFloat(), 20f) }
        val random = Random(304)
        repeat(12) {
            val shuffled = regions.shuffled(random)
            assertEquals(shuffled.sortedBy { it.second.left }, TextDetection.readingOrder(shuffled) { it.second })
        }
    }

    @Test
    fun overlappingRowsPreferFirstCreatedReferenceOverTheClosestTop() {
        val regions = listOf(0 to quad(0f, 50f, 20f), 1 to quad(6f, 0f, 2f),
            2 to quad(6.5f, -5f, 20f), 3 to quad(6.5f, -5f, 20f))
        assertEquals(listOf(2, 3, 0, 1), TextDetection.readingOrder(regions) { it.second }.map { it.first })
    }

    @Test
    fun indexedCandidatesKeepSubpixelRoundedAndNonFiniteOrderingContracts() {
        val random = Random(912)
        val coordinates = listOf(-0f, 0f, Float.MIN_VALUE, -Float.MIN_VALUE, 1f,
            Float.fromBits(0x4b000001), Float.fromBits(0x4b000002),
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN)
        val heights = listOf(0f, Float.MIN_VALUE, .5f, 2f, 16f, Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NaN)
        repeat(400) {
            val regions = List(40) { index -> index to quad(coordinates.random(random),
                random.nextInt(10).toFloat(), heights.random(random)) }
            val sorted = regions.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            val rows = mutableListOf<MutableList<Pair<Int, TextDetection.Quad>>>()
            for (item in sorted) {
                val row = rows.firstOrNull { existing ->
                    val reference = existing.first().second
                    abs(item.second.top - reference.top) <= min(item.second.height, reference.height) * .5f
                }
                if (row == null) rows += mutableListOf(item) else row += item
            }
            assertEquals(rows.flatMap { it.sortedBy { it.second.left } },
                TextDetection.readingOrder(regions) { it.second })
        }
    }
}
