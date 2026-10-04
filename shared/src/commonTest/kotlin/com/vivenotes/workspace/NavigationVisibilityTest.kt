package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's `hideNotebookRail` / `hideNavigation`, beside the desktop's navigation button. */
class NavigationVisibilityTest {
    private val shown = WorkspaceState.demo()

    @Test
    fun swipingTheNotebookPaneAwayLeavesThePageList() {
        val hidden = shown.hideNotebookPane()
        assertEquals(false to true, hidden.navigationVisible to hidden.pageListVisible)
    }

    @Test
    fun swipingThePageListAwayTakesBothPanesAndTheButtonBringsBothBack() {
        val hidden = shown.hideNavigation()
        assertEquals(false to false, hidden.navigationVisible to hidden.pageListVisible)
        val back = hidden.toggleNavigation()
        assertEquals(true to true, back.navigationVisible to back.pageListVisible)
    }

    @Test
    fun theButtonStillShowsAndHidesTheNotebookPaneAlone() {
        val toggled = shown.toggleNavigation()
        assertEquals(false to true, toggled.navigationVisible to toggled.pageListVisible)
        assertEquals(shown, toggled.toggleNavigation())
    }
}
