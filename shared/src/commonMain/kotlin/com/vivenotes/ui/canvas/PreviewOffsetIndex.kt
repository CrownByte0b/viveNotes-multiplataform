package com.vivenotes.ui.canvas

/** Source/display UTF-16 coordinates for ordered, non-overlapping inline replacements. */
internal class PreviewOffsetIndex(ranges: List<IntRange>) {
    private val starts = IntArray(ranges.size)
    private val ends = IntArray(ranges.size)
    private val displayed = IntArray(ranges.size)
    private val delta = IntArray(ranges.size + 1)
    private val empty = IntArray(ranges.size + 1)

    init {
        ranges.forEachIndexed { index, range ->
            val end = range.last + 1
            require(end >= range.first && (index == 0 || range.first >= ends[index - 1]))
            starts[index] = range.first
            ends[index] = end
            displayed[index] = range.first + delta[index]
            delta[index + 1] = delta[index] + 1 - (end - range.first)
            empty[index + 1] = empty[index] + if (range.isEmpty()) 1 else 0
        }
    }

    fun toDisplay(offset: Int): Int {
        val index = starts.lowerBound(offset) - 1
        if (index < 0) return offset
        return if (offset < ends[index]) starts[index] + delta[index] + 1 else offset + delta[index + 1]
    }

    fun toSource(offset: Int): Int {
        val index = displayed.lowerBound(offset) - 1
        if (index < 0) return offset
        return if (offset == displayed[index] + 1) ends[index] else offset - delta[index + 1]
    }

    fun insertionsAt(offset: Int): Int {
        val first = starts.lowerBound(offset)
        val last = starts.upperBound(offset)
        return empty[last] - empty[first]
    }
}

/** Coverage before link glyphs are appended; overlapping formula/video candidates are allowed. */
internal class PreviewReplacementCoverage(ranges: List<IntRange>) {
    private val ordered = ranges.filterNot { it.isEmpty() }.sortedBy { it.first }
    private val starts = IntArray(ordered.size) { ordered[it].first }
    private val farthest = IntArray(ordered.size)
    init {
        ordered.forEachIndexed { index, range -> farthest[index] = maxOf(range.last + 1,
            farthest.getOrElse(index - 1) { Int.MIN_VALUE }) }
    }
    fun coversLinkEnd(offset: Int): Boolean {
        val index = starts.lowerBound(offset) - 1
        return index >= 0 && farthest[index] >= offset
    }
}

private fun IntArray.lowerBound(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < value) low = middle + 1 else high = middle
    }
    return low
}

private fun IntArray.upperBound(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] <= value) low = middle + 1 else high = middle
    }
    return low
}
