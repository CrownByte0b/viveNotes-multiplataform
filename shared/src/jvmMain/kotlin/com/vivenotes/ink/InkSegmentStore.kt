package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBounds
import java.util.TreeSet

internal class InkQueryWork {
    var segmentBoundsVisited = 0
    var nativeIndexQueries = 0
}

internal class InkLocationWork {
    var positionNodesVisited = 0
    var positionNodesCopied = 0
}

/** Ordered persistent AVL leaves: updates copy links, queries prune unions before native tests. */
internal class InkSegmentStore private constructor(private val root: SegmentNode?,
    private val rows: LocationNode?, private val groups: LocationNode?) : AbstractList<DesktopInkSegment>() {
    override val size: Int get() = root.count
    val bounds: InkBounds? get() = root?.bounds
    val emptySlots: Int get() = root?.emptySlots ?: 0

    override fun get(index: Int): DesktopInkSegment {
        require(index in indices)
        var node = checkNotNull(root)
        var remaining = index
        while (true) {
            val left = node.left.count
            when {
                remaining < left -> node = checkNotNull(node.left)
                remaining == left -> return node.segment
                else -> { remaining -= left + 1; node = checkNotNull(node.right) }
            }
        }
    }

    override fun iterator(): Iterator<DesktopInkSegment> = iterator {
        val stack = ArrayDeque<SegmentNode>()
        var node = root
        while (node != null || stack.isNotEmpty()) {
            while (node != null) { stack.addLast(node); node = node.left }
            val current = stack.removeLast()
            yield(current.segment)
            node = current.right
        }
    }

    fun append(segments: List<DesktopInkSegment>, work: InkLocationWork? = null): InkSegmentStore {
        var next = this
        for (segment in segments) {
            val index = next.size
            var rowIndex = next.rows
            for (id in segment.sourceIds) rowIndex = rowIndex.withLocation(id, index, true, work)
            var groupIndex = next.groups
            for (group in segment.groupIds) groupIndex = groupIndex.withLocation(group, index, true, work)
            next = InkSegmentStore(next.root.append(segment), rowIndex, groupIndex)
        }
        return next
    }

    fun replace(replacements: Map<Int, DesktopInkSegment>, work: InkLocationWork? = null): InkSegmentStore {
        var next = this
        for ((index, segment) in replacements) {
            val old = next[index]
            if (old === segment) continue
            var rowIndex = next.rows
            for (id in old.sourceIds) if (id !in segment.sourceIds) rowIndex = rowIndex.withLocation(id, index, false, work)
            for (id in segment.sourceIds) if (id !in old.sourceIds) rowIndex = rowIndex.withLocation(id, index, true, work)
            var groupIndex = next.groups
            for (id in old.groupIds) if (id !in segment.groupIds) groupIndex = groupIndex.withLocation(id, index, false, work)
            for (id in segment.groupIds) if (id !in old.groupIds) groupIndex = groupIndex.withLocation(id, index, true, work)
            next = InkSegmentStore(checkNotNull(next.root).replace(index, segment), rowIndex, groupIndex)
        }
        return next
    }

    fun containsSource(id: String): Boolean = rows.find(id) != null
    fun forRows(ids: Set<String>): List<Pair<Int, DesktopInkSegment>> = locations(rows, ids)
    fun forGroups(ids: Set<String>): List<Pair<Int, DesktopInkSegment>> = locations(groups, ids)
    private fun locations(index: LocationNode?, ids: Set<String>): List<Pair<Int, DesktopInkSegment>> {
        val found = TreeSet<Int>()
        ids.forEach { id -> index.find(id).forEachPosition { found.add(it) } }
        return found.map { it to get(it) }
    }

    fun near(box: InkBounds, work: InkQueryWork? = null): List<DesktopInkSegment> = buildList {
        fun visit(node: SegmentNode?) {
            if (node == null) return
            work?.let { it.segmentBoundsVisited++ }
            if (!node.bounds.meets(box)) return
            visit(node.left)
            if (node.segment.bounds.meets(box)) add(node.segment)
            visit(node.right)
        }
        visit(root)
    }

    companion object { val Empty = InkSegmentStore(null, null, null) }
}

