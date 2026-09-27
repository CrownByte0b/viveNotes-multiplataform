package com.vivenotes.workspace

/**
 * What the notebook and page panes ask of whoever holds the workspace.
 *
 * [WorkspaceSession] carries each request out in storage as well; [InMemoryNavigation] is the whole
 * of it for the sample workspace and for tests that build a [WorkspaceState] directly.
 */
interface NavigationActions {
    /** Adds a page at the end of the open section and opens it. */
    fun addPage()

    fun rename(item: NavigationItem, name: String)

    /** Deletes [item] with everything in it; the panes have already asked. */
    fun delete(item: NavigationItem)

    fun setNotebookExpanded(id: String, expanded: Boolean)

    /** Adds a notebook, blank [name] meaning [NEW_NOTEBOOK_NAME], and opens its first section. */
    fun createNotebook(name: String)

    /** Adds a section, blank [name] meaning [NEW_SECTION_NAME], and opens it on a new page. */
    fun createSection(notebookId: String, name: String)

    /** A section dragged to a new place: [orderedIds] is the notebook's sections as the pane showed them. */
    fun reorderSections(notebookId: String, orderedIds: List<String>)

    /** A page dragged to a new place: [orderedIds] is the section's pages as the pane showed them. */
    fun reorderPages(sectionId: String, orderedIds: List<String>)
}

/** [NavigationActions] as plain [WorkspaceState] transitions, handed to [onStateChange]. Nothing is stored. */
class InMemoryNavigation(
    private val onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
) : NavigationActions {
    override fun addPage() = onStateChange { it.addPage() }
    override fun rename(item: NavigationItem, name: String) = onStateChange { it.rename(item, name) }
    override fun delete(item: NavigationItem) = onStateChange { it.delete(item) }
    override fun setNotebookExpanded(id: String, expanded: Boolean) = onStateChange { it.setNotebookExpanded(id, expanded) }
    override fun createNotebook(name: String) = onStateChange { it.addNotebook(name) }
    override fun createSection(notebookId: String, name: String) = onStateChange { it.addSection(notebookId, name) }
    override fun reorderSections(notebookId: String, orderedIds: List<String>) =
        onStateChange { it.reorderSections(notebookId, orderedIds) }
    override fun reorderPages(sectionId: String, orderedIds: List<String>) =
        onStateChange { it.reorderPages(sectionId, orderedIds) }
}
