package com.vivenotes.workspace

import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.pagePreview
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

/** Whether a page's [PageSummary.document] is its stored body or a stand-in for one. */
enum class PageContent {
    /** Not read yet: the document is an empty stand-in, shown only while the body loads. */
    Unloaded,

    /** The document is the stored body, with any edits not yet saved. */
    Loaded,

    /**
     * The stored body could not be decoded. The stand-in is never edited, so nothing can ever save
     * it over content that is merely unreadable rather than gone.
     */
    Unreadable,
}

data class PageSummary(
    val id: String,
    val title: String,
    val preview: String,
    val createdLabel: String,
    val document: PageDoc,
    val content: PageContent = PageContent.Loaded,
) {
    val body: String get() = document.outlines.filterIsInstance<Outline.Text>()
        .firstOrNull()?.blocks?.joinToString("\n") { it.text }.orEmpty()

    /** Only a body that was actually read may change: an edit to a stand-in would be saved over it. */
    val editable: Boolean get() = content == PageContent.Loaded
}

private fun PageSummary.withDocument(next: PageDoc): PageSummary =
    copy(document = next, preview = pagePreview(next))

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
 * Immutable state for the workspace window.
 *
 * Independent of Room and of where notes are stored: `WorkspaceSession` fills it from storage and
 * writes its edits back, and tests build it directly.
 */
