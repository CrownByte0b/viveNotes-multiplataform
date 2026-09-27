package com.vivenotes.workspace

import com.vivenotes.model.Outline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Renaming and deleting notebooks, sections and pages from the navigation menus. */
class NavigationEditsTest {

    private val demo = WorkspaceState.demo()

    @Test
    fun theWorkspaceOpensOnTheDocumentTab() {
        assertEquals(RibbonTab.Document, demo.activeTab)
        assertEquals(RibbonTab.Document, WorkspaceState(emptyList(), "", "", "").activeTab)
    }

    @Test
    fun renamingTrimsTheNameAndABlankNameChangesNothing() {
        val renamed = demo
            .rename(NavigationItem.Notebook("calculus"), "  Analysis ")
            .rename(NavigationItem.Section("chapter-2"), "Series")
            .rename(NavigationItem.Page("sequences"), "Limits of sequences")

        assertEquals("Analysis", renamed.nameOf(NavigationItem.Notebook("calculus")))
        assertEquals("Series", renamed.nameOf(NavigationItem.Section("chapter-2")))
        assertEquals("Limits of sequences", renamed.nameOf(NavigationItem.Page("sequences")))
        assertSame(demo, demo.rename(NavigationItem.Section("chapter-1"), "   "))
        assertSame(demo, demo.rename(NavigationItem.Page("no-such-page"), "Name"), "an unknown item is left alone")
    }

    @Test
    fun deletingTheOpenPageOpensTheNextOneElseThePreviousOne() {
        val first = demo.selectPage("lecture-notes").delete(NavigationItem.Page("lecture-notes"))
        assertEquals("homework-1", first.selectedPageId)
        assertEquals(listOf("homework-1"), first.selectedSection!!.pages.map { it.id })

        val last = demo.selectPage("homework-1").delete(NavigationItem.Page("homework-1"))
        assertEquals("lecture-notes", last.selectedPageId)
    }

    @Test
    fun deletingTheOnlyPageLeavesItsSectionOpenAndEmpty() {
        val deleted = demo.selectSection("chapter-2").delete(NavigationItem.Page("sequences"))

        assertEquals("chapter-2", deleted.selectedSectionId)
        assertEquals("calculus", deleted.selectedNotebookId)
        assertEquals("", deleted.selectedPageId)
        assertNull(deleted.selectedPage)
    }

    @Test
    fun deletingAnotherPageKeepsTheOpenPageAndItsEditing() {
        val editing = demo.focusBody()
        val deleted = editing.delete(NavigationItem.Page("lecture-notes"))

        assertEquals("homework-1", deleted.selectedPageId)
        assertEquals(editing.focusedTextOutlineId, deleted.focusedTextOutlineId)
    }

    @Test
    fun deletingASectionTakesItsPagesAndOpensANeighbourInTheSameNotebook() {
        val deleted = demo.delete(NavigationItem.Section("chapter-1"))

        assertEquals(listOf("chapter-2"), deleted.selectedNotebook!!.sections.map { it.id })
        assertEquals("chapter-2", deleted.selectedSectionId)
        assertEquals("sequences", deleted.selectedPageId)
        assertNull(deleted.nameOf(NavigationItem.Page("homework-1")))
    }

    @Test
    fun deletingTheLastSectionStaysInItsNotebookWithNothingOpen() {
        val deleted = demo.selectNotebook("biology").delete(NavigationItem.Section("cell-biology"))

        assertEquals("biology", deleted.selectedNotebookId)
        assertEquals("", deleted.selectedSectionId)
        assertEquals("", deleted.selectedPageId)
    }

    @Test
    fun deletingTheOpenNotebookOpensTheNextOne() {
        val deleted = demo.delete(NavigationItem.Notebook("calculus"))

        assertEquals(listOf("biology"), deleted.notebooks.map { it.id })
        assertEquals("biology", deleted.selectedNotebookId)
        assertEquals("cell-biology", deleted.selectedSectionId)
        assertEquals("meiosis", deleted.selectedPageId)

        val none = deleted.delete(NavigationItem.Notebook("biology"))
        assertTrue(none.notebooks.isEmpty())
        assertEquals(Triple("", "", ""), Triple(none.selectedNotebookId, none.selectedSectionId, none.selectedPageId))
    }

