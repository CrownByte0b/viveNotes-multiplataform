package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.model.ink.InkPage
import kotlin.test.*

class InkLocalEraseTest {
    @Test
    fun newNormalErasesKeepExactCanonicalGeometryOrderAndHistoricalSharedMoveClamps() {
        val pen = shape(ViveBrushes.MARKER, 0xff221166.toInt())
        val highlight = shape(ViveBrushes.HIGHLIGHTER, 0x66ffff00)
        val sources = listOf(PageStroke("pen", pen), PageStroke("highlight", highlight, brushFamily = ViveBrushes.HIGHLIGHTER),
            PageStroke("clamp-anchor", pen, offsetX = -8f, offsetY = 120f)) +
            List(300) { PageStroke("far-$it", pen, offsetX = 500f + it * 150f, offsetY = 500f) }
        val firstCut = erase("first", 2L, 60f, 50f, setOf("pen", "highlight"))
        val path = listOf(InkPoint(-100f, -100f), InkPoint(200f, -100f), InkPoint(200f, 300f), InkPoint(-100f, 300f))
        val move = ViveInkCodec.encodeMove(InkLassoMove(path, setOf("pen", "highlight", "clamp-anchor"), emptySet(), -50f, -20f),
            "move", "page", 3L).copy(scaleX = .75f, scaleY = 1.25f, anchorX = 0f, anchorY = 0f)
        val moved = ViveInkPage.load(emptyList(), emptyList(), listOf(move)).operations.single()
        val history = listOf(firstCut, moved)
        val before = DesktopInkPage(ViveInkPage.replay(sources, history), sources.map { it.id }, sources, history)
        var current = before
        var operations = history
        repeat(3) { index ->
            val target = current.projections.first { it.id == "pen" }.pageBounds!!
            val operation = erase("new-$index", 4L + index, target.center.x, target.center.y, setOf("pen", "highlight"), size = 3f)
            val work = InkProjectionWork()
            val after = current.withNormalErase(operation, work)
            operations = operations + operation
            assertGeometry(ViveInkPage.replay(sources, operations), after.projections)
            assertTrue(work.targetProjections < 20, "historical operations must not replay unrelated target rows")
            assertTrue(work.rebuiltProjections <= 140, "only the existing affected chunk is reindexed")
            assertEquals(1, work.rebuiltSegments)
            assertSame(before.sourceStrokes, after.sourceStrokes)
            assertTrue(after.retainedSegments.drop(1).zip(current.retainedSegments.drop(1)).all { (a, b) -> a === b } || current === before)
            current = after
        }
        assertEquals(1, before.basePartitionBuildCount)
        assertGeometry(ViveInkPage.replay(sources, history), before.projections)
    }

