package com.vivenotes.ui.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import kotlin.test.*

class SelectionColorPlacementTest {
    @Test
    fun paletteOpensAboveItsButtonAndClampsAtWindowEdgesInBothDirections() {
        val position = AboveSelectionColorPosition(4)
        assertEquals(IntOffset(100, 96), position.calculatePosition(IntRect(100, 300, 132, 332),
            IntSize(800, 600), LayoutDirection.Ltr, IntSize(224, 200)))
        assertEquals(IntOffset(576, 0), position.calculatePosition(IntRect(780, 100, 812, 132),
            IntSize(800, 600), LayoutDirection.Ltr, IntSize(224, 200)))
        assertEquals(IntOffset(308, 96), position.calculatePosition(IntRect(500, 300, 532, 332),
            IntSize(800, 600), LayoutDirection.Rtl, IntSize(224, 200)))
    }

    @Test
    fun onlyTheBlackOrWhiteSwatchMatchingThePageInkIsAutomatic() {
        for ((ink, automatic, fixed) in listOf(Triple(Color(0xff1b1b1b), 0xff000000.toInt(), -1),
            Triple(Color(0xffe6e6e6), -1, 0xff000000.toInt()))) {
            assertTrue(selectionColorFollowsTheme(automatic, ink))
            for (argb in listOf(fixed, 0xffef4444.toInt(), 0x66ffffff, 0x66000000)) {
                assertFalse(selectionColorFollowsTheme(argb, ink))
            }
        }
    }
}
