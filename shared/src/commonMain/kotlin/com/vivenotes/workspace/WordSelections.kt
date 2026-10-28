package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.model.Mark
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.withCellBlocks
import com.vivenotes.richtext.RichTextBuffer
import com.vivenotes.richtext.TextSelection

data class SelectedTextRange(val containerId: String, val range: TextSelection, val typingMarks: Set<Mark>? = null)

/** Transient selections in one text box or in the cells of one table; the last range is active. */
data class WordSelections(val pageId: String, val ownerId: String, val ranges: List<SelectedTextRange>)

internal val WorkspaceState.activeWordSelections: WordSelections?
    get() = wordSelections?.takeIf { group ->
        group.pageId == selectedPageId && group.ranges.isNotEmpty() &&
            group.ranges.last().containerId == focusedTextOutlineId &&
            group.ranges.last().range == editorSelection &&
            selectedPage?.document?.selectionOwner(group.ranges.last().containerId) == group.ownerId &&
            group.ranges.all { target -> selectedPage?.document?.textIndex?.get(target.containerId)?.let {
                target.range.min >= 0 && target.range.max <= RichTextBuffer(it.blocks).text.length
            } == true }
    }

fun WorkspaceState.textSelectionsFor(id: String): List<TextSelection> =
    activeWordSelections?.ranges?.filter { it.containerId == id }?.map { it.range }.orEmpty()

private fun PageDoc.selectionOwner(id: String): String? = outlines.firstOrNull { outline ->
    outline is Outline.Text && outline.id == id || outline is Outline.Table &&
        !outline.inkOnly && outline.lockGroup == null && outline.locate(id) != null
}?.id

fun WorkspaceState.addWordSelection(id: String, selection: TextSelection): WorkspaceState {
    val page = selectedPage?.takeIf { it.editable } ?: return this
    val container = page.document.textIndex[id] ?: return this
    val buffer = RichTextBuffer(container.blocks)
    val range = selection.clamped(buffer.text.length).let { TextSelection(it.min, it.max) }
    if (range.collapsed || buffer.text.substring(range.min, range.max).isBlank()) return this
    val owner = page.document.selectionOwner(id) ?: return this
    val group = activeWordSelections?.takeIf { it.ownerId == owner }
    val seed = group?.ranges ?: listOfNotNull(focusedTextOutlineId?.takeIf {
        page.document.selectionOwner(it) == owner && !editorSelection.collapsed
    }?.let { SelectedTextRange(it, TextSelection(editorSelection.min, editorSelection.max)) })
    val target = SelectedTextRange(id, range)
    val ranges = if (group != null && target in seed) seed - target else {
        val overlapping = seed.filter { it.containerId == id && it.range.min < range.max && range.min < it.range.max }
        val merged = TextSelection((overlapping.map { it.range.min } + range.min).min(),
            (overlapping.map { it.range.max } + range.max).max())
        seed.filterNot { it in overlapping || it.range.collapsed } + SelectedTextRange(id, merged)
    }
    if (ranges.isEmpty()) return focusTextBox(id).selectText(TextSelection(range.max))
    val primary = ranges.last()
    return focusTextBox(primary.containerId).selectText(primary.range).copy(
        wordSelections = WordSelections(page.id, owner, ranges), typingRun = null,
    )
}

/** A captured ribbon/menu selection may activate one range without discarding the others. */
fun WorkspaceState.activateTextSelection(id: String, range: TextSelection): WorkspaceState {
    val group = activeWordSelections
    val target = SelectedTextRange(id, range)
    if (group != null && target in group.ranges) return copy(
        focusedTextOutlineId = id, editorSelection = range,
        wordSelections = group.copy(ranges = group.ranges.filterNot { it == target } + target),
        typingMarks = emptySet(), editorComposition = null,
    )
    return (if (focusedTextOutlineId == id) this else focusTextBox(id)).selectText(range)
}

internal fun WorkspaceState.restoreTextSelection(range: TextSelection?): WorkspaceState =
    if (range == null || range == editorSelection) this
    else focusedTextOutlineId?.let { activateTextSelection(it, range) } ?: this

internal fun WorkspaceState.restoreWordSelections(group: WordSelections?): WorkspaceState {
    if (group == null || group.pageId != selectedPageId || group.ranges.isEmpty() ||
        group.ranges.any { selectedPage?.document?.textIndex?.get(it.containerId) == null }) return this
    val primary = group.ranges.last()
    return copy(wordSelections = group, focusedTextOutlineId = primary.containerId,
        editorSelection = primary.range, typingMarks = emptySet(), editorComposition = null)
}

internal fun WorkspaceState.orderedTextRanges(): List<SelectedTextRange> {
    val group = activeWordSelections ?: return focusedTextOutlineId?.let {
        listOf(SelectedTextRange(it, editorSelection))
    }.orEmpty()
    val owner = selectedPage!!.document.outlines.first { it.id == group.ownerId }
    val ids = if (owner is Outline.Table) owner.contentCellIds() else listOf(owner.id)
    return group.ranges.sortedWith(compareBy({ ids.indexOf(it.containerId) }, { it.range.min }))
}

internal fun WorkspaceState.selectedTextFragments(): List<Block> = orderedTextRanges().flatMap { target ->
    val blocks = selectedPage?.document?.textIndex?.get(target.containerId)?.blocks ?: return@flatMap emptyList()
    RichTextBuffer(blocks).select(target.range).selectedFragment()
}

