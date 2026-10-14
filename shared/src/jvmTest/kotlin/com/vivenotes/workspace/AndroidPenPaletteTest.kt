package com.vivenotes.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPenPaletteTest {

    private val orange = 0xFFEF6C00.toInt()

    @Test
    fun aNewColorTakesTheFrontAndTheTailFallsOff() {
        val rolled = PenColors.withColorInFront(orange)

        assertEquals(orange, rolled.first())
        assertEquals(PenColors.dropLast(1), rolled.drop(1))
        assertTrue("the last swatch made room", PenColors.last() !in rolled)
    }

    @Test
    fun theRowNeverOutgrowsTheSpaceItHas() {
        var row = PenColors
        repeat(PenColors.size * 2) { row = row.withColorInFront(0xFF000000.toInt() or it) }

        assertEquals(PenColors.size, row.size)
    }

    @Test
    fun aColorAlreadyInTheRowMovesRatherThanRepeats() {
        val existing = PenColors.last()
        val rolled = PenColors.withColorInFront(existing)

        assertEquals(existing, rolled.first())
        assertEquals("nothing was displaced", PenColors.size, rolled.size)
        assertEquals("and nothing was lost", PenColors.toSet(), rolled.toSet())
        assertEquals("nor duplicated", 1, rolled.count { it == existing })
    }

    @Test
    fun rePickingTheHeadChangesNothing() {
        assertEquals(PenColors, PenColors.withColorInFront(PenColors.first()))
    }

    @Test
    fun enoughCustomColorsPushTheShippedPaletteOutEntirely() {
        var row = PenColors
        repeat(PenColors.size) { row = row.withColorInFront(0xFF102030.toInt() + it) }

        assertTrue("nothing shipped survives", row.none { it in PenColors })
    }

    @Test
    fun whiteAndBlackLeadTheStartingPalette() {
        assertEquals(0xFFFFFFFF.toInt(), PenColors[0])
        assertEquals(0xFF000000.toInt(), PenColors[1])
    }
}