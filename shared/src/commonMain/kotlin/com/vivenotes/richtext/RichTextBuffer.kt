package com.vivenotes.richtext

import com.vivenotes.model.Align
import com.vivenotes.model.Block
import com.vivenotes.model.BlockType
import com.vivenotes.model.Mark
import com.vivenotes.model.Run
import com.vivenotes.model.newId
import com.vivenotes.model.opposingScript

/** UTF-16 offsets into [RichTextBuffer.text], matching Compose and Android editor selections. */
data class TextSelection(val start: Int, val end: Int = start) {
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val collapsed: Boolean get() = start == end

    fun clamped(length: Int): TextSelection =
        TextSelection(start.coerceIn(0, length), end.coerceIn(0, length))
}

/**
 * The portable editing boundary between a native text input and Android-compatible blocks/runs.
 * A newline separates blocks; it is not stored in a run. Unchanged blocks keep their IDs.
 */
data class RichTextBuffer(
    val blocks: List<Block>,
    val selection: TextSelection = TextSelection(0),
    val typingMarks: Set<Mark> = emptySet(),
) {
    init { require(blocks.isNotEmpty()) }

    val text: String get() = blocks.joinToString("\n") { it.editorText }

    val currentBlock: Block get() = blocks[locate(selection.clamped(text.length).min).index]

    fun select(range: TextSelection): RichTextBuffer {
        val next = range.clamped(text.length)
        return copy(selection = next, typingMarks = if (next.collapsed) marksForCaret(next.start) else emptySet())
    }

    /** The marks common to every selected character, or the armed marks at the caret. */
    val activeMarks: Set<Mark>
        get() {
            val range = selection.clamped(text.length)
            if (range.collapsed) return typingMarks
            val involved = selectedRunPieces(range.min, range.max)
            return involved.firstOrNull()?.let { first ->
                involved.drop(1).fold(first) { common, marks -> common intersect marks }
            }.orEmpty()
        }

    fun toggleMark(mark: Mark): RichTextBuffer {
        val range = selection.clamped(text.length)
        val shouldAdd = mark !in activeMarks
        if (range.collapsed) {
            return copy(typingMarks = typingMarks.withMark(mark, shouldAdd))
        }
        return changeMarks(range.min, range.max) { it.withMark(mark, shouldAdd) }
    }

    fun setMark(mark: Mark): RichTextBuffer {
        val range = selection.clamped(text.length)
        if (range.collapsed) return copy(typingMarks = typingMarks.withMark(mark, true))
        return changeMarks(range.min, range.max) { it.withMark(mark, true) }
    }

    fun clearMark(mark: Mark): RichTextBuffer {
        val range = selection.clamped(text.length)
        if (range.collapsed) return copy(typingMarks = typingMarks.withMark(mark, false))
        return changeMarks(range.min, range.max) { it.withMark(mark, false) }
    }

    /** Android's Clear formatting: inline marks go, and so do the paragraphs' list, indent and alignment. */
    fun clearFormatting(): RichTextBuffer {
        val range = selection.clamped(text.length)
        val plainBlocks = changeBlocks {
            it.copy(type = BlockType.Paragraph, indent = 0, align = Align.Start, checked = null)
        }
        if (range.collapsed) return plainBlocks.copy(typingMarks = emptySet())
        return plainBlocks.changeMarks(range.min, range.max) { marks ->
            marks.filterTo(mutableSetOf()) { it is Mark.Equation || it is Mark.Link }
        }
    }

    /**
     * Android's `SetBlockType`: choosing the style a paragraph already has returns it to a plain
     * paragraph, which is how a list is left. A to-do keeps its tick; any other type has none.
     */
    fun setBlockType(type: BlockType): RichTextBuffer = changeBlocks {
        val next = if (it.type == type) BlockType.Paragraph else type
        it.copy(type = next, checked = if (next == BlockType.Todo) it.checked ?: false else null)
    }

    /** Ticks or unticks the to-do [blockId]; any other block is left alone. */
    fun toggleChecked(blockId: String): RichTextBuffer = copy(blocks = blocks.map { block ->
        if (block.id == blockId && block.type == BlockType.Todo) block.copy(checked = block.checked != true)
        else block
    })

    /**
     * What the link panel opens with: the selected text, or the whole link the caret is in, with
     * the address it already has.
     */
    val linkTarget: LinkTarget
        get() {
            val range = selection.clamped(text.length)
            if (!range.collapsed) {
                return LinkTarget(text.substring(range.min, range.max),
                    activeMarks.filterIsInstance<Mark.Link>().firstOrNull()?.href)
            }
            val link = linkAt(range.min) ?: return LinkTarget("", null)
            return LinkTarget(text.substring(link.start, link.end), link.href)
        }

    /** The address of the link covering the character at [offset], for opening it. */
    fun linkUrlAt(offset: Int): String? {
        if (offset !in 0 until text.length) return null
        return selectedRunPieces(offset, offset + 1).firstOrNull()
            ?.filterIsInstance<Mark.Link>()?.firstOrNull()?.href
    }

    /**
     * Android's `InsertLink`: links the selection as [label], or changes the link the caret is in,
     * or inserts [label] linked at the caret. The caret ends after the link.
     */
    fun insertLink(label: String, url: String): RichTextBuffer {
        val shown = label.replace('\n', ' ')
        if (shown.isEmpty()) return this
        val range = selection.clamped(text.length)
        val target = if (range.collapsed) linkAt(range.min)?.let { TextSelection(it.start, it.end) } ?: range
            else range
        val relabelled = if (text.substring(target.min, target.max) == shown) this else {
            val marks = if (target.collapsed) typingMarks else marksOfCharacterAt(target.min)
            copy(selection = target, typingMarks = marks.filterNotTo(mutableSetOf()) {
                it is Mark.Link || it is Mark.Equation
            }).replace(shown)
        }
        val end = target.min + shown.length
        return relabelled.changeMarks(target.min, end) { it.withMark(Mark.Link(url), true) }
            .select(TextSelection(end))
    }

    fun setAlign(align: Align): RichTextBuffer = changeBlocks { it.copy(align = align) }

    fun indent(delta: Int): RichTextBuffer = changeBlocks {
        it.copy(indent = (it.indent + delta).coerceIn(0, 8))
    }

    /** Replace a selection, preserving run marks and unaffected block identities. */
    fun replace(inserted: String): RichTextBuffer {
        val range = selection.clamped(text.length)
        val from = locate(range.min)
        val to = locate(range.max)
        val first = blocks[from.index]
        val last = blocks[to.index]
        val prefix = first.runs.splitAt(from.offset).first
        val suffix = last.runs.splitAt(to.offset).second
        val lines = inserted.split('\n')
        val insertedRuns = lines.map { line ->
            if (line.isEmpty()) emptyList() else listOf(Run(line, typingMarks))
        }
        // Android's `SpannableCodec.normalize`: a paragraph split off continues the one it came
        // from, which is what makes Enter in a list make another item. A new to-do starts unticked.
        fun continued(): Block = first.copy(id = newId(), checked = first.checked?.let { false })
        val replacement = if (lines.size == 1) {
            listOf(first.copy(runs = mergeRuns(prefix + insertedRuns[0] + suffix)))
        } else {
            buildList {
                add(first.copy(runs = mergeRuns(prefix + insertedRuns.first())))
                lines.subList(1, lines.lastIndex).forEachIndexed { index, _ ->
                    add(continued().copy(runs = insertedRuns[index + 1]))
                }
                val tail = if (from.index == to.index) continued() else last
                add(tail.copy(runs = mergeRuns(insertedRuns.last() + suffix)))
            }
        }
        val changed = blocks.take(from.index) + replacement + blocks.drop(to.index + 1)
        val caret = range.min + inserted.length
        return copy(blocks = changed, selection = TextSelection(caret))
    }

    /**
     * The selection with its formatting: every block it touches, cut down to the selected part, with
     * its runs' marks and its own paragraph style. Empty when nothing is selected.
     */
    fun selectedFragment(): List<Block> {
        val range = selection.clamped(text.length)
        if (range.collapsed) return emptyList()
        val from = locate(range.min)
        val to = locate(range.max)
        return (from.index..to.index).map { index ->
            val block = blocks[index]
            val start = if (index == from.index) from.offset else 0
            val end = if (index == to.index) to.offset else block.editorText.length
            block.copy(runs = block.runs.splitAt(end).first.splitAt(start).second)
        }
    }

    /**
     * Replaces the selection with a [selectedFragment], marks and all. A piece of one paragraph joins
     * the paragraph at the caret and takes its style. Pasted paragraphs keep their own style, except
     * that one sharing a line with text already there — the first, after the text before the caret;
     * the last, before the text after it — takes that paragraph's. Pasted blocks get new ids.
     */
    fun insertFragment(fragment: List<Block>): RichTextBuffer {
        if (fragment.isEmpty()) return this
        val range = selection.clamped(text.length)
        val from = locate(range.min)
        val to = locate(range.max)
        val first = blocks[from.index]
        val last = blocks[to.index]
        val prefix = first.runs.splitAt(from.offset).first
        val suffix = last.runs.splitAt(to.offset).second
        val replacement = if (fragment.size == 1) {
            listOf(first.copy(runs = mergeRuns(prefix + fragment.single().runs + suffix)))
        } else {
            buildList {
                val head = fragment.first()
                add((if (prefix.isEmpty()) first.styledAs(head) else first).copy(runs = mergeRuns(prefix + head.runs)))
                fragment.subList(1, fragment.lastIndex).forEach { add(it.copy(id = newId())) }
                val tail = fragment.last()
                // As in [replace]: the paragraph split at the caret continues past the pasted ones.
                val after = if (from.index == to.index) first.copy(id = newId(), checked = first.checked?.let { false })
                    else last
                add((if (suffix.isEmpty()) after.styledAs(tail) else after).copy(runs = mergeRuns(tail.runs + suffix)))
            }
        }
        val changed = blocks.take(from.index) + replacement + blocks.drop(to.index + 1)
        val insertedLength = fragment.sumOf { it.editorText.length } + fragment.lastIndex
        // Typing on continues in the formatting just pasted, as it would after that text was typed.
        return copy(blocks = changed).select(TextSelection(range.min + insertedLength))
    }

    /** Apply the smallest contiguous text change reported by a platform input field. */
    fun acceptTextChange(newText: String, newSelection: TextSelection): RichTextBuffer {
        val oldText = text
        if (newText == oldText) {
            return if (newSelection == selection) this else select(newSelection)
        }
        val oldRange = selection.clamped(oldText.length)
        // Identical surrounding text makes a global prefix/suffix diff ambiguous (typing into
        // "aaaa" is the common case). Prefer the editor's old selection as the edit anchor.
        if (newText.startsWith(oldText.substring(0, oldRange.min)) &&
            newText.endsWith(oldText.substring(oldRange.max)) &&
            newText.length >= oldText.length - (oldRange.max - oldRange.min)
        ) {
            val insertedEnd = newText.length - (oldText.length - oldRange.max)
            val inserted = newText.substring(oldRange.min, insertedEnd)
            return copy(selection = oldRange).replace(inserted)
                .copy(selection = newSelection.clamped(newText.length))
        }
        if (oldRange.collapsed && newSelection.collapsed && newText.length < oldText.length) {
            val removed = oldText.length - newText.length
            val candidateStart = if (newSelection.start < oldRange.start) {
                newSelection.start
            } else oldRange.start
            val candidateEnd = candidateStart + removed
            if (candidateStart >= 0 && candidateEnd <= oldText.length &&
                oldText.removeRange(candidateStart, candidateEnd) == newText
            ) {
                return copy(selection = TextSelection(candidateStart, candidateEnd))
                    .replace("").copy(selection = newSelection.clamped(newText.length))
            }
        }
        val prefix = oldText.commonPrefixWith(newText).length
        val oldTail = oldText.substring(prefix)
        val newTail = newText.substring(prefix)
        val suffix = oldTail.commonSuffixWith(newTail).length
        val removedEnd = oldText.length - suffix
        val inserted = newText.substring(prefix, newText.length - suffix)
        val prepared = copy(selection = TextSelection(prefix, removedEnd))
        return prepared.replace(inserted).copy(selection = newSelection.clamped(newText.length))
    }

    private fun changeBlocks(transform: (Block) -> Block): RichTextBuffer {
        val range = selection.clamped(text.length)
        val first = locate(range.min).index
        val last = locate(range.max).index
        return copy(blocks = blocks.mapIndexed { index, block ->
            if (index in first..last) transform(block) else block
        })
    }

    private fun changeMarks(
        start: Int,
        end: Int,
        transform: (Set<Mark>) -> Set<Mark>,
    ): RichTextBuffer {
        var blockStart = 0
        val updated = blocks.map { block ->
            var runStart = blockStart
            val runs = block.runs.flatMap { run ->
                val runEnd = runStart + run.editorText.length
                val overlapStart = maxOf(start, runStart)
                val overlapEnd = minOf(end, runEnd)
                val pieces = if (overlapStart < overlapEnd && run.marks.none { it is Mark.Equation }) {
                    buildList {
                        if (overlapStart > runStart) add(run.copy(text = run.text.substring(0, overlapStart - runStart)))
                        add(run.copy(
                            text = run.text.substring(overlapStart - runStart, overlapEnd - runStart),
                            marks = transform(run.marks),
                        ))
                        if (overlapEnd < runEnd) add(run.copy(text = run.text.substring(overlapEnd - runStart)))
                    }
                } else listOf(run)
                runStart = runEnd
                pieces
            }
            blockStart += block.editorText.length + 1
            block.copy(runs = mergeRuns(runs))
        }
        return copy(blocks = updated)
    }

    private fun selectedRunPieces(start: Int, end: Int): List<Set<Mark>> {
        var blockStart = 0
        return buildList {
            blocks.forEach { block ->
                var runStart = blockStart
                block.runs.forEach { run ->
                    val runEnd = runStart + run.editorText.length
                    if (maxOf(start, runStart) < minOf(end, runEnd)) add(run.marks)
                    runStart = runEnd
                }
                blockStart += block.editorText.length + 1
            }
        }
    }

    /** The marks of the character that starts at [offset], rather than of the one before it. */
    private fun marksOfCharacterAt(offset: Int): Set<Mark> =
        selectedRunPieces(offset, offset + 1).firstOrNull() ?: marksAt(offset)

    data class LinkSpan(val start: Int, val end: Int, val href: String)

    /**
     * The link touching [offset], either end included, as Android's `linkAtCaret` finds it. Adjacent
     * runs to the same address are one link, even when other formatting splits them.
     */
    private fun linkAt(offset: Int): LinkSpan? = linkSpans().firstOrNull { offset in it.start..it.end }

    /** Contiguous linked text, even when other formatting splits its runs. */
    fun linkSpans(): List<LinkSpan> = buildList {
        var blockStart = 0
        blocks.forEach { block ->
            var runStart = blockStart
            var open: LinkSpan? = null
            block.runs.forEach { run ->
                val runEnd = runStart + run.editorText.length
                val href = run.marks.filterIsInstance<Mark.Link>().firstOrNull()?.href
                val current = open
                open = when {
                    href == null -> null
                    current?.href == href -> current.copy(end = runEnd)
                    else -> LinkSpan(runStart, runEnd, href)
                }
                if (current != null && open?.start != current.start) add(current)
                runStart = runEnd
            }
            open?.let(::add)
            blockStart += block.editorText.length + 1
        }
    }

    private fun marksAt(offset: Int): Set<Mark> {
        val position = locate(offset)
        val runs = blocks[position.index].runs
        var cursor = 0
        runs.forEach { run ->
            cursor += run.editorText.length
            if (position.offset <= cursor) return run.marks
        }
        return runs.lastOrNull()?.marks.orEmpty()
    }

    /** A link ends at its edge; typing there keeps other formatting without growing the link. */
    private fun marksForCaret(offset: Int): Set<Mark> {
        val marks = marksAt(offset)
        val link = marks.filterIsInstance<Mark.Link>().firstOrNull() ?: return marks
        return if (linkSpans().any { it.href == link.href && (offset == it.start || offset == it.end) })
            marks - link else marks
    }

    private data class Position(val index: Int, val offset: Int)

    private fun locate(offset: Int): Position {
        var remaining = offset.coerceIn(0, text.length)
        blocks.forEachIndexed { index, block ->
            val length = block.editorText.length
            if (remaining <= length || index == blocks.lastIndex) {
                return Position(index, remaining.coerceAtMost(length))
            }
            remaining -= length + 1
        }
        error("Rich text buffer must contain a block")
    }
}

