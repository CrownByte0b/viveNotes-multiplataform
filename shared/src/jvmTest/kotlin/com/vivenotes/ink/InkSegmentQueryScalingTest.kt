package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import kotlin.random.Random
import kotlin.test.*

class InkSegmentQueryScalingTest {
    @Test
    fun longSessionPointBandMaskAndLassoQueriesPruneFarSegmentsInCanonicalOrder() {
        val shape = stroke()
        var page = DesktopInkPage(emptyList())
        // Deliberately seal 1,024 full append batches rather than loading one large native index.
        repeat(1_024) { segment ->
            page = page.withChanges(List(128) { row -> PageStroke("$segment-$row", shape,
                offsetX = segment * 200f, offsetY = row * 20f,
                groupId = "distant-group".takeIf { row == 1 && segment in setOf(1, 512, 1_023) }) }, emptySet())
        }
        assertEquals(1_024, page.retainedSegments.size)
        val x = 512 * 200f + 15f
        val expected = page.retainedSegments[512].projections.first()
        val pointWork = InkQueryWork()
        assertEquals(listOf(expected), page.index.at(InkPoint(x, 50f), 2f, pointWork))
        assertTrue(pointWork.segmentBoundsVisited < 40, "ordered bounds unions should visit logarithmically many nodes")
        assertEquals(1, pointWork.nativeIndexQueries)
        val crossingWork = InkQueryWork()
        assertEquals(listOf(expected), page.index.crossing(InkPoint(x - 5f, 50f), InkPoint(x + 5f, 50f), 2f, crossingWork))
        assertEquals(1, crossingWork.nativeIndexQueries)
        val mask = ViveBrushes.eraseMask(MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, x, 50f, 0L)
        }, 2f)
        val maskWork = InkQueryWork()
        assertEquals(listOf(expected), page.index.touching(mask, maskWork))
        assertEquals(1, maskWork.nativeIndexQueries)
        val path = listOf(InkPoint(x - 15f, 35f), InkPoint(x + 15f, 35f), InkPoint(x + 15f, 85f), InkPoint(x - 15f, 85f))
        val lassoWork = InkQueryWork()
        val selected = assertNotNull(page.index.selectWithLasso(path, lassoWork))
        val reference = assertNotNull(page.projections.selectWithLasso(path))
        assertEquals(reference.targetIds, selected.targetIds)
        assertEquals(reference.projections.toList(), selected.projections.toList(), "distant group expansion preserves original global order")
        assertEquals(1, lassoWork.nativeIndexQueries)
        assertTrue(lassoWork.segmentBoundsVisited < 40)
    }

    @Test
    fun localDeletionReclaimsRetainedGeometryAndLocationsWithoutChangingOldSnapshotsOrReaddedOrder() {
        val shape = stroke()
        val initial = DesktopInkPage(emptyList()).withChanges(List(512) { index ->
            PageStroke("row-$index", shape, offsetY = index * 20f)
        }, emptySet())
        val removed = (128 until 256).mapTo(HashSet()) { "row-$it" }
        val after = initial.withChanges(emptyList(), removed)
        assertTrue(after.retainedSegments[1].projections.isEmpty())
        assertTrue(after.retainedSegments[1].sourceIds.isEmpty())
        assertTrue(removed.all { !after.containsSource(it) })
        assertTrue(removed.all(initial::containsSource))
        assertSame(initial.retainedSegments[0], after.retainedSegments[0])
        assertSame(initial.retainedSegments[2], after.retainedSegments[2])
        assertEquals(512, initial.projections.size)
        assertEquals(384, after.projections.size)
        val replacement = PageStroke("row-200", shape, offsetX = 200f)
        val readded = after.withChanges(listOf(replacement), emptySet())
        assertEquals(replacement, readded.projections.last())
        assertEquals(listOf(replacement), readded.projections.filter { it.id == "row-200" })
        assertTrue(readded.containsSource("row-200"))
        assertFalse(after.containsSource("row-200"))
    }

    @Test
    fun repeatedTailDeleteReaddDoesNotAccumulateRowOrProjectionTombstones() {
        val shape = stroke()
        var page = DesktopInkPage(emptyList())
        val first = page.withChanges(listOf(PageStroke("same", shape)), emptySet())
        repeat(2_000) {
            page = page.withChanges(listOf(PageStroke("same", shape)), emptySet())
            page = page.withChanges(emptyList(), setOf("same"))
            assertTrue(page.excludedRows.isEmpty())
            assertTrue(page.excludedProjections.isEmpty())
            assertTrue(page.retainedSegments.isEmpty())
            assertTrue(page.sourceStrokes.isEmpty())
        }
        assertEquals(1, first.projections.size)
        assertEquals(0, page.projections.size)
    }

    @Test
    fun workerReclamationDropsEmptySlotsAndKeepsErasedAwayTailSourceMembership() {
        val shape = stroke()
        var page = DesktopInkPage(emptyList()).withChanges(List(256 * 128) { PageStroke("row-$it", shape) }, emptySet())
        page = page.withChanges(emptyList(), page.sourceStrokes.mapTo(HashSet()) { it.id })
        assertEquals(256, page.retainedSegments.emptySlots)
        assertTrue(page.needsProjectionReclamation)
        val reclaimed = page.reclaimExcludedProjections()
        assertTrue(reclaimed.retainedSegments.isEmpty())
        assertTrue(reclaimed.sourceStrokes.isEmpty())
        assertTrue(reclaimed.excludedRows.isEmpty())
        assertFalse(reclaimed.needsProjectionReclamation)

        // An erase can leave a live stored source without a projection. An append must preserve
        // that source's membership, even though no geometry is carried into the rebuilt tail.
        val initial = DesktopInkPage(emptyList()).withChanges(listOf(com.vivenotes.byteink.kit.PageStroke("erased", shape)), emptySet())
        val input = MutableStrokeInputBatch().apply { add(InputToolType.MOUSE, 15f, 50f, 0L) }
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(input, 100f), "erase", "page", InkEraseMode.Normal, 10L, listOf("erased"))
        val operation = ViveInkPage.load(emptyList(), listOf(stored), emptyList()).operations.single() as DecodedInkOperation.Erase
        val erased = initial.withNormalErase(operation)
        assertTrue(erased.projections.isEmpty())
        val appended = erased.withChanges(listOf(PageStroke("next", shape)), emptySet())
        assertTrue(appended.containsSource("erased"))
        assertEquals(listOf("next"), appended.projections.map { it.id })
        assertFalse(appended.withChanges(emptyList(), setOf("erased")).containsSource("erased"))
    }

    @Test
    fun sharedGroupAndFragmentLocationsCopyBoundedPathsAndPreserveOrderedOldSnapshots() {
        val shape = stroke()
        var store = InkSegmentStore.Empty
        val snapshots = ArrayList<Pair<InkSegmentStore, Int>>()
        var totalCopies = 0
        repeat(4_096) { index ->
            // A large selection group and a fragmented source can both occupy many chunks.
            val segment = DesktopInkSegment(listOf(PageStroke("fragment", shape,
                offsetX = index * 200f, groupId = "shared")))
            val work = InkLocationWork()
            store = store.append(listOf(segment), work)
            assertTrue(work.positionNodesCopied <= 96,
                "append $index copied ${work.positionNodesCopied} position nodes instead of a bounded tree path")
            assertTrue(work.positionNodesVisited <= 96)
            totalCopies += work.positionNodesCopied
            if (index == 127 || index == 1_023) snapshots += store to (index + 1)
        }
        assertTrue(totalCopies < 4_096 * 96,
            "flat lists previously copied 2 * (1+2+...+4096) shared-group/source position entries")
        val original = store
        val all = (0 until 4_096).toList()
        assertEquals(all, original.forRows(setOf("fragment")).map { it.first })
        assertEquals(all, original.forGroups(setOf("shared")).map { it.first })
        snapshots.forEach { (saved, count) ->
            assertEquals(count, saved.size)
            assertEquals((0 until count).toList(), saved.forRows(setOf("fragment")).map { it.first })
            assertEquals((0 until count).toList(), saved.forGroups(setOf("shared")).map { it.first })
        }

        val moved = setOf(0, 2_047, 4_095)
        for (index in moved) {
            val work = InkLocationWork()
            val replacement = DesktopInkSegment(listOf(PageStroke("fragment", shape,
                offsetX = index * 200f, groupId = "other")))
            store = store.replace(mapOf(index to replacement), work)
            assertTrue(work.positionNodesCopied <= 96)
            assertTrue(work.positionNodesVisited <= 96)
        }
        assertEquals(all, store.forRows(setOf("fragment")).map { it.first }, "changing a group retains source locations")
        assertEquals(all.filterNot { it in moved }, store.forGroups(setOf("shared")).map { it.first })
        assertEquals(moved.sorted(), store.forGroups(setOf("other")).map { it.first })
        assertEquals(all, store.forGroups(setOf("shared", "other")).map { it.first })

        val removed = setOf(1, 2_048, 4_094)
        for (index in removed) {
            val work = InkLocationWork()
            store = store.replace(mapOf(index to DesktopInkSegment(emptyList())), work)
            assertTrue(work.positionNodesCopied <= 96)
            assertTrue(work.positionNodesVisited <= 96)
        }
        assertEquals(all.filterNot { it in removed }, store.forRows(setOf("fragment")).map { it.first })
        val withHoles = store
        for (index in moved + removed) store = store.replace(mapOf(index to original[index]))
        assertEquals(all, store.forRows(setOf("fragment")).map { it.first })
        assertEquals(all, store.forGroups(setOf("shared")).map { it.first })
        assertTrue(store.forGroups(setOf("other")).isEmpty(), "last removal also removes the group's lookup key")
        assertEquals(all.filterNot { it in removed }, withHoles.forRows(setOf("fragment")).map { it.first })
        assertEquals(all, original.forGroups(setOf("shared")).map { it.first }, "old metadata roots stay immutable")
    }

    @Test
    fun mixedPositionRemovalsAndReinsertionsKeepSourceGroupAndBoundsLookupsEquivalent() {
        val shape = stroke()
        val random = Random(7301)
        fun segment(index: Int) = DesktopInkSegment(listOf(PageStroke("row-${random.nextInt(16)}", shape,
            offsetX = index * 200f, groupId = "group-${random.nextInt(8)}")))
        val reference = MutableList(128, ::segment)
        var store = InkSegmentStore.Empty.append(reference)
        val original = store
        val originalReference = reference.toList()
        repeat(800) { step ->
            val index = random.nextInt(reference.size)
            val next = if (step % 5 == 0) DesktopInkSegment(emptyList()) else segment(index)
            store = store.replace(mapOf(index to next))
            reference[index] = next
            val rows = setOf("row-${random.nextInt(16)}", "row-${random.nextInt(16)}")
            val groups = setOf("group-${random.nextInt(8)}", "group-${random.nextInt(8)}")
            assertEquals(reference.indices.filter { reference[it].sourceIds.any(rows::contains) },
                store.forRows(rows).map { it.first })
            assertEquals(reference.indices.filter { reference[it].groupIds.any(groups::contains) },
                store.forGroups(groups).map { it.first })
            rows.forEach { row -> assertEquals(reference.any { row in it.sourceIds }, store.containsSource(row)) }
            val box = InkBounds(index * 200f, 40f, index * 200f + 30f, 60f)
            assertEquals(reference.filter { it.bounds.meets(box) }, store.near(box), "replacement updates bounds in page order")
        }
        assertEquals(originalReference, original.toList())
        for (row in (0 until 16).map { "row-$it" }) {
            assertEquals(originalReference.indices.filter { row in originalReference[it].sourceIds },
                original.forRows(setOf(row)).map { it.first })
        }
    }

    private fun stroke(): Stroke {
        val tool = ViveInkTool(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f)
        return Stroke(tool.brush, MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 50f, 0L)
            add(InputToolType.MOUSE, 20f, 50f, 10L)
        })
    }
}
