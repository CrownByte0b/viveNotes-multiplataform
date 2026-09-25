package com.vivenotes.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceStateTest {

    @Test
    fun selectingSectionMovesSelectionToItsFirstPage() {
        val selected = WorkspaceState.demo().selectSection("chapter-2")

        assertEquals("calculus", selected.selectedNotebookId)
        assertEquals("chapter-2", selected.selectedSectionId)
        assertEquals("sequences", selected.selectedPageId)
    }

    @Test
    fun selectingPageFindsItsOwningNotebookAndSection() {
        val selected = WorkspaceState.demo().selectPage("meiosis")

        assertEquals("biology", selected.selectedNotebookId)
        assertEquals("cell-biology", selected.selectedSectionId)
        assertEquals("meiosis", selected.selectedPageId)
    }

    @Test
    fun editingPageDoesNotMutateOtherPages() {
        val initial = WorkspaceState.demo()
        val lectureBefore = initial.selectPage("lecture-notes").selectedPage

        val edited = initial.updateSelectedPage(
            title = "Finished homework",
            body = "First line\nSecond line",
        )

        assertEquals("Finished homework", edited.selectedPage?.title)
        assertEquals("First line", edited.selectedPage?.preview)
        assertEquals(lectureBefore, edited.selectPage("lecture-notes").selectedPage)
        assertNotEquals(initial.selectedPage, edited.selectedPage)
    }

    @Test
    fun addPageAppendsAndSelectsDraft() {
        val initial = WorkspaceState.demo()
        val withDraft = initial.addPage()

        assertEquals(initial.selectedSection!!.pages.size + 1, withDraft.selectedSection!!.pages.size)
        assertEquals("Untitled page", withDraft.selectedPage?.title)
        assertEquals(withDraft.selectedSection?.pages?.last()?.id, withDraft.selectedPageId)
    }
}