/** Text and address for the link panel; [url] is null when there is no link to edit. */
data class LinkTarget(val text: String, val url: String?)

private fun Set<Mark>.withMark(mark: Mark, add: Boolean): Set<Mark> {
    val retained = filterTo(mutableSetOf()) { existing ->
        existing::class != mark::class
    }
    if (add) {
        retained.remove(mark.opposingScript())
        retained.add(mark)
    }
    return retained
}

/** This block, with [source]'s paragraph style. */
private fun Block.styledAs(source: Block): Block =
    copy(type = source.type, indent = source.indent, align = source.align, checked = source.checked)

private data class RunSplit(val first: List<Run>, val second: List<Run>)

private fun List<Run>.splitAt(offset: Int): RunSplit {
    val first = mutableListOf<Run>()
    val second = mutableListOf<Run>()
    var cursor = 0
    for (run in this) {
        val length = run.editorText.length
        val split = (offset - cursor).coerceIn(0, length)
        if (split == 0) second += run
        else if (split == length) first += run
        else {
            first += run.copy(text = run.text.substring(0, split))
            second += run.copy(text = run.text.substring(split))
        }
        cursor += length
    }
    return RunSplit(first, second)
}

private fun mergeRuns(runs: List<Run>): List<Run> = buildList {
    for (run in runs) {
        if (run.text.isEmpty()) continue
        val previous = lastOrNull()
        if (previous != null && previous.marks == run.marks && previous.marks.none { it is Mark.Equation }) {
            this[lastIndex] = previous.copy(text = previous.text + run.text)
        } else add(run)
    }
}
