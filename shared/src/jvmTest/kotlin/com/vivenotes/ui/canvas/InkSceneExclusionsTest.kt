package com.vivenotes.ui.canvas

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.InkProjectionKey
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.splitSelectionPage
import kotlin.test.*

class InkSceneExclusionsTest {
    @Test
    fun hugeSelectionAndGlobalExclusionsWalkEachLocalMapRatherThanTheirCrossProduct() {
        val occurrence = occurrence()
        val count = 32_768
        val rowIds = (0 until count).map { "row-$it" }
        val keys = rowIds.mapIndexed { index, row -> InkProjectionKey(row, index) }
        val rows = CountedSet(rowIds.toSet())
        val excluded = CountedSet(keys.toSet())
        val moving = CountedSet(keys.toSet())
        for (start in 0 until count step 128) {
            val localRows = rowIds.subList(start, start + 128).associateWith { listOf(occurrence) }
            val localKeys = keys.subList(start, start + 128).associateWith { occurrence }
            val result = inkSceneExclusions(rows, localRows, excluded, localKeys, moving)
            assertEquals(1, result.size)
            assertTrue(occurrence in result)
        }
        for (set in listOf(rows, excluded, moving)) {
            assertEquals(0, set.iterated, "global selections must not be enumerated again for every segment")
            assertEquals(count, set.membershipChecks, "work grows with local projections/rows, not segments × selection size")
        }
    }

    @Test
    fun smallGlobalSetsUseDirectLookupsInsteadOfScanningTheLargeBase() {
        val occurrence = occurrence()
        val rows = (0 until 10_000).associate { "row-$it" to listOf(occurrence) }
        val keys = (0 until 10_000).associate { InkProjectionKey("row-$it", it) to occurrence }
        val localRows = CountedMap(rows)
        val localKeys = CountedMap(keys)
        val result = inkSceneExclusions(setOf("row-500"), localRows, setOf(InkProjectionKey("row-600", 600)),
            localKeys, setOf(InkProjectionKey("row-700", 700)))
        assertEquals(1, result.size)
        assertEquals(1, localRows.lookups)
        assertEquals(2, localKeys.lookups)
        assertEquals(0, localRows.iterated)
        assertEquals(0, localKeys.iterated)
    }

    @Test
    fun disconnectedProjectionsStayIndependentAndWholeRowsExcludeAllTheirSurvivingPieces() {
        val page = splitSelectionPage().second
        val projections = page.desktopGeometry().projections
        assertEquals(2, projections.size)
        val scene = InkScene(projections.map { InkSceneStroke(it.stroke, it.strokeToPageTransform()) })
        val byProjection = projections.mapIndexed { index, item -> item.projectionKey to scene.strokes[index] }.toMap()
        val byRow = mapOf(projections.first().id to scene.strokes)
        val left = projections.minBy { it.pageBounds!!.left }.projectionKey
        val right = projections.maxBy { it.pageBounds!!.left }.projectionKey
        val selected = inkSceneExclusions(emptySet(), byRow, setOf(left), byProjection, emptySet())
        assertEquals(1, selected.size)
        assertTrue(byProjection.getValue(left) in selected)
        assertFalse(byProjection.getValue(right) in selected)
        val merged = inkSceneExclusions(emptySet(), byRow, setOf(left), byProjection, setOf(right))
        assertEquals(2, merged.size)
        val row = inkSceneExclusions(setOf(projections.first().id), byRow, emptySet(), byProjection, emptySet())
        assertEquals(2, row.size)
        assertTrue(scene.strokes.all { it in row })
    }

    @Test
    fun equalOccurrencesUseIdentityAndPublishedSetsAreImmutable() {
        val original = occurrence()
        val scene = InkScene(listOf(original, original))
        assertEquals(scene.strokes[0], scene.strokes[1])
        assertNotSame(scene.strokes[0], scene.strokes[1])
        val first = InkProjectionKey("row", 1)
        val second = InkProjectionKey("row", 2)
        val byProjection = mapOf(first to scene.strokes[0], second to scene.strokes[1])
        val one = inkSceneExclusions(emptySet(), emptyMap(), setOf(first), byProjection, emptySet())
        assertTrue(scene.strokes[0] in one)
        assertFalse(scene.strokes[1] in one)
        val both = inkSceneExclusions(emptySet(), emptyMap(), setOf(first), byProjection, setOf(second))
        assertEquals(2, both.size, "equal occurrences must not collapse into one exclusion")
        assertFailsWith<UnsupportedOperationException> { (both as MutableSet<InkSceneStroke>).clear() }
        assertEquals(2, both.size)
    }

    private fun occurrence(): InkSceneStroke {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f)
        return InkSceneStroke(Stroke(tool.brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0L)
            add(InputToolType.MOUSE, 20f, 50f, 10L)
        }))
    }

    private class CountedSet<T>(private val source: Set<T>) : AbstractSet<T>() {
        var iterated = 0
        var membershipChecks = 0
        override val size get() = source.size
        override fun contains(element: T): Boolean { membershipChecks++; return source.contains(element) }
        override fun iterator(): Iterator<T> {
            val iterator = source.iterator()
            return object : Iterator<T> {
                override fun hasNext() = iterator.hasNext()
                override fun next(): T { iterated++; return iterator.next() }
            }
        }
    }

    private class CountedMap<K, V>(private val source: Map<K, V>) : AbstractMap<K, V>() {
        var lookups = 0
        var iterated = 0
        override val size get() = source.size
        override fun get(key: K): V? { lookups++; return source[key] }
        override val entries: Set<Map.Entry<K, V>> get() = object : AbstractSet<Map.Entry<K, V>>() {
            override val size get() = source.size
            override fun iterator(): Iterator<Map.Entry<K, V>> {
                val iterator = source.entries.iterator()
                return object : Iterator<Map.Entry<K, V>> {
                    override fun hasNext() = iterator.hasNext()
                    override fun next(): Map.Entry<K, V> { iterated++; return iterator.next() }
                }
            }
        }
    }
}
