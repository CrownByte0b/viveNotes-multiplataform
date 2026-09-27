package com.vivenotes.workspace

import com.vivenotes.data.ACCENT_PALETTE
import com.vivenotes.model.newId
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

/** What a notebook left unnamed in the New notebook dialog is called (Android's default). */
const val NEW_NOTEBOOK_NAME = "New Notebook"

/** What a section left unnamed is called — also the first section of every new notebook. */
const val NEW_SECTION_NAME = "New Section"

/** [name] trimmed, or [NEW_NOTEBOOK_NAME] when blank: unlike a rename, creating has a default. */
fun notebookName(name: String): String = name.trim().ifEmpty { NEW_NOTEBOOK_NAME }

/** [name] trimmed, or [NEW_SECTION_NAME] when blank. */
fun sectionName(name: String): String = name.trim().ifEmpty { NEW_SECTION_NAME }

/** Shows or hides a notebook's sections in the notebook pane. Nothing else changes, not even what is open. */
fun WorkspaceState.setNotebookExpanded(id: String, expanded: Boolean): WorkspaceState {
    if (notebooks.none { it.id == id && it.expanded != expanded }) return this
    return copy(notebooks = notebooks.map { if (it.id == id) it.copy(expanded = expanded) else it })
}

/**
 * Adds a notebook at the end with one [NEW_SECTION_NAME] section, and opens that section — the whole
 * of it for the in-memory sample. A stored workspace creates both in storage: `WorkspaceSession`.
 */
fun WorkspaceState.addNotebook(name: String): WorkspaceState {
    val section = SectionSummary(newId(), NEW_SECTION_NAME, ACCENT_PALETTE.first(), emptyList())
    val notebook = NotebookSummary(newId(), notebookName(name), accent(notebooks.size), listOf(section))
    return copy(notebooks = notebooks + notebook).selectSection(section.id)
}

/** Adds a section at the end of [notebookId] and opens it on a new page, as Android does. */
fun WorkspaceState.addSection(notebookId: String, name: String): WorkspaceState {
    val notebook = notebooks.firstOrNull { it.id == notebookId } ?: return this
    val section = SectionSummary(newId(), sectionName(name), accent(notebook.sections.size), emptyList())
    return copy(notebooks = notebooks.map { if (it.id == notebookId) it.copy(sections = it.sections + section) else it })
        .selectSection(section.id)
        .addPage()
}

/** Puts [notebookId]'s sections in [orderedIds]' order — see [inOrder]. */
fun WorkspaceState.reorderSections(notebookId: String, orderedIds: List<String>): WorkspaceState =
    copy(notebooks = notebooks.map { notebook ->
        if (notebook.id == notebookId) notebook.copy(sections = notebook.sections.inOrder(orderedIds) { it.id }) else notebook
    })

/** Puts [sectionId]'s pages in [orderedIds]' order — see [inOrder]. */
fun WorkspaceState.reorderPages(sectionId: String, orderedIds: List<String>): WorkspaceState =
    copy(notebooks = notebooks.map { notebook ->
        notebook.copy(sections = notebook.sections.map { section ->
            if (section.id == sectionId) section.copy(pages = section.pages.inOrder(orderedIds) { it.id }) else section
        })
    })

/**
 * The repository's reorder rule: what is here decides membership and [orderedIds] only the sequence,
 * so anything the list did not show when it was dragged keeps its relative place at the end.
 */
private fun <T> List<T>.inOrder(orderedIds: List<String>, id: (T) -> String): List<T> {
    val byId = associateBy(id)
    val requested = orderedIds.distinct().mapNotNull(byId::get)
    return requested + filterNot { it in requested }
}

/** The colour storage gives the [index]th notebook, or section of a notebook. */
private fun accent(index: Int): Int = ACCENT_PALETTE[index % ACCENT_PALETTE.size]