private class SegmentNode(val left: SegmentNode?, val segment: DesktopInkSegment, val right: SegmentNode?) {
    val count: Int = left.count + 1 + right.count
    val height: Int = 1 + maxOf(left.height, right.height)
    val emptySlots: Int = (left?.emptySlots ?: 0) + (right?.emptySlots ?: 0) +
        if (segment.projections.isEmpty() && segment.sourceIds.isEmpty()) 1 else 0
    val bounds: InkBounds? = union(left?.bounds, union(segment.bounds, right?.bounds))
}
private val SegmentNode?.count: Int get() = this?.count ?: 0
private val SegmentNode?.height: Int get() = this?.height ?: 0
private fun SegmentNode?.append(segment: DesktopInkSegment): SegmentNode =
    if (this == null) SegmentNode(null, segment, null) else balance(SegmentNode(left, this.segment, right.append(segment)))
private fun SegmentNode.replace(index: Int, segment: DesktopInkSegment): SegmentNode = when {
    index < left.count -> SegmentNode(checkNotNull(left).replace(index, segment), this.segment, right)
    index == left.count -> SegmentNode(left, segment, right)
    else -> SegmentNode(left, this.segment, checkNotNull(right).replace(index - left.count - 1, segment))
}
private fun balance(node: SegmentNode): SegmentNode {
    if (node.right.height - node.left.height > 1) {
        val right = checkNotNull(node.right)
        if (right.left.height > right.right.height) {
            val middle = checkNotNull(right.left)
            return SegmentNode(SegmentNode(node.left, node.segment, middle.left), middle.segment,
                SegmentNode(middle.right, right.segment, right.right))
        }
        return SegmentNode(SegmentNode(node.left, node.segment, right.left), right.segment, right.right)
    }
    if (node.left.height - node.right.height > 1) {
        val left = checkNotNull(node.left)
        if (left.right.height > left.left.height) {
            val middle = checkNotNull(left.right)
            return SegmentNode(SegmentNode(left.left, left.segment, middle.left), middle.segment,
                SegmentNode(middle.right, node.segment, node.right))
        }
        return SegmentNode(left.left, left.segment, SegmentNode(left.right, node.segment, node.right))
    }
    return node
}

private class LocationNode(val key: String, val positions: PositionNode, val left: LocationNode?, val right: LocationNode?) {
    val height: Int = 1 + maxOf(left.locationHeight, right.locationHeight)
}
private val LocationNode?.locationHeight: Int get() = this?.height ?: 0
private fun LocationNode?.find(key: String): PositionNode? {
    var node = this
    while (node != null) {
        val comparison = key.compareTo(node.key)
        node = when {
            comparison < 0 -> node.left
            comparison > 0 -> node.right
            else -> return node.positions
        }
    }
    return null
}
private fun LocationNode?.withLocation(key: String, position: Int, add: Boolean, work: InkLocationWork?): LocationNode? {
    if (this == null) return if (add) LocationNode(key, PositionNode(position, null, null, work), null, null) else null
    val comparison = key.compareTo(this.key)
    val changed = when {
        comparison < 0 -> LocationNode(this.key, positions, left.withLocation(key, position, add, work), right)
        comparison > 0 -> LocationNode(this.key, positions, left, right.withLocation(key, position, add, work))
        else -> {
            val updated = positions.withPosition(position, add, work)
            return when {
                updated == null -> removeLocationRoot(this)
                updated === positions -> this
                else -> LocationNode(key, updated, left, right)
            }
        }
    }
    return balanceLocation(changed)
}
private fun removeLocationRoot(node: LocationNode): LocationNode? {
    if (node.left == null) return node.right
    if (node.right == null) return node.left
    var successor = node.right
    while (successor?.left != null) successor = successor.left
    val held = checkNotNull(successor)
    return balanceLocation(LocationNode(held.key, held.positions, node.left, removeFirstLocation(node.right)))
}
private fun removeFirstLocation(node: LocationNode): LocationNode? = if (node.left == null) node.right else
    balanceLocation(LocationNode(node.key, node.positions, removeFirstLocation(node.left), node.right))
