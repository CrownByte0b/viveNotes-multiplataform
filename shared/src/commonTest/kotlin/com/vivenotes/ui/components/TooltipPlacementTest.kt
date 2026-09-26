package com.vivenotes.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalMaterial3Api::class)
class TooltipPlacementTest {
    private val window = IntSize(800, 600)
    private val popup = IntSize(120, 32)

    @Test
    fun tooltipCentersOnItsButton() {
        val anchor = IntRect(300, 50, 340, 90)
        assertEquals(IntOffset(260, 94), position(TooltipAnchorPosition.Below, anchor))
        assertEquals(IntOffset(260, 14), position(TooltipAnchorPosition.Above, anchor))
    }

    @Test
    fun tooltipStaysInWindowAndFlipsAtEdges() {
        assertEquals(IntOffset(0, 44), position(TooltipAnchorPosition.Below, IntRect(0, 0, 40, 40)))
        assertEquals(IntOffset(680, 94), position(TooltipAnchorPosition.Below, IntRect(760, 50, 800, 90)))
        assertEquals(IntOffset(260, 520), position(TooltipAnchorPosition.Below, IntRect(300, 556, 340, 596)))
    }

    private fun position(preferred: TooltipAnchorPosition, anchor: IntRect): IntOffset =
        HoverTooltipPositionProvider(preferred, gap = 4).calculatePosition(
            anchor, window, LayoutDirection.Ltr, popup)
}
