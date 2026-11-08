package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkEraseWithTargets
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkMoveWithTargets
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.model.ink.InkPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * The row-level meaning of each ink edit, which the browser replays. Every scenario is also held to
 * desktop: replaying the edited rows must give what desktop's incremental `withDesktopEdits` gives.
 */
class InkPageRowsTest {
    private val a = stroke("a", 10f to 50f, 90f to 50f)
    private val b = stroke("b", 10f to 120f, 90f to 120f, seq = 1)
    private val c = stroke("c", 10f to 190f, 90f to 190f, seq = 2)

    @Test
    fun addingKeepsDrawOrderAndIgnoresARowThePageAlreadyHas() {
        val rows = InkPageRows("page", listOf(a.toByteInk()))
        val added = rows.withEdits(listOf(InkEdit.AddStroke(b), InkEdit.AddStroke(a.copy(colorArgb = 1))), ::noRestore)
        assertEquals(listOf("a", "b"), added.strokes.map { it.id })
        assertSame(a.points, added.strokes.first().points, "an existing row is kept as it was")
        assertFailsWith<IllegalArgumentException> { rows.withEdits(listOf(InkEdit.AddStroke(b.copy(pageId = "other"))), ::noRestore) }
        assertDesktopReplays(listOf(a), listOf(InkEdit.AddStroke(b), InkEdit.AddStroke(a), InkEdit.AddStroke(c)))
    }

    @Test
    fun erasedStrokesComeBackInTheOrderOfThePageTheyWereErasedFrom() {
        val before = InkPageReader.read("page", listOf(a, b, c), emptyList(), emptyList())
        val beforeRows = rowsOf(listOf(a, b, c))
        val erased = beforeRows.withEdits(listOf(InkEdit.EraseStrokes(setOf("a", "b"))), ::noRestore)
        assertEquals(listOf("c"), erased.strokes.map { it.id })
        val restored = erased.withEdits(listOf(InkEdit.AddStroke(stroke("d", 10f to 260f, 90f to 260f)),
            InkEdit.RestoreStrokes(setOf("a"), before))) { source -> assertSame(before, source); beforeRows }
        assertEquals(listOf("a", "c", "d"), restored.strokes.map { it.id },
            "the source page's order first, then rows it never had")
        assertDesktopReplays(listOf(a, b, c), listOf(InkEdit.EraseStrokes(setOf("a", "b")),
            InkEdit.RestoreStrokes(setOf("a"), before)), before to beforeRows)
    }

    @Test
    fun aPartialEraseSplitsItsTargetsAndUndoingItMakesThemWholeAgain() {
        val cut = erase("cut", setOf("a", "b"), EraserMode.Normal, 5L, 50f to 20f, 50f to 150f)
        val rows = rowsOf(listOf(a, b)).withEdits(listOf(cut), ::noRestore)
        assertEquals(listOf("cut"), rows.erases.map { it.id })
        assertEquals(listOf("a", "b"), rows.erases.single().targetIds)
        assertSame(rows, rows.withEdits(listOf(cut), ::noRestore), "an active erase is not applied twice")
        val undone = rows.withEdits(listOf(cut.copy(active = false)), ::noRestore)
        assertEquals(emptyList(), undone.erases)
        assertEquals(5L, listOf(cut.copy(active = false)).latestOperationAt(1L), "undo never rewinds the operation clock")
        assertEquals(4, InkPageReader.read("page", listOf(a, b), emptyList(), emptyList()).withDesktopEdits(listOf(cut))
            .desktopGeometry().projections.size, "the cut leaves two pieces of each target")
        assertDesktopReplays(listOf(a, b), listOf(cut))
        assertDesktopReplays(listOf(a, b), listOf(cut, cut.copy(active = false)))
    }

    @Test
    fun anObjectEraseRemovesWhatItTouches() {
        val tap = erase("tap", setOf("b"), EraserMode.Object, 5L, 40f to 115f, 45f to 125f)
        assertEquals(listOf("a", "c"), InkPageReader.read("page", listOf(a, b, c), emptyList(), emptyList())
            .withDesktopEdits(listOf(tap)).desktopGeometry().projections.map { it.id }.sorted())
        assertDesktopReplays(listOf(a, b, c), listOf(erase("tap", setOf("b"), EraserMode.Object, 5L, 40f to 115f, 45f to 125f)))
    }

    @Test
    fun aMoveShiftsItsTargetsUntilItIsUndone() {
        val path = listOf(InkPoint(0f, 30f), InkPoint(100f, 30f), InkPoint(100f, 140f), InkPoint(0f, 140f))
        val shift = move("shift", setOf("a", "b"), 15f, 25f, 6L, path)
        val rows = rowsOf(listOf(a, b, c)).withEdits(listOf(shift), ::noRestore)
        assertEquals(listOf("shift"), rows.moves.map { it.id })
        assertEquals(6L, listOf(InkEdit.Batch(listOf(shift))).latestOperationAt(2L))
        val moved = InkPageReader.read("page", listOf(a, b, c), emptyList(), emptyList()).withDesktopEdits(listOf(shift))
            .desktopGeometry().projections.associate { it.id to it.pageBounds!!.top }
        assertEquals(listOf(25f, 25f, 0f), listOf("a", "b", "c").map { id ->
            moved.getValue(id) - InkPageReader.read("page", listOf(a, b, c), emptyList(), emptyList())
                .desktopGeometry().projections.single { it.id == id }.pageBounds!!.top
        }, "the lasso holds a and b only")
        assertDesktopReplays(listOf(a, b, c), listOf(shift))
        assertDesktopReplays(listOf(a, b, c), listOf(shift, shift.copy(active = false)))
    }

