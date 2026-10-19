package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

/** Navigation closing rules, including the desktop's coupled notebook/page close action. */
class NavigationVisibilityTest {
    private val shown = WorkspaceState.demo()

    @Test
    fun closingTheNotebookPaneAlsoClosesThePageList() {
        val hidden = shown.hideNotebookPane()
        assertEquals(false to false, hidden.navigationVisible to hidden.pageListVisible)
    }

    @Test
    fun swipingThePageListAwayTakesBothPanesAndTheButtonBringsBothBack() {
        val hidden = shown.hideNavigation()
        assertEquals(false to false, hidden.navigationVisible to hidden.pageListVisible)
        val back = hidden.toggleNavigation()
        assertEquals(true to true, back.navigationVisible to back.pageListVisible)
    }

    @Test
    fun theButtonClosesAndReopensBothPanes() {
        val toggled = shown.toggleNavigation()
        assertEquals(false to false, toggled.navigationVisible to toggled.pageListVisible)
        assertEquals(shown, toggled.toggleNavigation())
    }

    @Test
    fun resizingHasNoMinimumAndKeepsTheDocumentAndSelection() {
        val resized = shown.resizeNotebookPane(18.5f).resizePagePane(1f)
        assertEquals(shown.copy(notebookPaneWidth = 18.5f, pagePaneWidth = 1f), resized)
    }

    @Test
    fun collapsingNotebooksClosesBothAndRetainsTheirLastWidths() {
        val resized = shown.resizeNotebookPane(410f).resizePagePane(320f)
        for (width in listOf(0f, -200f)) {
            val closed = resized.resizeNotebookPane(width)
            assertEquals(false to false, closed.navigationVisible to closed.pageListVisible)
            assertEquals(resized, closed.toggleNavigation())
        }
    }

    @Test
    fun collapsingPagesClosesOnlyPagesAndTheButtonCanStillCloseNotebooks() {
        val resized = shown.resizePagePane(390f)
        val closed = resized.resizePagePane(0f)
        assertEquals(true to false, closed.navigationVisible to closed.pageListVisible)
        val hidden = closed.toggleNavigation()
        assertEquals(false to false, hidden.navigationVisible to hidden.pageListVisible)
        assertEquals(resized, hidden.toggleNavigation())
    }

    @Test
    fun invalidMeasurementsDoNotChangeWidthsOrVisibility() {
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(shown, shown.resizeNotebookPane(width))
            assertEquals(shown, shown.resizePagePane(width))
        }
    }

    @Test
    fun aGradualCollapseRestoresTheWidthBeforeTheDrag() {
        val partlyCollapsed = shown.resizeNotebookPane(1f).resizePagePane(1f)
        val closedNotebook = partlyCollapsed.resizeNotebookPane(0f, restoreWidth = shown.notebookPaneWidth)
        assertEquals(shown.notebookPaneWidth, closedNotebook.toggleNavigation().notebookPaneWidth)
        val closedPages = partlyCollapsed.resizePagePane(0f, restoreWidth = shown.pagePaneWidth)
        assertEquals(shown.pagePaneWidth, closedPages.toggleNavigation().toggleNavigation().pagePaneWidth)
    }

    @Test
    fun restoringAPreviouslyHiddenNotebookAlsoRestoresPages() {
        val legacyPartial = shown.copy(navigationVisible = false, pageListVisible = true)
        assertEquals(shown, legacyPartial.toggleNavigation())
    }
}
