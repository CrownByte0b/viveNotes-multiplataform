package com.vivenotes.ink

import com.vivenotes.byteink.portable.InkSample
import com.vivenotes.byteink.portable.InkTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.model.PageSpace
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkPageOperation
import com.vivenotes.model.ink.InkSample as PageSample
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Erasing and selecting the browser's ink over the real engine, by desktop's rules. */
class WebInkSelectionTest {
    /** Three 6 dp marker strokes, 10..90 wide, on y = 50, 120 and 190. */
    private suspend fun threeStrokes(): Pair<WebInkPages, InkPage> {
        val pages = loadedWebInk()
        return pages to pages.withEdits(InkPage("page", emptyList()), listOf("a", "b", "c").mapIndexed { index, id ->
            InkEdit.AddStroke(pages.strokeRow(id, 10f to 50f + index * 70f, 90f to 50f + index * 70f, seq = index))
        })
    }

    private fun WebInkPages.cutInTwo(page: InkPage, vararg targets: String): InkPage =
        withEdits(page, listOf(eraseEdit("cut", targets.toSet(), EraserMode.Normal, page.latestOperationAt + 1, 50f to 20f, 50f to 220f)))

    private fun closedLoop(left: Float, top: Float, right: Float, bottom: Float) = listOf(PageSample(left, top),
        PageSample(right, top), PageSample(right, bottom), PageSample(left, bottom), PageSample(left, top + 1f))

    private fun WebInkPages.topOf(page: InkPage, id: String): List<Float> =
        pieces(page).filter { it.id == id }.map { it.bounds!!.top }

    @Test
    fun aTapSelectsTheTopmostInkAndTheWholeGroup() = runTest {
        val (pages, page) = threeStrokes()
        val geometry = page.geometry!!
        assertEquals(setOf("b"), geometry.selectAt(page, 40f, 121f)?.targetIds)
        assertNull(geometry.selectAt(page, 40f, 85f), "nothing within reach between strokes")
        val grouped = pages.withEdits(page, listOf(InkEdit.SetStrokeGroups(mapOf("a" to "g", "c" to "g"))))
        val selection = assertNotNull(grouped.geometry!!.selectAt(grouped, 40f, 51f))
        assertEquals(setOf("a", "c"), selection.targetIds)
        assertTrue(selection.isGrouped)
        assertTrue(selection.bounds.top < 50f && selection.bounds.bottom > 190f)
    }

    @Test
    fun aLassoTakesOnlyTheInkItEnclosesAndMustClose() = runTest {
        val (_, page) = threeStrokes()
        val geometry = page.geometry!!
        assertEquals(setOf("a", "b"), geometry.selectInLasso(page, closedLoop(0f, 30f, 100f, 140f))?.targetIds)
        assertNull(geometry.selectInLasso(page, closedLoop(0f, 30f, 100f, 140f).dropLast(2) + PageSample(100f, 200f)),
            "an open gesture selects nothing")
        assertEquals(setOf("b", "c"), geometry.selectInRect(page, 0f, 100f, 100f, 210f)?.targetIds)
        assertEquals(setOf("c"), geometry.selectInRect(page, 0f, 170f, 100f, 210f)?.targetIds)
        assertNull(geometry.selectInRect(page, 30f, 30f, 60f, 70f), "a rectangle across part of a stroke takes nothing")
    }

    @Test
    fun movingShiftsTheHeldInkReselectsItAndStopsAtThePageEdge() = runTest {
        val (pages, page) = threeStrokes()
        val selection = assertIs<WebInkSelection>(page.geometry!!.selectAt(page, 40f, 51f))
        val change = assertNotNull(selection.move(15f, 25f))
        assertEquals(page, change.before)
        assertEquals(25f, pages.topOf(change.after, "a").single() - pages.topOf(page, "a").single(), 0.01f)
        assertEquals(pages.topOf(page, "b"), pages.topOf(change.after, "b"))
        assertEquals(setOf("a"), change.selection?.targetIds)
        assertEquals(selection.bounds.top + 25f, change.selection!!.bounds.top, 0.01f)
        val undone = pages.withEdits(change.after, change.undo)
        assertEquals(pages.topOf(page, "a"), pages.topOf(undone, "a"))
        val clamped = assertNotNull(selection.move(-1000f, 0f))
        assertEquals(-selection.bounds.left, clamped.dx, 0.01f, "ink stops at the page's left edge")
    }