data class WorkspaceState(
    val notebooks: List<NotebookSummary>,
    val selectedNotebookId: String,
    val selectedSectionId: String,
    val selectedPageId: String,
    /** Document first: the app opens ready for writing. */
    val activeTab: RibbonTab = RibbonTab.Document,
    val navigationVisible: Boolean = true,
    val editorSelection: TextSelection = TextSelection(0),
    val typingMarks: Set<Mark> = emptySet(),
    val editorComposition: TextSelection? = null,
    val textToolArmed: Boolean = false,
    val objectLassoArmed: Boolean = false,
    val focusedTextOutlineId: String? = null,
    val selectedTextOutlineIds: Set<String> = emptySet(),
    val selectedObjectIds: Set<String> = emptySet(),
    val canvasClipboard: CanvasClipboard = CanvasClipboard(),
    /** Text last copied from a text box, with its formatting — see [pasteText]. */
    val textClipboard: TextClipboard? = null,
    val structuralUndo: List<StructuralSnapshot> = emptyList(),
    val structuralRedo: List<StructuralSnapshot> = emptyList(),
    /** Why the last read or write of notes storage failed, until one succeeds again. */
    val storageError: String? = null,
) {
    val selectedNotebook: NotebookSummary?
        get() = notebooks.firstOrNull { it.id == selectedNotebookId }

    val selectedSection: SectionSummary?
        get() = notebooks.asSequence()
            .flatMap { it.sections.asSequence() }
            .firstOrNull { it.id == selectedSectionId }

    val selectedPage: PageSummary?
        get() = selectedSection?.pages?.firstOrNull { it.id == selectedPageId }

    /** The selected page, when its document may be changed; every edit goes through this. */
    private val editablePage: PageSummary?
        get() = selectedPage?.takeIf { it.editable }

    /**
     * The text box being edited, or null when none is. Text commands act on this box and nothing
     * else: with no box focused there is nothing for Bold or a list to apply to.
     */
    val focusedTextOutline: Outline.Text?
        get() = focusedTextOutlineId?.let { id ->
            selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()?.firstOrNull { it.id == id }
        }

    /** The page's first text box: the one [PageSummary.body] reads and [updateSelectedPage] writes. */
    val bodyTextOutline: Outline.Text?
        get() = selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()?.firstOrNull()

    /** The focused text box as an editing buffer; null — and the text commands disabled — without one. */
    val richText: RichTextBuffer?
        get() = focusedTextOutline?.let { RichTextBuffer(it.blocks, editorSelection, typingMarks) }

    fun richTextFor(id: String): RichTextBuffer? =
        selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
            ?.firstOrNull { it.id == id }?.let {
                RichTextBuffer(it.blocks, if (id == focusedTextOutline?.id) editorSelection else TextSelection(0),
                    if (id == focusedTextOutline?.id) typingMarks else emptySet())
            }

    fun toggleTextTool(): WorkspaceState = discardEmptyFocusedTextBox().copy(
        textToolArmed = !textToolArmed,
        objectLassoArmed = false,
        selectedTextOutlineIds = emptySet(),
        selectedObjectIds = emptySet(),
    )

    fun toggleObjectLasso(): WorkspaceState = discardEmptyFocusedTextBox().copy(
        objectLassoArmed = !objectLassoArmed,
        textToolArmed = false,
        selectedTextOutlineIds = emptySet(),
        selectedObjectIds = emptySet(),
    )

    /** Escape and the Select command return to the ordinary mouse pointer. */
    fun selectPointer(): WorkspaceState = discardEmptyFocusedTextBox().copy(
        textToolArmed = false,
        objectLassoArmed = false,
        focusedTextOutlineId = null,
        selectedTextOutlineIds = emptySet(),
        selectedObjectIds = emptySet(),
        editorComposition = null,
    )

    fun focusTextBox(id: String): WorkspaceState {
        if (selectedPage?.document?.outlines?.none { it is Outline.Text && it.id == id } != false) return this
        val next = if (id == focusedTextOutlineId) this else discardEmptyFocusedTextBox()
        return next.copy(focusedTextOutlineId = id, selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(),
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null)
    }

    fun clearCanvasFocus(): WorkspaceState = discardEmptyFocusedTextBox().copy(
        focusedTextOutlineId = null, selectedTextOutlineIds = emptySet(),
        selectedObjectIds = emptySet(), editorComposition = null)

    private fun discardEmptyFocusedTextBox(): WorkspaceState {
        val id = focusedTextOutlineId ?: return this
        val page = editablePage ?: return this
        val outline = page.document.outlines.firstOrNull { it.id == id } as? Outline.Text
            ?: return this
        if (!outline.isUnwritten()) return this
        return updatePage(page.id) { candidate ->
            candidate.withDocument(candidate.document.copy(
                outlines = candidate.document.outlines.filterNot { it.id == id }))
        }.copy(focusedTextOutlineId = null, editorSelection = TextSelection(0),
            typingMarks = emptySet(), editorComposition = null)
            .discardEmptyTextHistory(setOf(id))
    }

    private fun Outline.Text.isUnwritten(): Boolean =
        blocks.isNotEmpty() && blocks.all { it.text.isBlank() }

    fun createTextBox(x: Float, y: Float): WorkspaceState {
        val page = editablePage
        if (!textToolArmed || page == null || y < PageStyle.TITLE_BAND_DP) return this
        val outline = Outline.Text.empty(y = y).copy(x = x.coerceAtLeast(0f))
        val removedIds = page.document.outlines.filterIsInstance<Outline.Text>()
            .filter { it.isUnwritten() }.map { it.id }.toSet()
        return editOutlines { outlines ->
            outlines.filterNot { it is Outline.Text && it.isUnwritten() } + outline
        }.copy(focusedTextOutlineId = outline.id, selectedTextOutlineIds = emptySet(),
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null)
            .discardEmptyTextHistory(removedIds)
    }

    /** A provisional box must not return through Undo after it was abandoned. */
    private fun discardEmptyTextHistory(ids: Set<String>): WorkspaceState {
        if (ids.isEmpty()) return this
        val page = selectedPage ?: return this
        fun List<StructuralSnapshot>.withoutIds() = map { snapshot ->
            if (snapshot.pageId != page.id) snapshot else snapshot.copy(
                document = snapshot.document.copy(outlines = snapshot.document.outlines
                    .filterNot { it.id in ids }))
        }
        var undo = structuralUndo.withoutIds()
        while (undo.lastOrNull()?.let { it.pageId == page.id && it.document == page.document } == true) {
            undo = undo.dropLast(1)
        }
        return copy(structuralUndo = undo, structuralRedo = structuralRedo.withoutIds())
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
        typingMarks = emptySet(), editorComposition = null,
        selectedTextOutlineIds = selectedTextOutlineIds - id)

    fun pasteCanvasAt(x: Float, y: Float): WorkspaceState {
        if (editablePage == null || canvasClipboard.isEmpty) return this
        val sources = canvasClipboard.outlines
        val left = sources.minOf { it.x }
        val top = sources.minOf { it.y }
        val pasted = sources.map { it.duplicateAt(it.x - left + x.coerceAtLeast(0f),
            it.y - top + y.coerceAtLeast(0f)) }
        return editOutlines { it + pasted }.copy(
            focusedTextOutlineId = pasted.filterIsInstance<Outline.Text>().firstOrNull()?.id,
            selectedTextOutlineIds = emptySet(),
            selectedObjectIds = pasted.filter { it.isPrimeObject() }.map { it.id }.toSet(),
            editorSelection = TextSelection(0), typingMarks = emptySet(), editorComposition = null,
        )
    }

    private fun editOutlines(transform: (List<Outline>) -> List<Outline>): WorkspaceState {
        val page = editablePage ?: return this
        val next = transform(page.document.outlines)
        if (next == page.document.outlines) return this
        val snapshot = StructuralSnapshot(page.id, page.document)
        return updatePage(page.id) { it.withDocument(it.document.copy(outlines = next)) }
            .copy(structuralUndo = (structuralUndo + snapshot).takeLast(100), structuralRedo = emptyList())
    }

    fun undoStructure(): WorkspaceState {
        val prior = structuralUndo.lastOrNull() ?: return this
        val page = editablePage ?: return this
        if (prior.pageId != page.id) return this
        val restored = prior.document.withCurrentTextFrom(page.document)
        return updatePage(page.id) { it.withDocument(restored) }.copy(
            structuralUndo = structuralUndo.dropLast(1),
            structuralRedo = structuralRedo + StructuralSnapshot(page.id, page.document),
            focusedTextOutlineId = null, selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(), editorSelection = TextSelection(0),
        )
    }

    fun redoStructure(): WorkspaceState {
        val next = structuralRedo.lastOrNull() ?: return this
        val page = editablePage ?: return this
        if (next.pageId != page.id) return this
        val restored = next.document.withCurrentTextFrom(page.document)
        return updatePage(page.id) { it.withDocument(restored) }.copy(
            structuralRedo = structuralRedo.dropLast(1),
            structuralUndo = structuralUndo + StructuralSnapshot(page.id, page.document),
            focusedTextOutlineId = null, selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(), editorSelection = TextSelection(0),
        )
    }

    /** Tapping one member of a locked group holds the whole group. */
    fun selectObject(id: String): WorkspaceState {
        val objects = selectedPage?.document?.outlines?.filter { it.isPrimeObject() } ?: return this
        val tapped = objects.firstOrNull { it.id == id } ?: return this
        val ids = if (tapped.lockGroup == null) setOf(id) else
            objects.filter { it.lockGroup == tapped.lockGroup }.map { it.id }.toSet()
        return discardEmptyFocusedTextBox().copy(selectedObjectIds = ids,
            selectedTextOutlineIds = emptySet(), focusedTextOutlineId = null)
    }

    /** Mixed lassos prefer movable outlines; a loop containing only locks selects those groups. */
    fun selectObjectsInRect(left: Float, top: Float, right: Float, bottom: Float): WorkspaceState {
        val cleaned = discardEmptyFocusedTextBox()
        val outlines = cleaned.selectedPage?.document?.outlines ?: return cleaned
        val objects = outlines.filter { it.isPrimeObject() }
        val hit = objects.filter { it.x < right && it.x + it.width > left && it.y < bottom &&
            it.y + it.primeHeight() > top }
        val textIds = outlines.filterIsInstance<Outline.Text>().filter { text ->
            !text.isUnwritten() && text.x < right && text.x + text.width > left &&
                text.y < bottom && text.y + maxOf(text.minHeight, 150f) > top
        }.map { it.id }.toSet()
        val unlocked = hit.filter { it.lockGroup == null }
        val ids = if (unlocked.isNotEmpty() || textIds.isNotEmpty()) unlocked.map { it.id }.toSet() else {
            val groups = hit.mapNotNull { it.lockGroup }.toSet()
            objects.filter { it.lockGroup in groups }.map { it.id }.toSet()
        }
        return cleaned.copy(selectedObjectIds = ids, selectedTextOutlineIds = textIds,
            focusedTextOutlineId = null)
    }

    val selectedObjectsLocked: Boolean get() = selectedTextOutlineIds.isEmpty() &&
        selectedObjectIds.isNotEmpty() &&
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
        val outlines = selectedPage?.document?.outlines.orEmpty()
        val objects = outlines.filter {
            it.id in selectedObjectIds && it.isPrimeObject()
        }
        val texts = outlines.filterIsInstance<Outline.Text>()
            .filter { it.id in selectedTextOutlineIds }
        return if (objects.isEmpty() && texts.isEmpty()) this else
            copy(canvasClipboard = CanvasClipboard(objects = objects, texts = texts))
    }

    fun deleteSelectedObjects(): WorkspaceState = editOutlines { outlines ->
        outlines.filterNot { it.id in selectedTextOutlineIds ||
            (it.id in selectedObjectIds && it.isPrimeObject()) }
    }.copy(selectedObjectIds = emptySet(), selectedTextOutlineIds = emptySet())

    fun moveSelectedObjects(dx: Float, dy: Float): WorkspaceState {
        if (selectedObjectsLocked) return this
        return editOutlines { outlines -> outlines.map {
            when {
                it.id in selectedObjectIds && it.isPrimeObject() -> it.movedBy(dx, dy)
                it is Outline.Text && it.id in selectedTextOutlineIds ->
                    it.copy(x = (it.x + dx).coerceAtLeast(0f), y = (it.y + dy).coerceAtLeast(0f))
                else -> it
            }
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
        return discardEmptyFocusedTextBox().copy(
            selectedNotebookId = notebook.id,
            selectedSectionId = section?.id.orEmpty(),
            selectedPageId = section?.pages?.firstOrNull()?.id.orEmpty(),
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
            focusedTextOutlineId = null,
            selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(),
        )
    }

    fun selectSection(id: String): WorkspaceState {
        val notebook = notebooks.firstOrNull { notebook ->
            notebook.sections.any { it.id == id }
        } ?: return this
        val section = notebook.sections.first { it.id == id }
        return discardEmptyFocusedTextBox().copy(
            selectedNotebookId = notebook.id,
            selectedSectionId = section.id,
            selectedPageId = section.pages.firstOrNull()?.id.orEmpty(),
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
            focusedTextOutlineId = null,
            selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(),
        )
    }

    fun selectPage(id: String): WorkspaceState {
        val owner = notebooks.firstNotNullOfOrNull { notebook ->
            notebook.sections.firstOrNull { section -> section.pages.any { it.id == id } }
                ?.let { section -> notebook to section }
        } ?: return this
        return discardEmptyFocusedTextBox().copy(
            selectedNotebookId = owner.first.id,
            selectedSectionId = owner.second.id,
            selectedPageId = id,
            editorSelection = TextSelection(0),
            typingMarks = emptySet(),
            editorComposition = null,
            focusedTextOutlineId = null,
            selectedTextOutlineIds = emptySet(),
            selectedObjectIds = emptySet(),
        )
    }

    fun updateSelectedPage(
        title: String = selectedPage?.title.orEmpty(),
        body: String = selectedPage?.body.orEmpty(),
    ): WorkspaceState {
        val current = selectedPage ?: return this
        val target = bodyTextOutline
        val withBody = if (body == current.body || target == null || target.blocks.isEmpty()) this else {
            val whole = RichTextBuffer(target.blocks).let { it.select(TextSelection(0, it.text.length)) }
            val replaced = whole.replace(body)
            val page = editablePage
            if (page == null) this else updatePage(page.id) { candidate ->
                candidate.withDocument(candidate.document.copy(outlines = candidate.document.outlines.map {
                    if (it.id == target.id) target.copy(blocks = replaced.blocks) else it
                }))
            }.let { if (focusedTextOutlineId == target.id) it.copy(editorSelection = replaced.selection) else it }
        }
        return withBody.updatePage(selectedPageId) { it.copy(title = title) }
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

    /** Links the selection, changes the link at the caret, or inserts [label] linked there. */
    fun insertLink(label: String, url: String): WorkspaceState =
        richText?.insertLink(label, url)?.let(::withRichText) ?: this

    /** Ticks or unticks a to-do in any text box, focused or not. */
    fun toggleTodo(outlineId: String, blockId: String): WorkspaceState {
        val page = editablePage ?: return this
        val outline = page.document.outlines.firstOrNull { it.id == outlineId } as? Outline.Text ?: return this
        if (outline.blocks.isEmpty()) return this
        val blocks = RichTextBuffer(outline.blocks).toggleChecked(blockId).blocks
        if (blocks == outline.blocks) return this
        return updatePage(page.id) { current ->
            current.withDocument(current.document.copy(outlines = current.document.outlines.map {
                if (it.id == outlineId) outline.copy(blocks = blocks) else it
            }))
        }
    }

    /**
     * Android's `insertImage`: puts a stored picture where the user is looking — [viewLeft] and
     * [viewTop] are the page point at the canvas's top left — clear of the title band, at
     * [Outline.Image.DEFAULT_WIDTH] and the picture's own aspect ratio. Nothing is left armed, so
     * the next click reaches the picture. [pageId] is the page the picture was chosen for: the
     * choice takes a while, and a picture must not land on a page opened meanwhile.
     */
    fun insertPicture(pageId: String, picture: ImportedPicture, viewLeft: Float, viewTop: Float): WorkspaceState {
        val page = editablePage?.takeIf { it.id == pageId } ?: return this
        val aspect = if (picture.pixelWidth > 0) picture.pixelHeight.toFloat() / picture.pixelWidth else 1f
        val width = Outline.Image.DEFAULT_WIDTH
        val titleFloor = if (page.document.style.hideTitle) 0f else PageStyle.TITLE_BAND_DP
        val image = Outline.Image(
            id = newId(),
            x = (viewLeft + PICTURE_INSERT_MARGIN).coerceAtLeast(0f),
            y = maxOf(viewTop + PICTURE_INSERT_MARGIN, titleFloor).coerceAtLeast(0f),
            width = width,
            height = (width * aspect).coerceAtLeast(Outline.Image.MIN_SIZE),
            attachmentId = picture.attachmentId,
        )
        return editOutlines { it + image }.copy(textToolArmed = false, objectLassoArmed = false)
    }

    internal fun withRichText(buffer: RichTextBuffer): WorkspaceState {
        val page = editablePage ?: return this
        val oldText = focusedTextOutline ?: return this
        val changedDoc = page.document.copy(outlines = page.document.outlines.map { outline ->
            if (outline.id == oldText.id) oldText.copy(blocks = buffer.blocks) else outline
        })
        return updatePage(page.id) { it.withDocument(changedDoc) }
            .copy(editorSelection = buffer.selection, typingMarks = buffer.typingMarks)
    }

    /**
     * An in-memory draft page, for the sample workspace. A stored workspace creates its pages in
     * storage instead — `WorkspaceSession.addPage` — so that they have real ids and survive.
     */
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

    /** Replaces one page wherever it is. No editability check: storage uses it to load and unload. */
    internal fun updatePage(id: String, transform: (PageSummary) -> PageSummary): WorkspaceState =
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
        /** Android's `INSERT_MARGIN`: how far inside the visible corner a picture is placed. */
        const val PICTURE_INSERT_MARGIN = 24f

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
