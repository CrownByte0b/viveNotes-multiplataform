package com.vivenotes.workspace

import com.vivenotes.richtext.TextSelection

/** A notebook, section or page, as the navigation panes' right-click menus act on it. */
sealed interface NavigationItem {
    val id: String

    data class Notebook(override val id: String) : NavigationItem
    data class Section(override val id: String) : NavigationItem
    data class Page(override val id: String) : NavigationItem
}

/** What [item] is listed as — a page's title may be blank — or null when it is not in the workspace. */
fun WorkspaceState.nameOf(item: NavigationItem): String? = when (item) {
    is NavigationItem.Notebook -> notebooks.firstOrNull { it.id == item.id }?.name
    is NavigationItem.Section -> notebooks.flatMap { it.sections }.firstOrNull { it.id == item.id }?.name
    is NavigationItem.Page -> notebooks.flatMap { it.sections }.flatMap { it.pages }
        .firstOrNull { it.id == item.id }?.title
}

/**
 * Gives [item] a new name, trimmed. A blank name changes nothing: Android's rule, since there is no
 * sensible default for something that already has a name.
 *
 * This is the whole rename for the in-memory sample. A stored workspace goes through
 * `WorkspaceSession.rename`, which applies this for immediate feedback and writes storage.
 */
fun WorkspaceState.rename(item: NavigationItem, name: String): WorkspaceState {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || nameOf(item) == null) return this
    return when (item) {
        is NavigationItem.Notebook -> copy(notebooks = notebooks.map {
            if (it.id == item.id) it.copy(name = trimmed) else it
        })
        is NavigationItem.Section -> copy(notebooks = notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { if (it.id == item.id) it.copy(name = trimmed) else it })
        })
        is NavigationItem.Page -> updatePage(item.id) { it.copy(title = trimmed) }
    }
}

/**
 * Takes [item], and everything in it, out of the workspace.
 *
 * When the open page goes with it, the workspace moves to the nearest thing left at the same level
 * — the next page, section or notebook, else the one before — and otherwise stays where it was with
 * nothing open, rather than jumping into another notebook. Undo history for the pages removed is
 * dropped, since there is no page left for it to apply to.
 */
fun WorkspaceState.delete(item: NavigationItem): WorkspaceState {
    if (nameOf(item) == null) return this
    val remaining = when (item) {
        is NavigationItem.Notebook -> copy(notebooks = notebooks.filterNot { it.id == item.id })
        is NavigationItem.Section -> copy(notebooks = notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.filterNot { it.id == item.id })
        })
        is NavigationItem.Page -> copy(notebooks = notebooks.map { notebook ->
            notebook.copy(sections = notebook.sections.map { section ->
                section.copy(pages = section.pages.filterNot { it.id == item.id })
            })
        })
    }.let { it.withoutHistoryOf(pageIds() - it.pageIds()) }
    return when (item) {
        is NavigationItem.Notebook -> if (item.id != selectedNotebookId) remaining else
            neighbour(notebooks.map { it.id }, item.id)?.let(remaining::selectNotebook) ?: remaining.closed()
        is NavigationItem.Section -> if (item.id != selectedSectionId) remaining else {
            val siblings = selectedNotebook?.sections.orEmpty().map { it.id }
            neighbour(siblings, item.id)?.let(remaining::selectSection)
                ?: remaining.closed().copy(selectedNotebookId = selectedNotebookId)
        }
        is NavigationItem.Page -> if (item.id != selectedPageId) remaining else {
            val siblings = selectedSection?.pages.orEmpty().map { it.id }
            neighbour(siblings, item.id)?.let(remaining::selectPage)
                ?: remaining.closed().copy(selectedNotebookId = selectedNotebookId, selectedSectionId = selectedSectionId)
        }
    }
}

/** The entry after [id] in [ids], else the one before it; null when [id] was the only one. */
private fun neighbour(ids: List<String>, id: String): String? {
    val index = ids.indexOf(id)
    return ids.getOrNull(index + 1) ?: ids.getOrNull(index - 1)
}

/** Nothing open, and no editing state left pointing at what was. */
private fun WorkspaceState.closed(): WorkspaceState = copy(
    selectedNotebookId = "",
    selectedSectionId = "",
    selectedPageId = "",
    editorSelection = TextSelection(0),
    typingMarks = emptySet(),
    editorComposition = null,
    focusedTextOutlineId = null,
    selectedTextOutlineIds = emptySet(),
    selectedObjectIds = emptySet(),
)

private fun WorkspaceState.pageIds(): Set<String> =
    notebooks.flatMap { it.sections }.flatMap { it.pages }.mapTo(mutableSetOf()) { it.id }

/** A stored workspace does not hold every section's pages, so only what was just removed is named. */
private fun WorkspaceState.withoutHistoryOf(removed: Set<String>): WorkspaceState = copy(
    structuralUndo = structuralUndo.filterNot { it.pageId in removed },
    structuralRedo = structuralRedo.filterNot { it.pageId in removed },
)
