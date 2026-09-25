package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.richtext.TextSelection
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

    @Test
    fun formattingTheSelectedPagePreservesOtherTextOutlines() {
        val initial = WorkspaceState.demo()
        val page = requireNotNull(initial.selectedPage)
        val extra = Outline.Text(id = "other", blocks = listOf(Block.of("keep me")))
        val withExtra = initial.copy(notebooks = initial.notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.map { candidate ->
                    if (candidate.id == page.id) candidate.copy(
                        document = candidate.document.copy(outlines = candidate.document.outlines + extra),
                    ) else candidate
                })
            })
        })

        val formatted = withExtra.selectText(TextSelection(0, 6)).toggleSelectedMark(Mark.Bold)

        assertEquals(extra, formatted.selectedPage?.document?.outlines?.last())
        assertEquals(Mark.Bold, formatted.richText?.blocks?.first()?.runs?.first()?.marks?.single())
    }
}
