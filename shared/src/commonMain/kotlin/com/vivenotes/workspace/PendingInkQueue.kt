package com.vivenotes.workspace

import com.vivenotes.data.PendingInkEdit

/** Immutable two-stack FIFO: old workspace/undo snapshots retain their exact commands. */
private class PendingInkQueue private constructor(
    private val front: Link?,
    private val rear: Link?,
    override val size: Int,
) : AbstractList<PendingInkEdit>() {
    private class Link(val value: PendingInkEdit, val next: Link?)

    fun append(edit: PendingInkEdit): PendingInkQueue = PendingInkQueue(front, Link(edit, rear), size + 1)

    fun removeFirst(): List<PendingInkEdit> {
        check(size > 0)
        if (front != null) return PendingInkQueue(front.next, rear, size - 1)
        var source = rear
        var reversed: Link? = null
        while (source != null) {
            reversed = Link(source.value, reversed)
            source = source.next
        }
        return PendingInkQueue(checkNotNull(reversed).next, null, size - 1)
    }

    override fun get(index: Int): PendingInkEdit {
        if (index !in indices) throw IndexOutOfBoundsException("$index outside $size queued edits")
        var cursor = front
        var offset = index
        while (cursor != null) {
            if (offset-- == 0) return cursor.value
            cursor = cursor.next
        }
        cursor = rear
        repeat(size - index - 1) { cursor = checkNotNull(cursor).next }
        return checkNotNull(cursor).value
    }

    override fun iterator(): Iterator<PendingInkEdit> = iterator {
        var cursor = front
        while (cursor != null) {
            yield(cursor.value)
            cursor = cursor.next
        }
        val tail = ArrayList<PendingInkEdit>()
        cursor = rear
        while (cursor != null) {
            tail += cursor.value
            cursor = cursor.next
        }
        for (index in tail.lastIndex downTo 0) yield(tail[index])
    }

    companion object {
        fun of(edits: List<PendingInkEdit>): PendingInkQueue {
            if (edits is PendingInkQueue) return edits
            var queue = PendingInkQueue(null, null, 0)
            for (edit in edits) queue = queue.append(edit)
            return queue
        }
    }
}

internal fun List<PendingInkEdit>.queueInk(edit: PendingInkEdit): List<PendingInkEdit> =
    PendingInkQueue.of(this).append(edit)

internal fun List<PendingInkEdit>.queueInk(edits: List<PendingInkEdit>): List<PendingInkEdit> {
    if (edits.isEmpty()) return this
    var queue = PendingInkQueue.of(this)
    for (edit in edits) queue = queue.append(edit)
    return queue
}

/** Only the acknowledged prefix is removed; later commands can arrive while its write suspends. */
internal fun List<PendingInkEdit>.acknowledgeFirstInk(id: String): List<PendingInkEdit> {
    check(firstOrNull()?.id == id) { "Ink acknowledgement must match the queued prefix" }
    return PendingInkQueue.of(this).removeFirst()
}
