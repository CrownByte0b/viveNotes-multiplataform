package com.vivenotes.model.ocr

import kotlin.math.max
import kotlin.math.min

/** Reference rows stay in creation order, so the first surviving candidate keeps stable row ties. */
internal class ReadingRowCandidates(capacity: Int) {
    private val leaves: Int = run {
        var size = 1
        while (size < capacity) size *= 2
        size
    }
    private val tops = FloatArray(leaves * 2) { Float.NEGATIVE_INFINITY }
    private val heights = FloatArray(leaves * 2) { Float.NEGATIVE_INFINITY }
    private var count = 0

    fun add(top: Float, height: Float) {
        require(top.isFinite() && count < leaves)
        var node = leaves + count++
        tops[node] = top
        // A NaN height never satisfies the original comparison, and need not retain candidates.
        heights[node] = if (height.isNaN()) Float.NEGATIVE_INFINITY else height
        while (node > 1) {
            node /= 2
            tops[node] = max(tops[node * 2], tops[node * 2 + 1])
            heights[node] = max(heights[node * 2], heights[node * 2 + 1])
        }
    }

    fun first(top: Float, height: Float, matches: (Int) -> Boolean): Int? {
        if (height.isNaN()) return null
        return find(1, top, height, matches)
    }

    private fun find(node: Int, top: Float, height: Float, matches: (Int) -> Boolean): Int? {
        if (heights[node] == Float.NEGATIVE_INFINITY) return null
        // This bounds the original subtraction itself; adding a rounded expiry could drop a tie.
        if (top - tops[node] > min(heights[node], height) * .5f) return null
        if (node >= leaves) return (node - leaves).takeIf(matches)
        return find(node * 2, top, height, matches) ?: find(node * 2 + 1, top, height, matches)
    }
}
