package com.vivenotes.ui.canvas

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PictureDemandTest {
    @Test
    fun duplicatePlacementsUseTheLargestTargetAndAllVisiblePicturesFit() {
        val targets = boundedPictureDemands(listOf(PictureDemand("same", 400, 200),
            PictureDemand("same", 800, 400), PictureDemand("other", 5000, 5000)))
        assertEquals(listOf(PictureDemand("same", 800, 400), PictureDemand("other", 2048, 2048)), targets)
        val crowded = List(20) { PictureDemand("picture-$it", 2048, 1024) }
        val planned = boundedPictureDemands(crowded, 1_000_000)
        assertEquals(crowded.map { it.attachmentId }, planned.map { it.attachmentId })
        assertTrue(planned.sumOf { it.bytes } <= 1_000_000)
        assertTrue(planned.all { it.widthPx in (it.heightPx * 2 - 1)..(it.heightPx * 2 + 1) })
        assertEquals(planned, boundedPictureDemands(crowded, 1_000_000))
    }

    @Test
    fun extremeAspectRatiosAndRandomDemandSetsRespectTheByteBudget() {
        assertEquals(listOf(PictureDemand("wide", 1, 1), PictureDemand("tall", 1, 1)),
            boundedPictureDemands(listOf(PictureDemand("wide", 2048, 1), PictureDemand("tall", 1, 2048)), 8))
        val random = Random(885)
        repeat(200) {
            val requested = List(100) { PictureDemand("picture-${random.nextInt(30)}",
                random.nextInt(1, 9000), random.nextInt(1, 9000)) }
            val budget = random.nextLong(128, 2_000_000)
            val result = boundedPictureDemands(requested, budget)
            assertEquals(requested.map { it.attachmentId }.toSet(), result.map { it.attachmentId }.toSet())
            assertTrue(result.sumOf { it.bytes } <= budget)
            assertTrue(result.all { it.widthPx > 0 && it.heightPx > 0 })
        }
        assertFailsWith<IllegalArgumentException> { boundedPictureDemands(listOf(PictureDemand("a", 1, 1)), 3) }
    }

    @Test
    fun memoryAdmissionEvictsUnusedLruEntriesAndKeepsVisiblePicturesPinned() {
        val cache = PictureMemoryCache<String>(8) { it.length.toLong() }
        assertTrue(cache.put("a", "aaaa", emptySet()))
        assertTrue(cache.put("b", "bbbb", emptySet()))
        assertEquals("aaaa", cache.get("a"))
        assertTrue(cache.put("c", "cccc", setOf("a")))
        assertEquals(null, cache.get("b"))
        assertFalse(cache.put("d", "dddd", setOf("a", "c", "d")))
        assertEquals(8L, cache.bytes)
        assertTrue(cache.put("a", "aa", setOf("a", "c")))
        assertEquals(6L, cache.bytes)
        assertTrue(cache.put("d", "dd", setOf("a", "c", "d")))
        cache.trimCount(2, setOf("d"))
        assertEquals(2, cache.size)
        assertTrue(cache.bytes <= 8)
        cache.clear()
        assertEquals(0L, cache.bytes)
    }
}