internal val WorkspaceState.selectedTextMarks: Set<Mark>
    get() {
        if (activeWordSelections == null) return richText?.activeMarks.orEmpty()
        val marks = orderedTextRanges().mapNotNull { target ->
            selectedPage?.document?.textIndex?.get(target.containerId)?.blocks?.let {
                RichTextBuffer(it).select(target.range).let { buffer ->
                    buffer.copy(typingMarks = target.typingMarks ?: buffer.typingMarks).activeMarks
                }
            }
        }
        return marks.firstOrNull()?.let { first -> marks.drop(1).fold(first) { common, next -> common intersect next } }.orEmpty()
    }

internal fun PageDoc.withTextBlocks(replacements: Map<String, List<Block>>): PageDoc = copy(outlines = outlines.map { outline ->
    when (outline) {
        is Outline.Text -> replacements[outline.id]?.let { outline.copy(blocks = it) } ?: outline
        is Outline.Table -> if (!outline.inkOnly && outline.lockGroup == null) outline.withCellBlocks(replacements) else outline
        else -> outline
    }
})

/** Applies each edit from right to left and records all affected cells as one text history step. */
internal fun WorkspaceState.editTextSelections(
    paragraphs: Boolean = false,
    command: (RichTextBuffer) -> RichTextBuffer,
): WorkspaceState {
    val group = activeWordSelections ?: return richText?.let(command)?.let(::withRichText) ?: this
    val page = selectedPage?.takeIf { it.editable } ?: return this
    val results = mutableMapOf<String, List<Block>>()
    val rangesAfter = mutableMapOf<SelectedTextRange, TextSelection>()
    val marksAfter = mutableMapOf<SelectedTextRange, Set<Mark>>()
    for ((id, targets) in group.ranges.groupBy { it.containerId }) {
        val before = page.document.textIndex[id]?.blocks ?: return this
        var buffer = RichTextBuffer(before)
        if (paragraphs) {
            var start = 0
            val starts = before.mapNotNull { block ->
                val at = start
                start += block.editorText.length + 1
                at.takeIf { targets.any { it.range.min <= start - 1 && it.range.max >= at } }
            }
            starts.forEach { at -> buffer = command(buffer.select(TextSelection(at))) }
            targets.forEach { rangesAfter[it] = it.range }
        } else {
            val deltas = mutableListOf<Pair<Int, Int>>()
            for (target in targets.sortedByDescending { it.range.min }) {
                val previousLength = buffer.text.length
                val selected = buffer.select(target.range).let {
                    it.copy(typingMarks = target.typingMarks ?: if (target.range.collapsed) it.typingMarks else it.activeMarks)
                }
                val changed = command(selected)
                deltas += target.range.min to (changed.text.length - previousLength)
                rangesAfter[target] = changed.selection
                marksAfter[target] = changed.typingMarks
                buffer = changed
            }
            targets.forEach { target ->
                val shift = deltas.filter { it.first < target.range.min }.sumOf { it.second }
                val range = rangesAfter.getValue(target)
                rangesAfter[target] = TextSelection(range.start + shift, range.end + shift)
            }
        }
        results[id] = buffer.blocks
    }
    val updatedGroup = group.copy(ranges = group.ranges.map { target ->
        val range = rangesAfter.getValue(target)
        target.copy(range = range, typingMarks = if (range.collapsed) marksAfter[target] ?: target.typingMarks else null)
    })
    val primary = updatedGroup.ranges.last()
    val changes = results.map { (id, blocks) -> TextHistoryChange(id,
        page.document.textIndex[id]!!.blocks, blocks,
        group.ranges.last { it.containerId == id }.range,
        updatedGroup.ranges.last { it.containerId == id }.range) }
    val primaryChange = changes.first { it.outlineId == primary.containerId }.copy(
        companions = changes.filter { it.outlineId != primary.containerId },
        wordsBefore = group, wordsAfter = updatedGroup,
    )
    val changed = changes.any { it.before != it.after }
    return updatePage(page.id) { it.withDocument(it.document.withTextBlocks(results)) }.copy(
        editorSelection = primary.range, wordSelections = updatedGroup,
        typingMarks = primary.typingMarks ?: RichTextBuffer(results.getValue(primary.containerId)).select(primary.range).typingMarks,
        editorComposition = null, typingRun = null,
        structuralUndo = if (changed) (structuralUndo + StructuralSnapshot(page.id, page.document, text = primaryChange)).takeLast(100) else structuralUndo,
        structuralRedo = if (changed) emptyList() else structuralRedo,
    )
}

internal fun WorkspaceState.editSelectedWords(text: String, selection: TextSelection): WorkspaceState? {
    if (activeWordSelections == null) return null
    val current = richText ?: return null
    if (current.text == text) return if (selection == editorSelection) this else selectText(selection)
    val range = editorSelection.clamped(current.text.length)
    val prefix = current.text.substring(0, range.min)
    val suffix = current.text.substring(range.max)
    if (text.length >= prefix.length + suffix.length && text.startsWith(prefix) && text.endsWith(suffix)) {
        val inserted = text.substring(prefix.length, text.length - suffix.length)
        return editTextSelections { it.replace(inserted) }
    }
    if (range.collapsed && selection.collapsed && text.length < current.text.length) {
        val count = current.text.length - text.length
        val backwards = selection.start < range.start
        return editTextSelections { buffer ->
            val caret = buffer.selection.start
            val removed = if (backwards) TextSelection((caret - count).coerceAtLeast(0), caret)
                else TextSelection(caret, (caret + count).coerceAtMost(buffer.text.length))
            buffer.select(removed).replace("")
        }
    }
    return null
}