    @Test
    fun recolouringAndGroupingChangeOnlyTheirRows() {
        val edits = listOf(InkEdit.SetStrokeColors(mapOf("b" to StrokeColor(0xffe53935.toInt(), false))),
            InkEdit.SetStrokeGroups(mapOf("a" to "g", "c" to "g")), InkEdit.SetStrokeGroups(mapOf("c" to null)))
        val rows = rowsOf(listOf(a, b, c)).withEdits(edits, ::noRestore)
        assertEquals(listOf(a.colorArgb, 0xffe53935.toInt(), c.colorArgb), rows.strokes.map { it.colorArgb })
        assertEquals(listOf(a.colorFollowsTheme, false, c.colorFollowsTheme), rows.strokes.map { it.colorFollowsTheme })
        assertEquals(listOf("g", null, null), rows.strokes.map { it.groupId })
        assertDesktopReplays(listOf(a, b, c), edits)
    }

    @Test
    fun aBatchAppliesItsEditsInOrder() {
        val path = listOf(InkPoint(0f, 30f), InkPoint(100f, 30f), InkPoint(100f, 70f), InkPoint(0f, 70f))
        assertDesktopReplays(listOf(a, b), listOf(InkEdit.Batch(listOf(InkEdit.AddStroke(c),
            erase("cut", setOf("a", "c"), EraserMode.Normal, 5L, 50f to 20f, 50f to 220f),
            move("shift", setOf("a"), 0f, 40f, 6L, path), InkEdit.EraseStrokes(setOf("b"))))))
    }

    private fun noRestore(page: InkPage): InkPageRows = error("no restore expected for ${page.pageId}")

    private fun rowsOf(strokes: List<InkStrokeEntity>) = InkPageRows("page", strokes.map { it.toByteInk() })

    /** Desktop's incremental result against a replay of the shared row edits, piece by piece. */
    private fun assertDesktopReplays(start: List<InkStrokeEntity>, edits: List<InkEdit>,
                                     restore: Pair<InkPage, InkPageRows>? = null) {
        val desktop = InkPageReader.read("page", start, emptyList(), emptyList()).withDesktopEdits(edits)
        val rows = rowsOf(start).withEdits(edits) { source ->
            requireNotNull(restore).takeIf { it.first === source }?.second ?: error("unexpected restore source")
        }
        val replayed = InkPageReader.read("page", rows.strokes.map { it.toEntity() },
            rows.erases.map { row -> InkEraseWithTargets(InkEraseEntity(row.id, row.pageId, EraserMode.valueOf(row.mode),
                row.sizeDp, row.points, row.enc, row.createdAt, row.deletedAt), row.targetIds.map { InkEraseTargetEntity(row.id, it) }) },
            rows.moves.map { row -> InkMoveWithTargets(InkMoveEntity(row.id, row.pageId, row.dxDp, row.dyDp, row.scaleX,
                row.scaleY, row.anchorX, row.anchorY, row.points, row.enc, row.createdAt, row.deletedAt),
                row.targetIds.map { InkMoveTargetEntity(row.id, it) }) })
        assertEquals(describe(desktop), describe(replayed))
    }

    private fun describe(page: InkPage): List<String> = page.desktopGeometry().projections.map { piece ->
        val box = piece.pageBounds
        "piece ${piece.id} group=${piece.groupId} colour=${piece.stroke.brush.colorIntArgb} " +
            (box?.let { "%.2f %.2f %.2f %.2f".format(it.left, it.top, it.right, it.bottom) } ?: "empty")
    }.sorted() + page.strokes.map { "visible ${it.id} ${it.colorArgb} ${it.colorFollowsTheme}" } +
        page.operations.map { "operation ${it.id} ${it.targetIds.sorted()}" }.sorted()

    private fun stroke(id: String, vararg points: Pair<Float, Float>, seq: Int = 0): InkStrokeEntity {
        val tool = ViveInkTool(sizeDp = 6f, colorFollowsTheme = true)
        return tool.complete(Stroke(tool.brush, inputs(*points)), id, "page", seq, 1L).row.toEntity()
    }

    private fun erase(id: String, targets: Set<String>, mode: EraserMode, createdAt: Long,
                      vararg points: Pair<Float, Float>): InkEdit.PartialErase {
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(*points), 18f), id, "page",
            if (mode == EraserMode.Object) InkEraseMode.Object else InkEraseMode.Normal, createdAt, targets.toList())
        return InkEdit.PartialErase(InkEraseEntity(id, "page", mode, stored.sizeDp, stored.points, stored.enc, createdAt), targets)
    }

    private fun move(id: String, targets: Set<String>, dx: Float, dy: Float, createdAt: Long, path: List<InkPoint>): InkEdit.Move {
        val stored = ViveInkCodec.encodeMove(InkLassoMove(path, targets, emptySet(), dx, dy), id, "page", createdAt)
        return InkEdit.Move(InkMoveEntity(id, "page", dx, dy, points = stored.points, enc = stored.enc, createdAt = createdAt), targets)
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }
}
