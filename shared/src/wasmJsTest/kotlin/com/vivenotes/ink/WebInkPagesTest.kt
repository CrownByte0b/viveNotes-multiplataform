package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.VisibleInkStroke
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** The browser's pages over the real Ink engine: rows in, replayed pieces and the common page out. */
class WebInkPagesTest {
    @Test
    fun addedStrokesAndAPartialEraseReplayAsTheCommonPage() = runTest {
        val pages = loadedWebInk()
        val a = pages.strokeRow("a", 10f to 50f, 90f to 50f)
        val b = pages.strokeRow("b", 10f to 120f, 90f to 120f, seq = 1)
        val drawn = pages.withEdits(InkPage("page", emptyList()), listOf(InkEdit.AddStroke(a), InkEdit.AddStroke(b)))
        assertEquals(listOf("a", "b"), drawn.strokes.map { it.id })
        val geometry = assertIs<WebInkGeometry>(drawn.geometry)
        assertTrue(geometry.rightDp in 92f..94f && geometry.bottomDp in 122f..124f, "${geometry.rightDp} ${geometry.bottomDp}")
        assertEquals(2, pages.replay(geometry.rows).strokes.size)

        val cut = pages.eraseEdit("cut", setOf("a", "b"), EraserMode.Normal, 5L, 50f to 20f, 50f to 150f)
        val erased = pages.withEdits(drawn, listOf(cut))
        assertEquals(listOf("a", "b"), erased.strokes.map { it.id }, "a partial erase keeps both rows")
        assertEquals(4, pages.replay((erased.geometry as WebInkGeometry).rows).strokes.size, "each row is cut in two")
        val operation = assertIs<InkPageOperation.Erase>(erased.operations.single())
        assertEquals(setOf("a", "b"), operation.targetIds)
        assertEquals(false, operation.objectMode)
        assertEquals(5L, erased.latestOperationAt)

        val undone = pages.withEdits(erased, listOf(cut.copy(active = false)))
        assertEquals(2, pages.replay((undone.geometry as WebInkGeometry).rows).strokes.size)
        assertEquals(emptyList(), undone.operations)
        assertEquals(5L, undone.latestOperationAt, "undo never rewinds the operation clock")
    }

    @Test
    fun anObjectEraseRemovesOnlyTheStrokeItTouches() = runTest {
        val pages = loadedWebInk()
        val page = pages.withEdits(InkPage("page", emptyList()), listOf(
            InkEdit.AddStroke(pages.strokeRow("a", 10f to 50f, 90f to 50f)),
            InkEdit.AddStroke(pages.strokeRow("b", 10f to 120f, 90f to 120f, seq = 1))))
        val tapped = pages.withEdits(page, listOf(pages.eraseEdit("tap", setOf("b"), EraserMode.Object, 3L, 40f to 115f, 45f to 125f)))
        assertEquals(listOf("a"), pages.replay((tapped.geometry as WebInkGeometry).rows).strokes.map { it.id })
        assertEquals(true, (tapped.operations.single() as InkPageOperation.Erase).objectMode)
    }

    @Test
    fun movesColoursAndGroupsFollowTheirRows() = runTest {
        val pages = loadedWebInk()
        val page = pages.withEdits(InkPage("page", emptyList()), listOf(
            InkEdit.AddStroke(pages.strokeRow("a", 10f to 50f, 90f to 50f)),
            InkEdit.AddStroke(pages.strokeRow("b", 10f to 120f, 90f to 120f, seq = 1))))
        val path = listOf(InkPoint(0f, 30f), InkPoint(100f, 30f), InkPoint(100f, 70f), InkPoint(0f, 70f))
        val edited = pages.withEdits(page, listOf(pages.moveEdit("shift", setOf("a", "b"), 15f, 25f, 6L, path),
            InkEdit.SetStrokeColors(mapOf("b" to StrokeColor(0xffe53935.toInt(), false))),
            InkEdit.SetStrokeGroups(mapOf("a" to "g"))))
        fun tops(page: InkPage) = pages.replay((page.geometry as WebInkGeometry).rows).strokes.associate { it.id to it.pageBounds!!.top }
        val before = tops(page)
        val after = tops(edited)
        assertEquals(25f, after.getValue("a") - before.getValue("a"), 0.01f)
        assertEquals(0f, after.getValue("b") - before.getValue("b"), 0.01f, "b lies outside the lasso")
        assertIs<InkPageOperation.Move>(edited.operations.single())
        assertEquals(listOf(0xff202020.toInt(), 0xffe53935.toInt()), edited.strokes.map { it.colorArgb })
        val rows = assertIs<WebInkGeometry>(edited.geometry).rows
        assertEquals(listOf("g", null), rows.strokes.map { it.groupId })
        assertEquals(listOf("g", null), pages.replay(rows).strokes.map { it.groupId })
    }

    @Test
    fun restoredStrokesReturnInTheOrderOfThePageTheyLeft() = runTest {
        val pages = loadedWebInk()
        val before = pages.withEdits(InkPage("page", emptyList()), listOf("a", "b", "c").mapIndexed { index, id ->
            InkEdit.AddStroke(pages.strokeRow(id, 10f to 50f + index * 60f, 90f to 50f + index * 60f, seq = index))
        })
        val erased = pages.withEdits(before, listOf(InkEdit.EraseStrokes(setOf("a", "b"))))
        assertEquals(listOf("c"), erased.strokes.map { it.id })
        assertEquals(listOf("a", "c"), pages.withEdits(erased, listOf(InkEdit.RestoreStrokes(setOf("a"), before))).strokes.map { it.id })
    }

    @Test
    fun onlyTheMostRecentReplaysKeepEngineMemory() = runTest {
        val shared = loadedWebInk()
        val pages = WebInkPages(shared.engine, capacity = 2)
        val rows = (0..2).map { index -> InkPageRows("page", listOf(shared.strokeRow("s$index", 10f to 50f, 90f to 50f).toByteInk())) }
        val first = pages.replay(rows[0])
        pages.replay(rows[1])
        assertTrue(first.strokes.single().stroke.bounds != null, "still open with one other replay since")
        pages.replay(rows[2])
        assertFailsWith<IllegalStateException>("the oldest replay was closed") { first.strokes.single().stroke.bounds }
        val again = pages.replay(rows[0])
        assertNotSame(first, again)
        assertEquals("s0", again.strokes.single().id)
    }

    @Test
    fun aPageFromElsewhereIsEncodedFromItsDecodedStrokes() = runTest {
        val pages = loadedWebInk()
        val page = InkPage("page", listOf(VisibleInkStroke("loose", "pressure-pen", 1, 4f, 0xff336699.toInt(), true,
            listOf(InkSample(10f, 10f), InkSample(60f, 40f), InkSample(110f, 20f)), stabilization = 2)))
        val rows = pages.rows(page)
        assertEquals(listOf("loose"), rows.strokes.map { it.id })
        assertEquals(listOf<Any?>("pressure-pen", 4f, 0xff336699.toInt(), true, 2), rows.strokes.single().let {
            listOf(it.brushFamily, it.sizeDp, it.colorArgb, it.colorFollowsTheme, it.stabilization)
        })
        val replayed = pages.page(rows)
        assertEquals(listOf("loose"), replayed.strokes.map { it.id })
        assertEquals(3, replayed.strokes.single().samples.size)
    }
}
