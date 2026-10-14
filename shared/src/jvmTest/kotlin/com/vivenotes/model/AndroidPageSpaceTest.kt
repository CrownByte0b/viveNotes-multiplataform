package com.vivenotes.model

import com.vivenotes.model.PageSpace.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPageSpaceTest {

    private fun down(at: Float, amount: Float) = SpaceCut(Axis.Vertical, at, amount)

    private fun right(at: Float, amount: Float) = SpaceCut(Axis.Horizontal, at, amount)

    @Test
    fun aVerticalCutMovesWhatStartsBelowIt() {
        val cut = down(at = 200f, amount = 50f)
        assertTrue(cut.moves(x = 0f, y = 260f))
        assertFalse(cut.moves(x = 0f, y = 199f))
    }

    @Test
    fun contentExactlyOnTheLineMoves() {
        assertTrue(down(at = 200f, amount = 50f).moves(x = 0f, y = 200f))
    }

    @Test
    fun anObjectStraddlingTheLineStaysPut() {
        assertFalse(down(at = 200f, amount = 50f).moves(x = 0f, y = 150f))
    }

    @Test
    fun aHorizontalCutReadsXWhereAVerticalOneReadsY() {
        val cut = right(at = 300f, amount = 40f)
        assertTrue(cut.moves(x = 320f, y = 0f))
        assertFalse(cut.moves(x = 280f, y = 900f))
        assertEquals(320f, cut.nearEdge(x = 320f, y = 0f))
    }

    @Test
    fun aVerticalCutTranslatesOnlyDown() {
        val cut = down(at = 100f, amount = 60f)
        assertEquals(0f, cut.dx)
        assertEquals(60f, cut.dy)
    }

    @Test
    fun aHorizontalCutTranslatesOnlyAcross() {
        val cut = right(at = 100f, amount = -25f)
        assertEquals(-25f, cut.dx)
        assertEquals(0f, cut.dy)
    }

    @Test
    fun aTapIsNotAGesture() {
        assertTrue(down(at = 100f, amount = 0f).isEmpty)
        assertFalse(down(at = 100f, amount = 0.5f).isEmpty)
    }

    @Test
    fun openingSpaceIsNeverLimited() {
        val cut = down(at = 100f, amount = 5000f)
        assertEquals(cut, cut.limitedTo(nearestMovedEdge = 120f))
    }

    @Test
    fun closingSpaceStopsAtTheLine() {
        val limited = down(at = 100f, amount = -200f).limitedTo(nearestMovedEdge = 160f)
        assertEquals(-60f, limited.amount)
    }

    @Test
    fun closingSpaceShorterThanTheGapIsLeftAlone() {
        val limited = down(at = 100f, amount = -20f).limitedTo(nearestMovedEdge = 160f)
        assertEquals(-20f, limited.amount)
    }

    @Test
    fun closingSpaceWithNothingBelowIsLeftAlone() {
        val limited = down(at = 100f, amount = -200f).limitedTo(nearestMovedEdge = null)
        assertEquals(-200f, limited.amount)
    }

    @Test
    fun contentClosedAgainstTheLineNeverCrossesIt() {
        val cut = down(at = 0f, amount = -400f)
        val limited = cut.limitedTo(nearestMovedEdge = 90f)
        assertEquals(-90f, limited.amount)
        assertEquals(0f, 90f + limited.dy)
    }

    @Test
    fun aClosingDragAgainstContentOnTheLineDoesNothing() {
        val limited = down(at = 240f, amount = -80f).limitedTo(nearestMovedEdge = 240f)
        assertTrue(limited.isEmpty)
    }

    @Test
    fun theLimitAppliesOnTheHorizontalAxisToo() {
        val limited = right(at = 50f, amount = -300f).limitedTo(nearestMovedEdge = 130f)
        assertEquals(-80f, limited.dx)
        assertEquals(0f, limited.dy)
    }

    @Test
    fun limitingKeepsTheLineAndTheAxis() {
        val limited = right(at = 50f, amount = -300f).limitedTo(nearestMovedEdge = 130f)
        assertEquals(Axis.Horizontal, limited.axis)
        assertEquals(50f, limited.at)
    }
}