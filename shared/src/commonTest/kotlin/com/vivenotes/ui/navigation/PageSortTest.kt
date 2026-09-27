package com.vivenotes.ui.navigation

import com.vivenotes.model.PageDoc
import com.vivenotes.workspace.PageSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** The page pane's three orders, with Android's labels. */
class PageSortTest {

    private fun page(id: String, title: String, updatedAt: Long) =
        PageSummary(id, title, preview = "", createdLabel = "", document = PageDoc(outlines = emptyList()), updatedAt = updatedAt)

    private val pages = listOf(page("a", "bravo", 100), page("b", "", 300), page("c", "Alpha", 200))

    @Test
    fun sectionOrderIsTheSectionsOwn() {
        assertEquals(listOf("a", "b", "c"), sortedPages(pages, PageSort.Manual).map { it.id })
    }

    /** Case does not decide it, and an untitled page sorts by the name the list shows for it. */
    @Test
    fun byTitleIgnoresCaseAndSortsUntitledPagesByTheirShownName() {
        assertEquals(listOf("c", "a", "b"), sortedPages(pages, PageSort.Alphabetical).map { it.id })
    }

    @Test
    fun byDateModifiedPutsTheLatestFirst() {
        assertEquals(listOf("b", "c", "a"), sortedPages(pages, PageSort.Recent).map { it.id })
    }

    @Test
    fun theMenuUsesAndroidsLabels() {
        assertEquals(listOf("Section order", "By title", "By date modified"), PageSort.entries.map { it.label })
    }
}
