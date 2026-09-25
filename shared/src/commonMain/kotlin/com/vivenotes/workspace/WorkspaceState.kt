package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Align
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.PageStyle
import com.vivenotes.model.newId
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

private fun PageSummary.withDocument(next: PageDoc): PageSummary = copy(
    document = next,
    preview = next.outlines.filterIsInstance<Outline.Text>().firstOrNull()
        ?.blocks?.joinToString("\n") { it.text }?.lineSequence()?.firstOrNull()?.take(72).orEmpty(),
)

/** Structural history changes containers, while edits made later to surviving text stay intact. */
private fun PageDoc.withCurrentTextFrom(current: PageDoc): PageDoc {
    val liveBlocks = current.outlines.filterIsInstance<Outline.Text>().associate { it.id to it.blocks }
    return copy(outlines = outlines.map { outline ->
        if (outline is Outline.Text) outline.copy(blocks = liveBlocks[outline.id] ?: outline.blocks)
        else outline
    })
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
    val textToolArmed: Boolean = false,
    val objectLassoArmed: Boolean = false,
    val focusedTextOutlineId: String? = null,
    val selectedObjectIds: Set<String> = emptySet(),
    val canvasClipboard: CanvasClipboard = CanvasClipboard(),
    val structuralUndo: List<StructuralSnapshot> = emptyList(),
    val structuralRedo: List<StructuralSnapshot> = emptyList(),
) {
    val selectedNotebook: NotebookSummary?
        get() = notebooks.firstOrNull { it.id == selectedNotebookId }

    val selectedSection: SectionSummary?
        get() = notebooks.asSequence()
            .flatMap { it.sections.asSequence() }
            .firstOrNull { it.id == selectedSectionId }

    val selectedPage: PageSummary?
        get() = selectedSection?.pages?.firstOrNull { it.id == selectedPageId }

    val focusedTextOutline: Outline.Text?
        get() = selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
            ?.firstOrNull { it.id == focusedTextOutlineId }
            ?: selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()?.firstOrNull()

    val richText: RichTextBuffer?
        get() = focusedTextOutline?.let { RichTextBuffer(it.blocks, editorSelection, typingMarks) }

    fun richTextFor(id: String): RichTextBuffer? =
        selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
            ?.firstOrNull { it.id == id }?.let {
                RichTextBuffer(it.blocks, if (id == focusedTextOutline?.id) editorSelection else TextSelection(0),
                    if (id == focusedTextOutline?.id) typingMarks else emptySet())
            }

    fun toggleTextTool(): WorkspaceState = copy(
        textToolArmed = !textToolArmed,
        objectLassoArmed = false,
        selectedObjectIds = emptySet(),
    )

    fun toggleObjectLasso(): WorkspaceState = copy(
        objectLassoArmed = !objectLassoArmed,
        textToolArmed = false,
        selectedObjectIds = emptySet(),
    )

    /** Escape and the Select command return to the ordinary mouse pointer. */
    fun selectPointer(): WorkspaceState = copy(
        textToolArmed = false,
        objectLassoArmed = false,
        focusedTextOutlineId = null,
        selectedObjectIds = emptySet(),
        editorComposition = null,
    )

    fun focusTextBox(id: String): WorkspaceState {
        if (selectedPage?.document?.outlines?.none { it is Outline.Text && it.id == id } != false) return this
        return copy(focusedTextOutlineId = id, selectedObjectIds = emptySet(),
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null)
    }

    fun clearCanvasFocus(): WorkspaceState = copy(focusedTextOutlineId = null, selectedObjectIds = emptySet())

    fun createTextBox(x: Float, y: Float): WorkspaceState {
        if (!textToolArmed || selectedPage == null || y < PageStyle.TITLE_BAND_DP) return this
        val outline = Outline.Text.empty(y = y).copy(x = x.coerceAtLeast(0f))
        return editOutlines { it + outline }.copy(focusedTextOutlineId = outline.id,
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null)
    }

    fun moveTextBox(id: String, dx: Float, dy: Float): WorkspaceState = editOutlines { outlines ->
        outlines.map { if (it is Outline.Text && it.id == id)
            it.copy(x = (it.x + dx).coerceAtLeast(0f), y = (it.y + dy).coerceAtLeast(0f)) else it }
    }

    fun resizeTextBox(id: String, width: Float? = null, minHeight: Float? = null): WorkspaceState =
        editOutlines { outlines -> outlines.map { if (it is Outline.Text && it.id == id)
            it.copy(width = width?.coerceIn(120f, 2000f) ?: it.width,
                minHeight = minHeight?.coerceIn(0f, 4000f) ?: it.minHeight) else it } }

    fun copyTextBox(id: String): WorkspaceState {
        val outline = selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
            ?.firstOrNull { it.id == id } ?: return this
        return copy(canvasClipboard = CanvasClipboard(texts = listOf(outline)))
    }

    fun selectAllTextBox(id: String): WorkspaceState {
        val focused = focusTextBox(id)
        return focused.selectText(TextSelection(0, focused.richText?.text?.length ?: 0))
    }

    fun deleteTextBox(id: String): WorkspaceState = editOutlines { outlines ->
        outlines.filterNot { it is Outline.Text && it.id == id }
    }.copy(focusedTextOutlineId = null, editorSelection = TextSelection(0),
        typingMarks = emptySet(), editorComposition = null)

    fun pasteCanvasAt(x: Float, y: Float): WorkspaceState {
        val page = selectedPage ?: return this
        if (canvasClipboard.isEmpty) return this
        val sources = canvasClipboard.outlines
        val left = sources.minOf { it.x }
        val top = sources.minOf { it.y }
        val pasted = sources.map { it.duplicateAt(it.x - left + x.coerceAtLeast(0f),
            it.y - top + y.coerceAtLeast(0f)) }
        return editOutlines { it + pasted }.copy(
            focusedTextOutlineId = pasted.filterIsInstance<Outline.Text>().firstOrNull()?.id,
            selectedObjectIds = pasted.filter { it.isPrimeObject() }.map { it.id }.toSet(),
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null,
        )
    }

    private fun editOutlines(transform: (List<Outline>) -> List<Outline>): WorkspaceState {
        val page = selectedPage ?: return this
        val next = transform(page.document.outlines)
        if (next == page.document.outlines) return this
        val snapshot = StructuralSnapshot(page.id, page.document)
        return updatePage(page.id) { it.withDocument(it.document.copy(outlines = next)) }
            .copy(structuralUndo = (structuralUndo + snapshot).takeLast(100), structuralRedo = emptyList())
    }

    fun undoStructure(): WorkspaceState {
        val prior = structuralUndo.lastOrNull() ?: return this
        val page = selectedPage ?: return this
        if (prior.pageId != page.id) return this
        val restored = prior.document.withCurrentTextFrom(page.document)
        return updatePage(page.id) { it.withDocument(restored) }.copy(
            structuralUndo = structuralUndo.dropLast(1),
            structuralRedo = structuralRedo + StructuralSnapshot(page.id, page.document),
            focusedTextOutlineId = null, selectedObjectIds = emptySet(), editorSelection = TextSelection(0),
        )
    }

    fun redoStructure(): WorkspaceState {
        val next = structuralRedo.lastOrNull() ?: return this
        val page = selectedPage ?: return this
        if (next.pageId != page.id) return this
        val restored = next.document.withCurrentTextFrom(page.document)
        return updatePage(page.id) { it.withDocument(restored) }.copy(
            structuralRedo = structuralRedo.dropLast(1),
            structuralUndo = structuralUndo + StructuralSnapshot(page.id, page.document),
            focusedTextOutlineId = null, selectedObjectIds = emptySet(), editorSelection = TextSelection(0),
        )
    }

    /** Tapping one member of a locked group holds the whole group. */
    fun selectObject(id: String): WorkspaceState {
        val objects = selectedPage?.document?.outlines?.filter { it.isPrimeObject() } ?: return this
        val tapped = objects.firstOrNull { it.id == id } ?: return this
        val ids = if (tapped.lockGroup == null) setOf(id) else
            objects.filter { it.lockGroup == tapped.lockGroup }.map { it.id }.toSet()
        return copy(selectedObjectIds = ids, focusedTextOutlineId = null)
    }

    /** Mixed lassos prefer movable objects; a loop containing only locks selects those groups. */
    fun selectObjectsInRect(left: Float, top: Float, right: Float, bottom: Float): WorkspaceState {
        val objects = selectedPage?.document?.outlines?.filter { it.isPrimeObject() } ?: return this
        val hit = objects.filter { it.x < right && it.x + it.width > left && it.y < bottom &&
            it.y + it.primeHeight() > top }
        val unlocked = hit.filter { it.lockGroup == null }
        val ids = if (unlocked.isNotEmpty()) unlocked.map { it.id }.toSet() else {
            val groups = hit.mapNotNull { it.lockGroup }.toSet()
            objects.filter { it.lockGroup in groups }.map { it.id }.toSet()
        }
        return copy(selectedObjectIds = ids, focusedTextOutlineId = null)
    }

    val selectedObjectsLocked: Boolean get() = selectedObjectIds.isNotEmpty() &&
        selectedPage?.document?.outlines?.filter { it.id in selectedObjectIds }
            ?.all { it.lockGroup != null } == true

    fun toggleObjectLock(): WorkspaceState {
        if (selectedObjectIds.isEmpty()) return this
        val group = if (selectedObjectsLocked) null else newId()
        return editOutlines { outlines -> outlines.map {
            if (it.id in selectedObjectIds && it.isPrimeObject()) it.withLockGroup(group) else it
        } }
    }

    fun copySelectedObjects(): WorkspaceState {
        val objects = selectedPage?.document?.outlines?.filter {
            it.id in selectedObjectIds && it.isPrimeObject()
        }.orEmpty()
        return if (objects.isEmpty()) this else copy(canvasClipboard = CanvasClipboard(objects = objects))
    }

    fun deleteSelectedObjects(): WorkspaceState = editOutlines { outlines ->
        outlines.filterNot { it.id in selectedObjectIds && it.isPrimeObject() }
    }.copy(selectedObjectIds = emptySet())

    fun moveSelectedObjects(dx: Float, dy: Float): WorkspaceState {
        if (selectedObjectsLocked) return this
        return editOutlines { outlines -> outlines.map {
            if (it.id in selectedObjectIds && it.isPrimeObject()) it.movedBy(dx, dy) else it
        } }
    }

    fun resizeSelectedObjects(anchorX: Float, anchorY: Float, scaleX: Float, scaleY: Float): WorkspaceState {
        if (selectedObjectsLocked || scaleX <= 0f || scaleY <= 0f) return this
        return editOutlines { outlines -> outlines.map { outline ->
            if (outline.id !in selectedObjectIds) outline else when (outline) {
                is Outline.Shape -> outline.scaledAbout(anchorX, anchorY, scaleX, scaleY)
                is Outline.Table -> outline.scaledAbout(anchorX, anchorY, scaleX, scaleY)
                is Outline.Equation -> outline.scaledAbout(anchorX, anchorY, scaleX, scaleY)
                is Outline.Image -> outline.scaledAbout(anchorX, anchorY, scaleX, scaleY)
                is Outline.Text, is Outline.Ink -> outline
            }
        } }
    }

    fun colorSelectedObjects(argb: Int): WorkspaceState = editOutlines { outlines -> outlines.map {
        if (it.id !in selectedObjectIds) it else when (it) {
            is Outline.Shape -> it.copy(borderArgb = argb, borderFollowsTheme = false)
            is Outline.Table -> it.copy(borderArgb = argb, borderFollowsTheme = false)
            is Outline.Equation -> it.copy(colorArgb = argb)
            else -> it
        }
    } }

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
            focusedTextOutlineId = null,
            selectedObjectIds = emptySet(),
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
            focusedTextOutlineId = null,
            selectedObjectIds = emptySet(),
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
            focusedTextOutlineId = null,
            selectedObjectIds = emptySet(),
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
        val oldText = focusedTextOutline ?: return this
        val changedDoc = page.document.copy(outlines = page.document.outlines.map { outline ->
            if (outline.id == oldText.id) oldText.copy(blocks = buffer.blocks) else outline
        })
        return updatePage(page.id) { it.withDocument(changedDoc) }
            .copy(editorSelection = buffer.selection, typingMarks = buffer.typingMarks)
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