    @Test
    fun deletingAPageDropsItsUndoHistoryAndKeepsOtherPages() {
        val edited = demo.focusBody().deleteTextBox(demo.bodyTextOutline!!.id)
        val lecture = edited.selectPage("lecture-notes")
        val both = lecture.focusBody().deleteTextBox(lecture.bodyTextOutline!!.id)
        assertEquals(setOf("homework-1", "lecture-notes"), both.structuralUndo.map { it.pageId }.toSet())

        val deleted = both.delete(NavigationItem.Page("homework-1"))

        assertEquals(listOf("lecture-notes"), deleted.structuralUndo.map { it.pageId })
        assertTrue(deleted.undoStructure().selectedPage!!.document.outlines.any { it is Outline.Text })
    }

    @Test
    fun foldingANotebookChangesOnlyItsDisclosure() {
        val editing = demo.focusBody()
        val folded = editing.setNotebookExpanded("calculus", false)

        assertEquals(listOf(false, false), folded.notebooks.map { it.expanded })
        assertEquals(editing.copy(notebooks = folded.notebooks), folded, "what is open and being edited stays")
        assertTrue(folded.setNotebookExpanded("calculus", true).notebooks.first().expanded)
        assertSame(folded, folded.setNotebookExpanded("biology", false), "already folded")
        assertSame(demo, demo.setNotebookExpanded("no-such-notebook", false))
    }

    /** Android's `createNotebook`: a blank name is "New Notebook", and it opens on a "New Section". */
    @Test
    fun aNewNotebookOpensOnItsNewSection() {
        val named = demo.addNotebook("  Physics ")
        val notebook = named.notebooks.last()
        assertEquals("Physics", notebook.name)
        assertEquals(listOf(NEW_SECTION_NAME), notebook.sections.map { it.name })
        assertTrue(notebook.expanded)
        assertEquals(notebook.id, named.selectedNotebookId)
        assertEquals(notebook.sections.single().id, named.selectedSectionId)
        assertEquals("", named.selectedPageId, "like Android, the new section starts without a page")

        assertEquals(NEW_NOTEBOOK_NAME, demo.addNotebook("   ").notebooks.last().name)
    }

    /** Android's `createSection`: the new section opens on a page of its own. */
    @Test
    fun aNewSectionOpensOnANewPage() {
        val added = demo.addSection("biology", "Genetics")
        val section = added.notebooks.first { it.id == "biology" }.sections.last()
        assertEquals("Genetics", section.name)
        assertEquals(section.id, added.selectedSectionId)
        assertEquals("biology", added.selectedNotebookId)
        assertEquals(section.pages.single().id, added.selectedPageId)

        assertEquals(NEW_SECTION_NAME, demo.addSection("calculus", "").notebooks.first().sections.last().name)
        assertSame(demo, demo.addSection("no-such-notebook", "Name"))
    }

    @Test
    fun newNotebooksAndSectionsTakeTheStoragePaletteInTurn() {
        val sections = demo.addSection("calculus", "Third").notebooks.first().sections
        assertEquals(com.vivenotes.data.ACCENT_PALETTE[2], sections.last().colorArgb)
        assertEquals(com.vivenotes.data.ACCENT_PALETTE[2], demo.addNotebook("Third").notebooks.last().colorArgb)
    }

    /** The repository's resequencing rule: the ids set the order, what is actually there the members. */
    @Test
    fun reorderingFollowsTheShownOrderAndKeepsWhatItDidNotShow() {
        val sections = demo.reorderSections("calculus", listOf("chapter-2", "chapter-1"))
        assertEquals(listOf("chapter-2", "chapter-1"), sections.notebooks.first().sections.map { it.id })
        assertEquals("chapter-1", sections.selectedSectionId, "reordering opens nothing")

        val pages = demo.reorderPages("chapter-1", listOf("homework-1", "gone-meanwhile"))
        assertEquals(listOf("homework-1", "lecture-notes"), pages.selectedSection!!.pages.map { it.id })
        assertEquals(demo.notebooks.last(), pages.notebooks.last(), "other notebooks are untouched")
    }
}
