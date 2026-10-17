package com.vivenotes.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationPaneWidthsTest {
    @Test
    fun fittingWidthsStayUnchangedAndAllowEitherPaneToGrow() {
        assertEquals(NavigationPaneWidths(232f, 260f, 726f, 754f),
            navigationPaneWidths(1000f, 232f, 260f, 7f))
    }

    @Test
    fun shrinkingAWindowFitsBothProportionally() {
        assertEquals(NavigationPaneWidths(200f, 300f, 200f, 300f),
            navigationPaneWidths(514f, 400f, 600f, 7f))
    }

    @Test
    fun hiddenPanelsReserveNeitherWidthNorDivider() {
        assertEquals(NavigationPaneWidths(0f, 260f, 733f, 993f),
            navigationPaneWidths(1000f, 0f, 260f, 7f))
        assertEquals(NavigationPaneWidths(0f, 0f, 1000f, 1000f),
            navigationPaneWidths(1000f, 0f, 0f, 7f))
    }

    @Test
    fun exhaustedSpaceNeverCreatesNegativeWidths() {
        assertEquals(NavigationPaneWidths(0f, 0f, 0f, 0f),
            navigationPaneWidths(10f, 232f, 260f, 7f))
    }
}
