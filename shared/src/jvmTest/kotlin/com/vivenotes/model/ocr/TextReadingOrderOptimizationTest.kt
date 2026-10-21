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
}
