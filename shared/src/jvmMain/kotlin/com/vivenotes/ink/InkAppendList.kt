package com.vivenotes.ink

private sealed class InkListNode<T>(val size: Int, val rank: Int)
private class InkListLeaf<T>(val values: List<T>) : InkListNode<T>(values.size, 0)
private class InkListBranch<T>(val left: InkListNode<T>, val right: InkListNode<T>) :
    InkListNode<T>(left.size + right.size, left.rank + 1)

/** Immutable binary chunk forest: append copies logarithmically many links, never the large base. */
private class InkAppendList<T>(private val chunks: List<InkListNode<T>>, override val size: Int) : AbstractList<T>() {
    fun append(values: List<T>): InkAppendList<T> {
        val updated = chunks.toMutableList()
        var next: InkListNode<T> = InkListLeaf(values.toList())
        while (updated.lastOrNull()?.rank == next.rank) next = InkListBranch(updated.removeAt(updated.lastIndex), next)
        updated += next
        return InkAppendList(updated, size + values.size)
    }

    override fun get(index: Int): T {
        if (index !in indices) throw IndexOutOfBoundsException("$index outside $size ink values")
        var remaining = index
        for (chunk in chunks) {
            if (remaining >= chunk.size) { remaining -= chunk.size; continue }
            var node = chunk
            while (node is InkListBranch) {
                if (remaining < node.left.size) node = node.left else {
                    remaining -= node.left.size
                    node = node.right
                }
            }
            return (node as InkListLeaf).values[remaining]
        }
        error("Missing ink value")
    }

    override fun iterator(): Iterator<T> = iterator {
        val pending = ArrayDeque<InkListNode<T>>()
        for (index in chunks.indices.reversed()) pending.addLast(chunks[index])
        while (pending.isNotEmpty()) when (val node = pending.removeLast()) {
            is InkListLeaf -> yieldAll(node.values)
            is InkListBranch -> { pending.addLast(node.right); pending.addLast(node.left) }
        }
    }

    companion object {
        fun <T> of(base: List<T>): InkAppendList<T> =
            if (base is InkAppendList) base else InkAppendList(if (base.isEmpty()) emptyList() else listOf(InkListLeaf(base)), base.size)
    }
}

internal fun <T> appendInkValues(base: List<T>, tail: List<T>): List<T> =
    if (tail.isEmpty()) base else InkAppendList.of(base).append(tail)
