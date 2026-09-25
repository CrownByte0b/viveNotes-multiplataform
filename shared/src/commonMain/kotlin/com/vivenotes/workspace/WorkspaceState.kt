package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Align
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection

/** Ribbon destinations shared by the desktop shell and the future real editor. */
enum class RibbonTab {
    File,
    Draw,
    Document,
    View,
    Settings,
}

data class PageSummary(
    val id: String,
    val title: String,
    val preview: String,
    val createdLabel: String,
    val document: PageDoc,
) {
    val body: String get() = document.outlines.filterIsInstance<Outline.Text>()
        .firstOrNull()?.blocks?.joinToString("\n") { it.text }.orEmpty()
}

private fun textDocument(body: String): PageDoc {
    val text = Outline.Text.empty(y = com.vivenotes.model.PageStyle.TITLE_BAND_DP)
    return PageDoc(outlines = listOf(text.copy(blocks = body.split('\n').map(Block::of))))
}

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
    val activeTab: RibbonTab = RibbonTab.Draw,
    val navigationVisible: Boolean = true,
    val editorSelection: TextSelection = TextSelection(0),
    val typingMarks: Set<Mark> = emptySet(),
    val editorComposition: TextSelection? = null,
) {
    val selectedNotebook: NotebookSummary?
        get() = notebooks.firstOrNull { it.id == selectedNotebookId }

    val selectedSection: SectionSummary?
        get() = notebooks.asSequence()
            .flatMap { it.sections.asSequence() }
            .firstOrNull { it.id == selectedSectionId }

    val selectedPage: PageSummary?
        get() = selectedSection?.pages?.firstOrNull { it.id == selectedPageId }

    val richText: RichTextBuffer?
        get() = selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
            ?.firstOrNull()?.let { RichTextBuffer(it.blocks, editorSelection, typingMarks) }

    fun selectNotebook(id: String): WorkspaceState {
        val notebook = notebooks.firstOrNull { it.id == id } ?: return this
        val section = notebook.sections.firstOrNull()
        return copy(
            selectedNotebookId = notebook.id,
            selectedSectionId = section?.id.orEmpty(),
            selectedPageId = section?.pages?.firstOrNull()?.id.orEmpty(),
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
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
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
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
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
        )
    }

    fun updateSelectedPage(
        title: String = selectedPage?.title.orEmpty(),
        body: String = selectedPage?.body.orEmpty(),
    ): WorkspaceState {
        val current = selectedPage ?: return this
        val withBody = if (body == current.body) this else {
            val length = richText?.text?.length ?: 0
            copy(editorSelection = TextSelection(0, length), typingMarks = emptySet())
                .richText?.replace(body)?.let(::withRichText) ?: this
        }
        return withBody.updatePage(selectedPageId) { it.copy(
            title = title,
            preview = body.lineSequence().firstOrNull().orEmpty().take(72),
        ) }
    }

    fun selectText(selection: TextSelection): WorkspaceState {
        val next = richText?.select(selection) ?: return this
        return copy(editorSelection = next.selection, typingMarks = next.typingMarks)
    }

    fun editSelectedText(
        text: String,
        selection: TextSelection,
        composition: TextSelection? = null,
    ): WorkspaceState {
        val next = richText?.acceptTextChange(text, selection) ?: return this
        return withRichText(next).copy(editorComposition = composition?.clamped(text.length))
    }

    fun toggleSelectedMark(mark: Mark): WorkspaceState =
        richText?.toggleMark(mark)?.let(::withRichText) ?: this

    fun setSelectedMark(mark: Mark): WorkspaceState =
        richText?.setMark(mark)?.let(::withRichText) ?: this

    fun clearSelectedMark(mark: Mark): WorkspaceState =
        richText?.clearMark(mark)?.let(::withRichText) ?: this

    fun replaceSelectedText(text: String): WorkspaceState =
        richText?.replace(text)?.let(::withRichText) ?: this

    val selectedText: String
        get() = richText?.let { buffer ->
            buffer.text.substring(buffer.selection.min, buffer.selection.max)
        }.orEmpty()

    fun clearSelectedFormatting(): WorkspaceState =
        richText?.clearFormatting()?.let(::withRichText) ?: this

    fun setSelectedBlockType(type: BlockType): WorkspaceState =
        richText?.setBlockType(type)?.let(::withRichText) ?: this

    fun alignSelectedText(align: Align): WorkspaceState =
        richText?.setAlign(align)?.let(::withRichText) ?: this

    fun indentSelectedText(delta: Int): WorkspaceState =
        richText?.indent(delta)?.let(::withRichText) ?: this

    private fun withRichText(buffer: RichTextBuffer): WorkspaceState {
        val page = selectedPage ?: return this
        val oldText = page.document.outlines.filterIsInstance<Outline.Text>().firstOrNull() ?: return this
        val changedDoc = page.document.copy(outlines = page.document.outlines.map { outline ->
            if (outline === oldText) oldText.copy(blocks = buffer.blocks) else outline
        })
        return updatePage(page.id) { it.copy(
            document = changedDoc,
            preview = buffer.text.lineSequence().firstOrNull().orEmpty().take(72),
        ) }.copy(editorSelection = buffer.selection, typingMarks = buffer.typingMarks)
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
            document = textDocument(""),
        )
        return updateSection(section.id) { it.copy(pages = it.pages + page) }
            .copy(selectedPageId = id, editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null)
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
                                document = textDocument("The basic idea of integral calculus is to calculate area by adding increasingly small pieces."),
                            ),
                            PageSummary(
                                id = "homework-1",
                                title = "Homework 1",
                                preview = "Review limits and derivatives before Friday.",
                                createdLabel = "Today, 10:45 AM",
                                document = textDocument("Review limits and derivatives before Friday.\n\nUse this space to continue the note. Changes in this first skeleton are kept in memory only."),
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
                                document = textDocument("Convergence, bounds, and worked examples."),
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
                                document = textDocument("Anaphase I\n\nHomologous chromosomes separate and move to opposite poles."),
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
