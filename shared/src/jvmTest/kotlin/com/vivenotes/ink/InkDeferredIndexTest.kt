package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkPageIndex
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.performance.performanceInkAppendFixture
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkDeferredIndexTest {
    @Test
    fun ordinaryTenThousandStrokeAppendDefersOnlyTheBoundedTailAndKeepsExactFirstQuery() {
        val fixture = performanceInkAppendFixture(10_000)
        val original = fixture.before127.desktopGeometry()
        assertTrue(original.base.indexReady, "large loaded bases retain their existing preparation lifecycle")
        assertFalse(original.additions.indexReady)
        val result = fixture.before127.withDesktopEdit(fixture.append127).desktopGeometry()
        assertSame(original.base, result.base)
        assertSame(original.base.index, result.base.index)
        assertFalse(result.additions.indexReady, "ordinary append must not index a tail that might never be queried")
        assertFalse(original.additions.indexReady, "the old immutable snapshot is not mutated")
        assertEquals(127, result.additions.projections.size)
        val last = result.additions.projections.last().pageBounds!!
        val point = InkPoint((last.left + last.right) / 2f, (last.top + last.bottom) / 2f)
        val expected = InkPageIndex(result.additions.projections).at(point, 20f)
        assertEquals(expected, result.additions.index.at(point, 20f))
        assertTrue(result.additions.indexReady)
        assertSame(result.additions.index, result.additions.index)
        assertFalse(original.additions.indexReady)

        val boundary = fixture.before128.withDesktopEdit(fixture.append128).desktopGeometry()
        val sealed = boundary.retainedSegments.last()
        assertEquals(128, sealed.projections.size)
        assertFalse(sealed.indexReady, "sealed and replacement chunks keep the same bounded first-query rule")
        assertEquals(InkPageIndex(sealed.projections).at(point, 20f), sealed.index.at(point, 20f))
        assertSame(original.base, boundary.base)
        assertEquals(10_126, original.projections.size)
        assertEquals(10_127, result.projections.size)
        assertEquals(10_128, boundary.projections.size)
    }

    @Test
    fun concurrentSpatialReadersShareOnePreparedSmallSegmentIndex() {
        val fixture = performanceInkAppendFixture(1)
        val segment = fixture.before127.desktopGeometry().additions
        assertFalse(segment.indexReady)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val indexes = pool.invokeAll(List(16) { Callable { segment.index } }).map { it.get() }
            indexes.forEach { assertSame(indexes.first(), it) }
            assertTrue(segment.indexReady)
        } finally {
            pool.shutdownNow()
        }
    }
}