    @Test
    fun deletingWholeRowsTombstonesThemAndAHeldPieceIsErasedAlone() = runTest {
        val (pages, page) = threeStrokes()
        val whole = assertNotNull(page.geometry!!.selectInRect(page, 0f, 30f, 100f, 140f)!!.delete())
        assertEquals(listOf(InkEdit.EraseStrokes(setOf("a", "b"))), whole.edits)
        assertEquals(listOf("c"), whole.after.strokes.map { it.id })
        assertEquals(listOf("a", "b", "c"), pages.withEdits(whole.after, whole.undo).strokes.map { it.id })

        val cut = pages.cutInTwo(page, "a")
        assertEquals(2, pages.pieces(cut).count { it.id == "a" })
        val left = assertNotNull(cut.geometry!!.selectInLasso(cut, closedLoop(0f, 30f, 45f, 70f)))
        assertEquals(setOf("a"), left.targetIds)
        val removed = assertNotNull(left.delete())
        val erase = assertIs<InkEdit.PartialErase>(removed.edits.single())
        assertEquals(EraserMode.Object, erase.row.mode)
        val remaining = pages.pieces(removed.after).filter { it.id == "a" }
        assertEquals(1, remaining.size, "only the held piece goes")
        assertTrue(remaining.single().bounds!!.left > 50f, "the right-hand piece stays")
        assertEquals(2, pages.pieces(pages.withEdits(removed.after, removed.undo)).count { it.id == "a" })
    }

    @Test
    fun aTappedCutPieceMovesAloneAndItsStoredLassoStandsOffTheInk() = runTest {
        val (pages, page) = threeStrokes()
        val cut = pages.cutInTwo(page, "a")
        val tapped = assertIs<WebInkSelection>(cut.geometry!!.selectAt(cut, 20f, 50f))
        assertEquals(setOf("a"), tapped.targetIds)
        val box = tapped.held.bounds
        assertEquals(listOf(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f), tapped.held.path.let { path ->
            listOf(path.minOf { it.x }, path.minOf { it.y }, path.maxOf { it.x }, path.maxOf { it.y }) })
        val moved = assertNotNull(tapped.move(0f, 100f))
        fun tops(of: InkPage) = pages.pieces(of).filter { it.id == "a" }.associate { it.bounds!!.left to it.bounds!!.top }
        val before = tops(cut)
        val after = tops(moved.after)
        val (left, right) = before.keys.sorted()
        assertEquals(before.getValue(left) + 100f, after.getValue(left), 0.01f, "the tapped piece moved")
        assertEquals(before.getValue(right), after.getValue(right), 0.01f, "its sibling stayed")
        assertEquals(setOf("a"), moved.selection?.targetIds)
    }

    @Test
    fun recolouringAndGroupingChangeTheSelectedRows() = runTest {
        val (pages, page) = threeStrokes()
        val selection = assertNotNull(page.geometry!!.selectInRect(page, 0f, 30f, 100f, 140f))
        val recoloured = assertNotNull(selection.recolor(0xffe53935.toInt(), false))
        assertEquals(listOf(0xffe53935.toInt(), 0xffe53935.toInt(), 0xff202020.toInt()), recoloured.after.strokes.map { it.colorArgb })
        assertEquals(setOf("a", "b"), recoloured.selection?.targetIds)
        assertNull(recoloured.selection!!.recolor(0xffe53935.toInt(), false), "the same colour is no change")

        val grouped = assertNotNull(selection.group())
        val groups = pages.rows(grouped.after).strokes.associate { it.id to it.groupId }
        assertNotNull(groups["a"])
        assertEquals(groups["a"], groups["b"])
        assertNull(groups["c"])
        val groupedSelection = assertNotNull(grouped.selection)
        assertTrue(groupedSelection.isGrouped)
        val ungrouped = assertNotNull(groupedSelection.ungroup())
        assertEquals(listOf(null, null, null), pages.rows(ungrouped.after).strokes.map { it.groupId })
    }

