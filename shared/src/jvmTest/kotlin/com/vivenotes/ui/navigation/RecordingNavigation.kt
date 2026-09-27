package com.vivenotes.ui.navigation

import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.NavigationItem

/** [NavigationActions] that only remember what they were asked, so a pane can be tested alone. */
class RecordingNavigation : NavigationActions {
    var pagesAdded = 0
        private set
    val renames = mutableListOf<Pair<NavigationItem, String>>()
    val deletes = mutableListOf<NavigationItem>()
    val expansions = mutableListOf<Pair<String, Boolean>>()
    val notebooksCreated = mutableListOf<String>()
    val sectionsCreated = mutableListOf<Pair<String, String>>()
    val sectionOrders = mutableListOf<Pair<String, List<String>>>()
    val pageOrders = mutableListOf<Pair<String, List<String>>>()

    override fun addPage() {
        pagesAdded++
    }

    override fun rename(item: NavigationItem, name: String) {
        renames += item to name
    }

    override fun delete(item: NavigationItem) {
        deletes += item
    }

    override fun setNotebookExpanded(id: String, expanded: Boolean) {
        expansions += id to expanded
    }

    override fun createNotebook(name: String) {
        notebooksCreated += name
    }

    override fun createSection(notebookId: String, name: String) {
        sectionsCreated += notebookId to name
    }

    override fun reorderSections(notebookId: String, orderedIds: List<String>) {
        sectionOrders += notebookId to orderedIds
    }

    override fun reorderPages(sectionId: String, orderedIds: List<String>) {
        pageOrders += sectionId to orderedIds
    }
}
