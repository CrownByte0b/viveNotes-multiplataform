package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.InkEdit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class InkSegmentRetentionTest {
    @Test
    fun appendForestNeverTraversesOrCopiesTheLargeBaseAtChunkBoundaries() {
        var reads = 0
        val base = object : AbstractList<Int>() {
            override val size = 40_000
            override fun get(index: Int): Int { reads++; return index }
        }
        var values: List<Int> = base
        val snapshots = ArrayList<List<Int>>()
        repeat(1_025) { index ->
            values = appendInkValues(values, listOf(40_000 + index))
            if (index in setOf(126, 127, 254, 255, 1_023)) snapshots += values
        }
        assertEquals(0, reads, "persistent appends must not enumerate the finished source list")
        assertEquals(39_999, values[39_999])
        assertEquals(41_024, values.last())
        assertEquals(listOf(40_127, 40_128, 40_255, 40_256, 41_024), snapshots.map { it.size })
        assertEquals((0 until 41_025).toList(), values.toList())
    }

    @Test
    fun multipleSealedSegmentsKeepBaseIndexOldSnapshotsAndGlobalSelectionOrder() {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff112233.toInt(), 3f)
        fun edit(index: Int): InkEdit.AddStroke {
            val x = index % 20 * 20f
            val y = index / 20 * 20f
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, x, y, 0L)
                add(InputToolType.MOUSE, x + 10f, y + 5f, 10L)
            }
            return tool.complete(Stroke(tool.brush, inputs), "row-$index", "page", index, 1L).desktopEdit()
        }
        val first = edit(0)
        val initial = InkPageReader.read("page", listOf(first.row), emptyList(), emptyList())
        var page = initial
        val saved = ArrayList<com.vivenotes.model.ink.InkPage>()
        val edits = List(300) { edit(it + 1) }
        edits.forEachIndexed { index, edit ->
            page = page.withDesktopEdit(edit)
            assertSame(initial.desktopGeometry().base.index, page.desktopGeometry().base.index)
            if (index in setOf(126, 127, 254, 255)) {
                saved += page
                val native = page.desktopGeometry()
                val exact = native.projections.mapNotNull { it.pageBounds }.unionBounds()!!
                assertEquals(exact.right.toRawBits(), native.rightDp.toRawBits(), "exact append right extent at $index")
                assertEquals(exact.bottom.toRawBits(), native.bottomDp.toRawBits(), "exact append bottom extent at $index")
            }
        }
        assertEquals(listOf(128, 129, 256, 257), saved.map { it.strokes.size })
        assertEquals(listOf(0, 1, 1, 2), saved.map { it.desktopGeometry().retainedSegments.size })
        assertEquals(2, page.desktopGeometry().retainedSegments.size)
        val expected = (listOf(first) + edits).map { it.row.id }
        assertEquals(expected, page.desktopGeometry().projections.map { it.id })
        val path = listOf(InkPoint(-20f, -20f), InkPoint(450f, -20f), InkPoint(450f, 400f), InkPoint(-20f, 400f))
        val reference = page.desktopGeometry().projections.selectWithLasso(path)!!
        val indexed = page.desktopGeometry().index.selectWithLasso(path)!!
        assertEquals(reference.targetIds, indexed.targetIds)
        assertEquals(reference.projections.toList(), indexed.projections.toList())
        val erased = page.withDesktopEdit(InkEdit.EraseStrokes(setOf("row-5", "row-129", "row-260")))
        assertSame(initial.desktopGeometry().base, erased.desktopGeometry().base)
        assertEquals(expected.filterNot { it in setOf("row-5", "row-129", "row-260") },
            erased.desktopGeometry().projections.map { it.id })
        assertEquals(301, page.desktopGeometry().projections.size)
    }

    @Test
    fun indexedLassoExpandsGroupsAcrossSegmentsButDoesNotReselectErasedMembers() {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f)
        fun row(index: Int): com.vivenotes.data.db.InkStrokeEntity {
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 10f + index * 20f, 50f, 0L)
                add(InputToolType.MOUSE, 20f + index * 20f, 50f, 10L)
            }
            return tool.complete(Stroke(tool.brush, inputs), "row-$index", "page", index, 1L).row.toEntity()
                .copy(groupId = "group".takeIf { index in setOf(0, 128, 257) })
        }
        val initial = InkPageReader.read("page", listOf(row(0)), emptyList(), emptyList())
        val page = initial.withDesktopEdits((1..257).map { InkEdit.AddStroke(row(it)) })
        assertEquals(listOf(128, 128), page.desktopGeometry().retainedSegments.map { it.projections.size })
        assertEquals(listOf("row-257"), page.desktopGeometry().additions.projections.map { it.id })
        val path = listOf(InkPoint(0f, 35f), InkPoint(30f, 35f), InkPoint(30f, 65f), InkPoint(0f, 65f))
        val actual = page.desktopGeometry().index.selectWithLasso(path)!!
        val reference = page.desktopGeometry().projections.selectWithLasso(path)!!
        assertEquals(setOf("row-0", "row-128", "row-257"), actual.targetIds)
        assertEquals(reference.projections.toList(), actual.projections.toList())
        val erased = page.withDesktopEdit(InkEdit.EraseStrokes(setOf("row-0")))
        assertNull(erased.desktopGeometry().index.selectWithLasso(path), "an excluded hit cannot widen to its live group")
        assertNull(erased.geometry!!.selectAt(erased, 15f, 50f, 2f))
        assertEquals(258, page.desktopGeometry().projections.size)
        assertEquals(257, erased.desktopGeometry().projections.size)
    }

    @Test
    fun erasePreviewAndCommitShareOneImmutablePreparedResultAndRebaseOnlyOnNewSnapshots() = runBlocking {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 6f)
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0L)
            add(InputToolType.MOUSE, 110f, 50f, 100L)
        }
        val row = tool.complete(Stroke(tool.brush, inputs), "row", "page", 0, 1L).row.toEntity()
        val page = InkPageReader.read("page", listOf(row), emptyList(), emptyList())
        val gesture = DesktopPartialEraseGesture(page, 1f, 12f, now = 10L)
        gesture.observe(InkPointerSample(50f, 30f, 0L, InputToolType.MOUSE))
        gesture.observe(InkPointerSample(50f, 70f, 50L, InputToolType.MOUSE))
        val request = gesture.request()
        assertSame(request, gesture.request(), "release must retain the last accepted observation snapshot")
        val lane = DesktopErasePreparer()
        val results = List(8) { async { lane.prepare(request) } }.awaitAll()
        val first = assertNotNull(results.first())
        results.forEach { assertSame(first, it) }
        assertEquals(1, request.preparationCount)
        assertEquals(setOf("row"), first.first.targetIds)
        assertSame(page, request.page)
        assertSame(request, request.onPage(page))
        val rebase = request.onPage(first.second)
        assertNotSame(request, rebase)
        assertSame(rebase, request.onPage(first.second))
        assertSame(first.second, rebase.page)
        assertEquals(0, rebase.preparationCount)
    }
}
