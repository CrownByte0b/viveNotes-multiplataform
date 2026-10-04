package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.*
import kotlin.test.*

class DesktopInkReplayTest {
    @Test
    fun storedPartialEraseAndConcaveMoveKeepDisconnectedProjectionsAndOriginalBytes() {
        fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
            points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
        }
        val tool = ViveInkTool(sizeDp = 6f)
        val original = tool.complete(Stroke(tool.brush, inputs(10f to 50f, 90f to 50f)), "stroke", "page", 0, 1).row.toEntity()
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), 18f)
        val partial = ViveInkCodec.encodeErase(mask, "cut", "page", InkEraseMode.Normal, 2L, listOf("stroke"))
        val erase = InkEraseWithTargets(InkEraseEntity(partial.id, "page", EraserMode.Normal,
            partial.sizeDp, partial.points, partial.enc, partial.createdAt), listOf(InkEraseTargetEntity(partial.id, "stroke")))
        val path = listOf(com.vivenotes.byteink.kit.InkPoint(0f, 30f), com.vivenotes.byteink.kit.InkPoint(45f, 30f),
            com.vivenotes.byteink.kit.InkPoint(44f, 50f), com.vivenotes.byteink.kit.InkPoint(45f, 70f),
            com.vivenotes.byteink.kit.InkPoint(0f, 70f))
        val storedMove = ViveInkCodec.encodeMove(InkLassoMove(path, setOf("stroke"), emptySet(), 5f, 7f), "move", "page", 3L)
        val move = InkMoveWithTargets(InkMoveEntity("move", "page", 5f, 7f, points = storedMove.points,
            enc = storedMove.enc, createdAt = 3L), listOf(InkMoveTargetEntity("move", "stroke")))
        val bytes = listOf(original.points.copyOf(), partial.points.copyOf(), storedMove.points.copyOf())
        val page = InkPageReader.read("page", listOf(original), listOf(erase), listOf(move))
        val projections = page.desktopGeometry().projections
        assertEquals(2, projections.size)
        val left = projections.minBy { it.pageBounds!!.left }
        val right = projections.maxBy { it.pageBounds!!.left }
        assertEquals(5f, left.offsetX, 0.001f)
        assertEquals(7f, left.offsetY, 0.001f)
        assertEquals(0f, right.offsetX, 0.001f)
        assertEquals(0f, right.offsetY, 0.001f)
        assertContentEquals(bytes[0], original.points)
        assertContentEquals(bytes[1], erase.erase.points)
        assertContentEquals(bytes[2], move.move.points)
        assertEquals(right.pageBounds!!.right, page.geometry!!.rightDp)
        val erased = page.withDesktopEdit(com.vivenotes.data.InkEdit.EraseStrokes(setOf("stroke")))
        assertTrue(erased.desktopGeometry().projections.isEmpty(), "whole-stroke erase removes all disconnected pieces")
    }
}
