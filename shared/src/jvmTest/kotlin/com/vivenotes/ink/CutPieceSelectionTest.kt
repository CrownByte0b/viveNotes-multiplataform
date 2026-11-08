package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.EraserMode
import com.vivenotes.data.InkEdit
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Selections of cut pieces, whose meshes have no outline to test against a lasso. A lasso made from
 * the pieces' own bounds touched their ink, so replay moved nothing and grouping found nothing to
 * select again; [heldLassoPath] stands it off by Android's Insert Space margin.
 */
class CutPieceSelectionTest {
    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }

    /** Strokes "a" (y = 50) and "b" (y = 120) from x 10 to 90, both cut in two around x = 50. */
    private fun cutPage(): InkPage {
        val tool = ViveInkTool(sizeDp = 6f)
        val rows = listOf("a" to 50f, "b" to 120f).mapIndexed { index, (id, y) ->
            tool.complete(Stroke(tool.brush, inputs(10f to y, 90f to y)), id, "page", index, 1L).row.toEntity()
        }
        val stored = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(50f to 20f, 50f to 220f), 18f), "cut", "page",
            InkEraseMode.Normal, 2L, listOf("a", "b"))
        return InkPageReader.read("page", rows, emptyList(), emptyList()).withDesktopEdit(InkEdit.PartialErase(
            InkEraseEntity("cut", "page", EraserMode.Normal, stored.sizeDp, stored.points, stored.enc, 2L), setOf("a", "b")))
    }

    private fun InkPage.tops(id: String) = desktopGeometry().projections.filter { it.id == id }
        .associate { it.pageBounds!!.left to it.pageBounds!!.top }

    @Test
    fun aTappedCutPieceMovesAloneAndItsStoredLassoStandsOffTheInk() {
        val page = cutPage()
        val tapped = assertNotNull(page.geometry!!.selectAt(page, 20f, 50f)) as DesktopInkSelection
        assertEquals(setOf("a"), tapped.targetIds)
        val box = tapped.held.bounds
        assertEquals(listOf(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f), tapped.held.path.let { path ->
            listOf(path.minOf { it.x }, path.minOf { it.y }, path.maxOf { it.x }, path.maxOf { it.y }) })
        val moved = assertNotNull(tapped.move(0f, 100f))
        val before = page.tops("a")
        val after = moved.after.tops("a")
        val (left, right) = before.keys.sorted()
        assertEquals(before.getValue(left) + 100f, after.getValue(left), 0.01f, "the tapped piece moved")
        assertEquals(before.getValue(right), after.getValue(right), 0.01f, "its sibling stayed")
        assertEquals(setOf("a"), moved.selection?.targetIds, "and it is still selected")
    }

    @Test
    fun selectedCutPiecesAloneCanBeGrouped() {
        val page = cutPage()
        val loop = listOf(InkSample(0f, 30f), InkSample(45f, 30f), InkSample(45f, 140f), InkSample(0f, 140f), InkSample(0f, 31f))
        val held = assertNotNull(page.geometry!!.selectInLasso(page, loop))
        assertEquals(setOf("a", "b"), held.targetIds)
        val change = assertNotNull(held.group(), "grouping two cut pieces")
        val ids = assertNotNull(change.selection).targetIds
        assertEquals(2, ids.size)
        assertTrue(ids.none { it == "a" || it == "b" }, "the held pieces became rows of their own")
        assertEquals(1, change.after.desktopGeometry().sourceStrokes.filter { it.id in ids }.map { it.groupId }.distinct().size)
        assertEquals(listOf(1, 1), listOf("a", "b").map { id -> change.after.desktopGeometry().projections.count { it.id == id } },
            "the originals keep only their unselected pieces")
    }
}
