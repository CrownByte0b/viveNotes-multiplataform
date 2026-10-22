package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.vivenotes.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextPreparationTest {
    private val key = TextMeasurementKey(listOf(Block.of("Exact text")), 500f, 80f, 1f, 1f)

    @Test fun unknownBoxesAboveTheWindowAreNeverClassifiedAsOffscreenByAGuessedHeight() {
        val window = Rect(0f, 4000f, 800f, 4700f)
        assertTrue(textMayIntersectWindow(20f, 0f, 500f, window))
        assertTrue(textMayIntersectWindow(20f, 4700f, 500f, window))
        assertFalse(textMayIntersectWindow(20f, 4701f, 500f, window))
        assertFalse(textMayIntersectWindow(801f, 0f, 500f, window))
        assertFalse(textMayIntersectWindow(-501f, 0f, 500f, window))
    }

    @Test fun backgroundAdmissionIsBoundedAndKeepsUnfinishedWork() {
        val ids = List(1_000) { "box-$it" }
        val first = planTextPreparation(ids, emptySet(), limit = 16)
        assertEquals(16, first.next.size)
        assertTrue(first.retained.isEmpty())
        val stalled = planTextPreparation(ids, first.next, limit = 16)
        assertEquals(first.next, stalled.retained)
        assertTrue(stalled.next.isEmpty())
        val remaining = ids.filterNot { it in first.next.take(10) }
        val second = planTextPreparation(remaining, first.next, limit = 16)
        assertEquals(6, second.retained.size)
        assertEquals(ids.drop(16).take(10).toSet(), second.next)
        assertEquals(16, second.retained.size + second.next.size)
        assertEquals(emptySet(), planTextPreparation(emptyList(), first.next).retained)
    }

    @Test fun cachedPlacementsRequireTheExactLayoutInputsAndLiveFonts() {
        var current = true
        val cache = TextMeasurementCache()
        cache.put("page", "box", MeasuredTextHeight(key, 231.5f) { current })
        assertEquals(231.5f, cache.get("page", "box", key)?.height)
        assertNull(cache.get("other-page", "box", key))
        assertNull(cache.get("page", "other-box", key))
        listOf(
            key.copy(blocks = listOf(Block.of("Changed"))),
            key.copy(width = 240f), key.copy(minHeight = 240f),
            key.copy(density = 1.25f), key.copy(fontScale = 1.5f), key.copy(editing = true),
            key.copy(style = TextStyle(fontSize = 32.sp)), key.copy(layoutDirection = LayoutDirection.Rtl),
        ).forEach { changed -> assertNull(cache.get("page", "box", changed)) }
        current = false
        assertNull(cache.get("page", "box", key))
    }

    @Test fun entryWeightAndPageBudgetsEvictOldPlacementsWithoutChangingTheActiveValue() {
        val placement = MeasuredTextHeight(key, 231.5f)
        val entries = TextMeasurementCache(maxEntries = 2)
        entries.put("a", "1", placement)
        entries.put("a", "2", placement)
        entries.put("a", "1", placement)
        entries.put("a", "3", placement)
        assertNull(entries.get("a", "2", key))
        assertNotNull(entries.get("a", "1", key))
        assertEquals(231.5f, placement.height)
        val weighted = TextMeasurementCache(maxWeight = 220)
        repeat(10) { weighted.put("a", "$it", placement) }
        assertTrue(weighted.weight <= 220)
        assertTrue(weighted.size in 1..2)
        val pages = TextMeasurementCache(maxPages = 2)
        listOf("a", "b", "c").forEach { pages.put(it, "box", placement) }
        assertNull(pages.get("a", "box", key))
        assertEquals(2, pages.size)
        pages.retainPages(setOf("b"))
        assertEquals(1, pages.size)
        pages.retainBoxes("b", emptySet())
        assertEquals(0, pages.size)
        assertEquals(0L, pages.weight)
    }

    @Test fun oversizeUnsettledAndInvalidMeasurementsAreNotRetained() {
        val cache = TextMeasurementCache(maxWeight = 200)
        cache.put("p", "b", MeasuredTextHeight(key, 80f))
        cache.put("p", "b", MeasuredTextHeight(key.copy(blocks = listOf(Block.of("x".repeat(1_000)))), 80f))
        assertEquals(0, cache.size)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach {
            cache.put("p", "b", MeasuredTextHeight(key, it))
        }
        cache.put("p", "b", MeasuredTextHeight(key.copy(editing = true), 80f))
        cache.put("p", "b", MeasuredTextHeight(key, 80f) { false })
        assertEquals(0, cache.size)
    }
}