    @Test
    fun groupingACutPieceGivesItARowOfItsOwnFirst() = runTest {
        val (pages, page) = threeStrokes()
        val cut = pages.cutInTwo(page, "a", "b")
        val held = assertNotNull(cut.geometry!!.selectInLasso(cut, closedLoop(0f, 30f, 45f, 140f)))
        assertEquals(setOf("a", "b"), held.targetIds)
        val change = assertNotNull(held.group())
        val ids = change.selection!!.targetIds
        assertTrue(ids.none { it == "a" || it == "b" }, "the held pieces became new rows: $ids")
        val rows = pages.rows(change.after).strokes.associateBy { it.id }
        assertEquals(1, ids.map { rows.getValue(it).groupId }.distinct().size)
        assertEquals(listOf(1, 1), listOf("a", "b").map { id -> pages.pieces(change.after).count { it.id == id } },
            "the originals keep only their unselected pieces")
    }

    @Test
    fun pastingCopiesOnlyTheHeldPieceAtItsOffset() = runTest {
        val (pages, page) = threeStrokes()
        val cut = pages.cutInTwo(page, "a")
        val clipboard = assertNotNull(cut.geometry!!.selectInLasso(cut, closedLoop(0f, 30f, 45f, 70f))).copy()!!
        val pasted = assertNotNull(clipboard.paste(cut, 0f, 300f))
        val copies = pasted.selection!!.targetIds
        assertEquals(1, copies.size)
        val pieces = pages.pieces(pasted.after).filter { it.id in copies }
        assertEquals(1, pieces.size, "the unheld piece of the copy is erased")
        assertTrue(pieces.single().bounds!!.top > 300f && pieces.single().bounds!!.right < 50f)
        assertEquals(2, pages.pieces(pasted.after).count { it.id == "a" }, "the original is untouched")
        assertEquals(pages.rows(cut).strokes.map { it.id }, pages.withEdits(pasted.after, pasted.undo).strokes.map { it.id })
    }

    @Test
    fun theEraserCutsOrRemovesWhatItTouchesAndPreviewsIt() = runTest {
        val (pages, page) = threeStrokes()
        val across = listOf(50f to 20f, 50f to 150f).mapIndexed { index, (x, y) -> InkSample(x, y, index * 10L, InkTool.MOUSE) }
        val (cut, normal) = assertNotNull(pages.erase(page, across, 18f, EraserMode.Normal, 0L))
        assertEquals(setOf("a", "b"), cut.targetIds)
        assertEquals(page.latestOperationAt + 1, cut.row.createdAt)
        assertEquals(listOf(2, 2, 1), listOf("a", "b", "c").map { id -> pages.pieces(normal).count { it.id == id } })
        assertIs<InkPageOperation.Erase>(normal.operations.single())

        val (_, objectErased) = assertNotNull(pages.erase(page, across, 18f, EraserMode.Object, 0L))
        assertEquals(listOf("c"), pages.pieces(objectErased).map { it.id })
        assertNull(pages.erase(page, listOf(InkSample(300f, 300f, 0L, InkTool.MOUSE)), 18f, EraserMode.Normal, 0L))

        val hidden = pages.erasePreview(pages.rows(page), across, 18f, EraserMode.Object)
        assertEquals(setOf("a", "b"), hidden.hidden.map { it.strokeId }.toSet())
        assertTrue(hidden.cut.isEmpty())
        val cutPreview = pages.erasePreview(pages.rows(page), across, 18f, EraserMode.Normal)
        assertEquals(hidden.hidden, cutPreview.cut.keys)
        cutPreview.cut.values.forEach { stroke ->
            assertNotEquals(null, stroke.bounds)
            stroke.close()
        }
    }

    @Test
    fun insertSpaceTakesWholeUnitsPastTheLine() = runTest {
        val (pages, page) = threeStrokes()
        assertEquals(setOf("b", "c"), page.geometry!!.selectPastLine(page, SpaceCut(PageSpace.Axis.Vertical, 100f, 40f))?.targetIds)
        val grouped = pages.withEdits(page, listOf(InkEdit.SetStrokeGroups(mapOf("a" to "g", "c" to "g"))))
        assertEquals(setOf("b"), grouped.geometry!!.selectPastLine(grouped, SpaceCut(PageSpace.Axis.Vertical, 100f, 40f))?.targetIds,
            "a group moves only when all of it is past the line")
    }
}
