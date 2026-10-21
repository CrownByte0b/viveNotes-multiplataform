package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LassoPathBufferTest {
    @Test fun retainsEveryHistoricalPointAndExtendsOnePathWithoutCopyingPrefixes() {
        val buffer = LassoPathBuffer()
        val expected = List(40_000) { Offset(it.toFloat(), (it % 300).toFloat()) }
        buffer.start(expected.first())
        val retainedPath = buffer.path
        expected.drop(1).forEach(buffer::append)
        assertTrue(retainedPath === buffer.path)
        assertEquals(expected, buffer.snapshot())
        assertEquals(expected.size, buffer.revision)
        assertEquals(Rect(0f, 0f, 39_999f, 299f), buffer.path.getBounds())
        assertEquals(expected.maxOf { (it - expected.first()).getDistance() }, buffer.distanceFromStart())
        val finished = buffer.snapshot()
        buffer.clear()
        assertTrue(buffer.isEmpty())
        buffer.start(Offset(10f, 10f))
        buffer.append(Offset(20f, 30f))
        assertEquals(expected, finished)
        assertEquals(listOf(Offset(10f, 10f), Offset(20f, 30f)), buffer.snapshot())
        assertEquals(Rect(10f, 10f, 20f, 30f), buffer.path.getBounds())
    }
}