private fun balanceLocation(node: LocationNode): LocationNode {
    if (node.right.locationHeight - node.left.locationHeight > 1) {
        val right = checkNotNull(node.right)
        if (right.left.locationHeight > right.right.locationHeight) {
            val middle = checkNotNull(right.left)
            return LocationNode(middle.key, middle.positions,
                LocationNode(node.key, node.positions, node.left, middle.left),
                LocationNode(right.key, right.positions, middle.right, right.right))
        }
        return LocationNode(right.key, right.positions, LocationNode(node.key, node.positions, node.left, right.left), right.right)
    }
    if (node.left.locationHeight - node.right.locationHeight > 1) {
        val left = checkNotNull(node.left)
        if (left.right.locationHeight > left.left.locationHeight) {
            val middle = checkNotNull(left.right)
            return LocationNode(middle.key, middle.positions,
                LocationNode(left.key, left.positions, left.left, middle.left),
                LocationNode(node.key, node.positions, middle.right, node.right))
        }
        return LocationNode(left.key, left.positions, left.left, LocationNode(node.key, node.positions, left.right, node.right))
    }
    return node
}

/** One shared group/fragment row can occupy every chunk; its positions must also persist by links. */
private class PositionNode(val position: Int, val left: PositionNode?, val right: PositionNode?, work: InkLocationWork?) {
    val height: Int = 1 + maxOf(left.positionHeight, right.positionHeight)
    init { work?.let { it.positionNodesCopied++ } }
}
private val PositionNode?.positionHeight: Int get() = this?.height ?: 0
private fun PositionNode?.withPosition(position: Int, add: Boolean, work: InkLocationWork?): PositionNode? {
    if (this == null) return if (add) PositionNode(position, null, null, work) else null
    work?.let { it.positionNodesVisited++ }
    val changed = when {
        position < this.position -> {
            val next = left.withPosition(position, add, work)
            if (next === left) return this
            PositionNode(this.position, next, right, work)
        }
        position > this.position -> {
            val next = right.withPosition(position, add, work)
            if (next === right) return this
            PositionNode(this.position, left, next, work)
        }
        else -> return if (add) this else removePositionRoot(this, work)
    }
    return balancePosition(changed, work)
}
private fun removePositionRoot(node: PositionNode, work: InkLocationWork?): PositionNode? {
    if (node.left == null) return node.right
    if (node.right == null) return node.left
    var successor: PositionNode? = node.right
    while (successor?.left != null) {
        work?.let { it.positionNodesVisited++ }
        successor = successor.left
    }
    val held = checkNotNull(successor)
    return balancePosition(PositionNode(held.position, node.left, removeFirstPosition(node.right, work), work), work)
}
private fun removeFirstPosition(node: PositionNode, work: InkLocationWork?): PositionNode? {
    work?.let { it.positionNodesVisited++ }
    return if (node.left == null) node.right else
        balancePosition(PositionNode(node.position, removeFirstPosition(node.left, work), node.right, work), work)
}
private fun balancePosition(node: PositionNode, work: InkLocationWork?): PositionNode {
    if (node.right.positionHeight - node.left.positionHeight > 1) {
        val right = checkNotNull(node.right)
        if (right.left.positionHeight > right.right.positionHeight) {
            val middle = checkNotNull(right.left)
            return PositionNode(middle.position,
                PositionNode(node.position, node.left, middle.left, work),
                PositionNode(right.position, middle.right, right.right, work), work)
        }
        return PositionNode(right.position, PositionNode(node.position, node.left, right.left, work), right.right, work)
    }
    if (node.left.positionHeight - node.right.positionHeight > 1) {
        val left = checkNotNull(node.left)
        if (left.right.positionHeight > left.left.positionHeight) {
            val middle = checkNotNull(left.right)
            return PositionNode(middle.position,
                PositionNode(left.position, left.left, middle.left, work),
                PositionNode(node.position, middle.right, node.right, work), work)
        }
        return PositionNode(left.position, left.left, PositionNode(node.position, left.right, node.right, work), work)
    }
    return node
}
private inline fun PositionNode?.forEachPosition(visit: (Int) -> Unit) {
    val pending = ArrayDeque<PositionNode>()
    var node = this
    while (node != null || pending.isNotEmpty()) {
        while (node != null) { pending.addLast(node); node = node.left }
        val current = pending.removeLast()
        visit(current.position)
        node = current.right
    }
}

internal fun InkBounds?.meets(other: InkBounds): Boolean = this != null &&
    left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

private fun union(first: InkBounds?, second: InkBounds?): InkBounds? = when {
    first == null -> second
    second == null -> first
    else -> InkBounds(minOf(first.left, second.left), minOf(first.top, second.top),
        maxOf(first.right, second.right), maxOf(first.bottom, second.bottom))
}
