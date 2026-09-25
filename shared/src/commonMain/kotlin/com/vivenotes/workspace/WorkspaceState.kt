package com.vivenotes.workspace

/** Ribbon destinations shared by the desktop shell and the future real editor. */
enum class RibbonTab {
    File,
    Home,
    Insert,
    Draw,
    View,
    Settings,
}

data class PageSummary(
    val id: String,
    val title: String,
    val preview: String,
    val createdLabel: String,
    val body: String,
)

data class SectionSummary(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val pages: List<PageSummary>,
)

data class NotebookSummary(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val sections: List<SectionSummary>,
)

/**
 * Immutable state for the first executable shell.
 *
 * This is intentionally independent of Room and ViewModel. Phase 2 can feed the same shape from a
 * repository without making the shared UI aware of where notes are stored.
 */
data class WorkspaceState(
    val notebooks: List<NotebookSummary>,
    val selectedNotebookId: String,
    val selectedSectionId: String,
    val selectedPageId: String,
    val activeTab: RibbonTab = RibbonTab.Home,
    val navigationVisible: Boolean = true,
) {
    val selectedNotebook: NotebookSummary?
        get() = notebooks.firstOrNull { it.id == selectedNotebookId }

    val selectedSection: SectionSummary?
        get() = notebooks.asSequence()
            .flatMap { it.sections.asSequence() }
            .firstOrNull { it.id == selectedSectionId }

    val selectedPage: PageSummary?
        get() = selectedSection?.pages?.firstOrNull { it.id == selectedPageId }

    fun selectNotebook(id: String): WorkspaceState {
        val notebook = notebooks.firstOrNull { it.id == id } ?: return this
        val section = notebook.sections.firstOrNull()
        return copy(
            selectedNotebookId = notebook.id,
            selectedSectionId = section?.id.orEmpty(),
            selectedPageId = section?.pages?.firstOrNull()?.id.orEmpty(),
        )
    }

    fun selectSection(id: String): WorkspaceState {
        val notebook = notebooks.firstOrNull { notebook ->
            notebook.sections.any { it.id == id }
        } ?: return this
        val section = notebook.sections.first { it.id == id }
        return copy(
            selectedNotebookId = notebook.id,
            selectedSectionId = section.id,
            selectedPageId = section.pages.firstOrNull()?.id.orEmpty(),
        )
    }

    fun selectPage(id: String): WorkspaceState {
        val owner = notebooks.firstNotNullOfOrNull { notebook ->
            notebook.sections.firstOrNull { section -> section.pages.any { it.id == id } }
                ?.let { section -> notebook to section }
        } ?: return this
        return copy(
            selectedNotebookId = owner.first.id,
            selectedSectionId = owner.second.id,
            selectedPageId = id,
        )
    }

    fun updateSelectedPage(
        title: String = selectedPage?.title.orEmpty(),
        body: String = selectedPage?.body.orEmpty(),
    ): WorkspaceState = updatePage(selectedPageId) { page ->
        page.copy(
            title = title,
            body = body,
            preview = body.lineSequence().firstOrNull().orEmpty().take(72),
        )
    }

    fun addPage(): WorkspaceState {
        val section = selectedSection ?: return this
        val ordinal = section.pages.size + 1
        val id = "${section.id}-draft-$ordinal"
        val page = PageSummary(
            id = id,
            title = "Untitled page",
            preview = "Start writing…",
            createdLabel = "Just now",
            body = "",
        )
        return updateSection(section.id) { it.copy(pages = it.pages + page) }
            .copy(selectedPageId = id)
    }

    private fun updatePage(id: String, transform: (PageSummary) -> PageSummary): WorkspaceState =
        copy(
            notebooks = notebooks.map { notebook ->
                notebook.copy(
                    sections = notebook.sections.map { section ->
                        section.copy(
                            pages = section.pages.map { page ->
                                if (page.id == id) transform(page) else page
                            },
                        )
                    },
                )
            },
        )

    private fun updateSection(
        id: String,
        transform: (SectionSummary) -> SectionSummary,
    ): WorkspaceState = copy(
        notebooks = notebooks.map { notebook ->
            notebook.copy(
                sections = notebook.sections.map { section ->
                    if (section.id == id) transform(section) else section
                },
            )
        },
    )

    companion object {
        fun demo(): WorkspaceState {
            val calculus = NotebookSummary(
                id = "calculus",
                name = "Calculus",
                colorArgb = 0xFF45C45A.toInt(),
                sections = listOf(
                    SectionSummary(
                        id = "chapter-1",
                        name = "Chapter 1",
                        colorArgb = 0xFF45C45A.toInt(),
                        pages = listOf(
                            PageSummary(
                                id = "lecture-notes",
                                title = "Lecture notes",
                                preview = "The basic idea of integral calculus is…",
                                createdLabel = "Today, 9:20 AM",
                                body = "The basic idea of integral calculus is to calculate area by adding increasingly small pieces.",
                            ),
                            PageSummary(
                                id = "homework-1",
                                title = "Homework 1",
                                preview = "Review limits and derivatives before Friday.",
                                createdLabel = "Today, 10:45 AM",
                                body = "Review limits and derivatives before Friday.\n\nUse this space to continue the note. Changes in this first skeleton are kept in memory only.",
                            ),
                        ),
                    ),
                    SectionSummary(
                        id = "chapter-2",
                        name = "Chapter 2",
                        colorArgb = 0xFF1E88E5.toInt(),
                        pages = listOf(
                            PageSummary(
                                id = "sequences",
                                title = "Sequences",
                                preview = "Convergence, bounds, and worked examples.",
                                createdLabel = "Yesterday",
                                body = "Convergence, bounds, and worked examples.",
                            ),
                        ),
                    ),
                ),
            )
            val biology = NotebookSummary(
                id = "biology",
                name = "Biology",
                colorArgb = 0xFFFFA726.toInt(),
                sections = listOf(
                    SectionSummary(
                        id = "cell-biology",
                        name = "Cell biology",
                        colorArgb = 0xFFFFA726.toInt(),
                        pages = listOf(
                            PageSummary(
                                id = "meiosis",
                                title = "Meiosis",
                                preview = "Anaphase I and cytokinesis.",
                                createdLabel = "Monday",
                                body = "Anaphase I\n\nHomologous chromosomes separate and move to opposite poles.",
                            ),
                        ),
                    ),
                ),
            )
            return WorkspaceState(
                notebooks = listOf(calculus, biology),
                selectedNotebookId = calculus.id,
                selectedSectionId = "chapter-1",
                selectedPageId = "homework-1",
            )
        }
    }
}
