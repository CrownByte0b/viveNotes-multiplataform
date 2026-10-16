package com.vivenotes.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPenPaletteTest {

    private val orange = 0xFFEF6C00.toInt()

    @Test
    fun aNewColorTakesTheFirstUnpinnedSlotAndTheTailFallsOff() {
        val rolled = PenColors.withColorInFront(orange)

        assertEquals(PenColors.take(2), rolled.take(2))
        assertEquals(orange, rolled[2])
        assertEquals(PenColors.drop(2).dropLast(1), rolled.drop(3))
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

        assertEquals(PenColors.take(2), rolled.take(2))
        assertEquals(existing, rolled[2])
        assertEquals("nothing was displaced", PenColors.size, rolled.size)
        assertEquals("and nothing was lost", PenColors.toSet(), rolled.toSet())
        assertEquals("nor duplicated", 1, rolled.count { it == existing })
    }

    @Test
    fun rePickingTheHeadChangesNothing() {
        assertEquals(PenColors, PenColors.withColorInFront(PenColors.first()))
        assertEquals(PenColors, PenColors.withColorInFront(PenColors[1]))
    }

    @Test
    fun enoughCustomColorsReplaceOnlyTheSevenUnpinnedSlots() {
        var row = PenColors
        repeat(PenColors.size) { row = row.withColorInFront(0xFF102030.toInt() + it) }

        assertEquals(PenColors.take(2), row.take(2))
        assertTrue("only the fixed swatches survive", row.drop(2).none { it in PenColors })
    }

    @Test
    fun whiteAndBlackLeadTheStartingPalette() {
        assertEquals(0xFFFFFFFF.toInt(), PenColors[0])
        assertEquals(0xFF000000.toInt(), PenColors[1])
    }

    @Test
    fun legacyMissingReorderedAndTranslucentPaletteEntriesNormalizeToNineOpaqueUniqueColors() {
        for (row in listOf(emptyList(), listOf(orange), listOf(0x00123456, 0xff123456.toInt(),
            PenColors[1], orange, PenColors[0], PenColors[1]), List(20) { 0xff102030.toInt() + it })) {
            val normalized = InkSettings(palette = row).normalized().palette
            assertEquals(PenColors.take(2), normalized.take(2))
            assertEquals(9, normalized.size)
            assertEquals(9, normalized.distinct().size)
            assertTrue(normalized.all { it ushr 24 == 255 })
            assertEquals(normalized, InkSettings(palette = normalized).normalized().palette)
        }
    }
}