    @Test
    fun distinctPreviewsReuseTheInitialPartitionAndUndoAndLateOperationsUseCanonicalReplay() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(512) { PageStroke("row-$it", source, offsetY = it * 20f) }
        val initial = DesktopInkPage(sources)
        val first = erase("a", 10L, 40f, 50f, setOf("row-0"))
        val second = erase("b", 11L, 80f, 50f, setOf("row-0"))
        val a = initial.withNormalErase(first)
        val b = initial.withNormalErase(second)
        assertEquals(1, initial.basePartitionBuildCount, "distinct preview masks from the same source share one partition")
        assertSame(a.retainedSegments[1], b.retainedSegments[1])
        assertSame(a.retainedSegments[3].index, b.retainedSegments[3].index)
        val page = InkPage("page", sources.map { it.portableStroke() }, geometry = initial)
        val liveEdit = edit("live", 10L, 50f, 50f, setOf("row-0"))
        val bytes = liveEdit.row.points.copyOf()
        val applied = page.withDesktopEdit(liveEdit)
        assertGeometry(ViveInkPage.replay(sources, applied.desktopGeometry().operations), applied.desktopGeometry().projections)
        val undone = applied.withDesktopEdit(liveEdit.copy(active = false))
        assertGeometry(sources, undone.desktopGeometry().projections)
        assertEquals(0, undone.desktopGeometry().operations.size)
        val late = edit("late", 5L, 80f, 50f, setOf("row-0"))
        val withLate = applied.withDesktopEdit(late)
        assertGeometry(ViveInkPage.replay(sources, withLate.desktopGeometry().operations), withLate.desktopGeometry().projections)
        assertContentEquals(bytes, liveEdit.row.points)
        assertEquals(512, initial.projections.size)
    }

    @Test
    fun deletingFullyErasedLoadedRowsReclaimsTheirRedundantBaseMembership() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(400) { PageStroke("row-$it", source) }
        val operation = erase("erase-all", 10L, 50f, 50f,
            sources.mapTo(HashSet()) { it.id }, size = 1_000f)
        val loaded = DesktopInkPage(ViveInkPage.replay(sources, listOf(operation)),
            sources.map { it.id }, sources, listOf(operation))
        assertTrue(loaded.projections.isEmpty())
        assertEquals(400, loaded.base.sourceIds.size)
        val removed = (0 until 256).mapTo(HashSet()) { "row-$it" }

        val deleted = loaded.withChanges(emptyList(), removed)
        assertTrue(deleted.needsProjectionReclamation,
            "A source-only loaded base must also schedule membership cleanup")
        val reclaimed = deleted.reclaimExcludedProjections()

        assertTrue(reclaimed.excludedRows.isEmpty())
        assertEquals(144, reclaimed.base.sourceIds.size, "source-only membership stays in the existing base segment")
        assertTrue(reclaimed.retainedSegments.isEmpty())
        assertEquals(144, reclaimed.segments().sumOf { it.sourceIds.size })
        assertEquals(144, reclaimed.sourceStrokes.size)
        assertTrue(reclaimed.projections.isEmpty())
        assertTrue(removed.none(reclaimed::containsSource))
        assertTrue((256 until 400).all { reclaimed.containsSource("row-$it") })
        assertFalse(reclaimed.needsProjectionReclamation)
        assertEquals(0, deleted.basePartitionBuildCount)
        assertEquals(1, deleted.projectionReclamationBuildCount)
        assertEquals(400, loaded.base.sourceIds.size, "Held snapshots remain immutable")
        assertEquals(256, deleted.excludedRows.size)
    }

    @Test
    fun largeObjectCleanupKeepsOneGlobalBaseAndOnlyNormalEraseCreatesAnEditingPartition() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(40_000) { PageStroke("row-$it", source, offsetY = if (it < 256) 0f else it * 20f) }
        val initial = DesktopInkPage(sources)
        val targets = (0 until 256).mapTo(LinkedHashSet()) { "row-$it" }
        val objectErase = erase("object", 10L, 50f, 50f, targets, mode = InkEraseMode.Object)

        val cleaned = assertNotNull(initial.withObjectErase(objectErase))

        assertEquals(39_744, cleaned.base.projections.size)
        assertEquals(40_000, cleaned.base.sourceIds.size, "hidden stored rows keep their canonical membership for undo")
        assertTrue(cleaned.retainedSegments.isEmpty(), "Object cleanup must not manufacture hundreds of editing chunks")
        assertSame(initial.additions, cleaned.additions)
        assertSame(initial.sourceStrokes, cleaned.sourceStrokes)
        assertEquals(0, initial.basePartitionBuildCount)
        assertEquals(0, cleaned.basePartitionBuildCount)
        assertTrue(cleaned.excludedProjections.isEmpty())
        assertEquals(sources.drop(256), cleaned.projections)
        assertEquals(initial.rightDp.toRawBits(), cleaned.rightDp.toRawBits())
        assertEquals(initial.bottomDp.toRawBits(), cleaned.bottomDp.toRawBits())
        assertTrue(targets.all(cleaned::containsSource))
        assertEquals(40_000, initial.base.projections.size, "Object cleanup never mutates the held source snapshot")

        val normalErase = erase("normal", 11L, 50f, 256 * 20f + 50f, setOf("row-256"))
        val edited = cleaned.withNormalErase(normalErase)
        assertEquals(1, cleaned.basePartitionBuildCount, "the editing partition is created only when Normal erase needs it")
        assertTrue(edited.base.projections.isEmpty())
        assertSame(cleaned.sourceStrokes, edited.sourceStrokes)
        assertTrue(targets.all(edited::containsSource))
        assertEquals(39_744, cleaned.projections.size)
    }

    @Test
    fun cleanupReusesUnchangedChunksAndDoesNotCompactSmallNumbersOfEmptySlots() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(400) { PageStroke("base-$it", source, offsetY = it * 20f) }
        val additions = List(513) { PageStroke("added-$it", source, offsetY = 10_000f + it * 20f) }
        val initial = DesktopInkPage(sources).withChanges(additions, emptySet())
        val removed = ((0 until 256).map { "base-$it" } + (0 until 128).map { "added-$it" }).toSet()
        val deleted = initial.withChanges(emptyList(), removed)
        assertTrue(deleted.needsProjectionReclamation)
        assertEquals(1, deleted.retainedSegments.emptySlots)
        val unchangedIndex = deleted.retainedSegments[1].index

        val cleaned = deleted.reclaimExcludedProjections()

        assertEquals(144, cleaned.base.projections.size)
        assertSame(deleted.retainedSegments, cleaned.retainedSegments, "one empty slot does not rebuild the location trees")
        assertSame(unchangedIndex, cleaned.retainedSegments[1].index)
        assertSame(deleted.additions, cleaned.additions)
        assertEquals(4, cleaned.retainedSegments.size)
        assertEquals(1, cleaned.retainedSegments.emptySlots)
        assertEquals((sources + additions).filterNot { it.id in removed }, cleaned.projections)
        assertSame(deleted.sourceStrokes, cleaned.sourceStrokes)
        assertSame(deleted.operations, cleaned.operations)
        assertEquals(deleted.rightDp.toRawBits(), cleaned.rightDp.toRawBits())
        assertEquals(deleted.bottomDp.toRawBits(), cleaned.bottomDp.toRawBits())
        assertTrue(removed.none(cleaned::containsSource))
        assertTrue(cleaned.excludedRows.isEmpty())
        assertTrue(cleaned.excludedProjections.isEmpty())
        assertFalse(cleaned.needsProjectionReclamation)
        assertSame(cleaned, deleted.reclaimExcludedProjections(), "repeat preparation reuses the immutable cleanup result")
        assertEquals(1, deleted.projectionReclamationBuildCount)
        assertEquals(0, deleted.basePartitionBuildCount)
        assertEquals(400, initial.base.projections.size)
        assertEquals(0, initial.retainedSegments.emptySlots)
    }

    @Test
    fun objectCleanupPreservesUntouchedChunksAndSourceMembershipAcrossBaseSealedAndTailSegments() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(400) { PageStroke("base-$it", source) }
        val additions = List(260) { PageStroke("added-$it", source) }
        val initial = DesktopInkPage(sources).withChanges(additions, emptySet())
        val targets = ((0 until 254).map { "base-$it" } + listOf("added-0", "added-259")).toSet()
        val operation = erase("object", 10L, 50f, 50f, targets, mode = InkEraseMode.Object)

        val cleaned = assertNotNull(initial.withObjectErase(operation))

        assertEquals(146, cleaned.base.projections.size)
        assertEquals(2, cleaned.retainedSegments.size)
        assertEquals(127, cleaned.retainedSegments[0].projections.size)
        assertEquals(128, cleaned.retainedSegments[0].sourceIds.size)
        assertSame(initial.retainedSegments[1], cleaned.retainedSegments[1])
        assertEquals(3, cleaned.additions.projections.size)
        assertEquals(4, cleaned.additions.sourceIds.size)
        assertEquals(initial.rightDp.toRawBits(), cleaned.rightDp.toRawBits())
        assertEquals(initial.bottomDp.toRawBits(), cleaned.bottomDp.toRawBits())
        assertSame(initial.sourceStrokes, cleaned.sourceStrokes)
        assertTrue(targets.all(cleaned::containsSource))
        assertTrue(cleaned.excludedProjections.isEmpty())
        assertGeometry(ViveInkPage.replay(sources + additions, listOf(operation)), cleaned.projections)
        assertEquals(660, initial.projections.size)
    }

    @Test
    fun objectThresholdReclaimsHiddenProjectionIndexesButKeepsSourcesUndoAndOldSnapshots() {
        val source = shape(ViveBrushes.MARKER, 0xff000000.toInt())
        val sources = List(400) { PageStroke("row-$it", source) }
        val initial = DesktopInkPage(sources)
        val targets = (0 until 256).mapTo(LinkedHashSet()) { "row-$it" }
        val operation = erase("object", 10L, 50f, 50f, targets, mode = InkEraseMode.Object)
        val after = assertNotNull(initial.withObjectErase(operation))
        assertEquals(144, after.projections.size)
        assertEquals(144, after.segments().sumOf { it.projections.size }, "new indexes do not retain hidden projection geometry")
        assertTrue(after.excludedProjections.isEmpty())
        assertEquals(400, after.sourceStrokes.size, "canonical source rows remain available for undo")
        assertEquals(400, initial.projections.size)
        assertTrue(initial.segments().any { it.projections.any { it.id in targets } })
        assertTrue(targets.all(after::containsSource), "object erasure does not tombstone source rows")
        assertGeometry(ViveInkPage.replay(sources, listOf(operation)), after.projections)
        val page = InkPage("page", sources.map { it.portableStroke() }, listOfNotNull(operation.portableOperation()), after)
        val stored = edit("object", 10L, 50f, 50f, targets, mode = EraserMode.Object)
        assertGeometry(sources, page.withDesktopEdit(stored.copy(active = false)).desktopGeometry().projections)
    }

    private fun shape(family: String, color: Int): Stroke {
        val tool = ViveInkTool(family, 0, color, 6f)
        return Stroke(tool.brush, inputs(10f, 50f, 110f, 50f))
    }

    private fun erase(id: String, at: Long, x: Float, y: Float, targets: Set<String>, size: Float = 12f,
        mode: InkEraseMode = InkEraseMode.Normal): DecodedInkOperation.Erase {
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(x, y - 20f, x, y + 20f), size),
            id, "page", mode, at, targets.toList())
        return ViveInkPage.load(emptyList(), listOf(stored), emptyList()).operations.single() as DecodedInkOperation.Erase
    }

    private fun edit(id: String, at: Long, x: Float, y: Float, targets: Set<String>, mode: EraserMode = EraserMode.Normal): InkEdit.PartialErase {
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(x, y - 20f, x, y + 20f), 12f), id, "page",
            if (mode == EraserMode.Normal) InkEraseMode.Normal else InkEraseMode.Object, at, targets.toList())
        return InkEdit.PartialErase(InkEraseEntity(id, "page", mode, stored.sizeDp, stored.points, stored.enc, at), targets)
    }

    private fun inputs(x1: Float, y1: Float, x2: Float, y2: Float) = MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, x1, y1, 0L); add(InputToolType.MOUSE, x2, y2, 50L)
    }

    private fun assertGeometry(expected: List<PageStroke>, actual: List<PageStroke>) {
        assertEquals(expected.map { it.id }, actual.map { it.id }, "row and fragment drawing order")
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a.pageBounds, b.pageBounds)
            assertEquals(a.offsetX.toRawBits(), b.offsetX.toRawBits())
            assertEquals(a.offsetY.toRawBits(), b.offsetY.toRawBits())
            assertEquals(a.scaleX.toRawBits(), b.scaleX.toRawBits())
            assertEquals(a.scaleY.toRawBits(), b.scaleY.toRawBits())
            assertEquals(a.stroke.shape.getRenderGroupCount(), b.stroke.shape.getRenderGroupCount())
            repeat(a.stroke.shape.getRenderGroupCount()) { group ->
                val am = InkMeshes.triangles(a.stroke.shape, group)
                val bm = InkMeshes.triangles(b.stroke.shape, group)
                assertEquals(am.size, bm.size)
                am.zip(bm).forEach { (x, y) ->
                    assertContentEquals(x.positions, y.positions)
                    assertContentEquals(x.triangles, y.triangles)
                }
                val ao = InkMeshes.outlines(a.stroke.shape, group)
                val bo = InkMeshes.outlines(b.stroke.shape, group)
                assertEquals(ao.size, bo.size)
                ao.zip(bo).forEach { (x, y) -> assertContentEquals(x, y) }
            }
        }
    }
}
