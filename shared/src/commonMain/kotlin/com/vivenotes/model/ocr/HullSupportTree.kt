package com.vivenotes.model.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Conservative support bounds keep the original Float products and strict rectangle-area ties. */
internal class HullSupportTree(
    private val points: List<TextDetection.Point>,
    private val recordPoints: ((Int) -> Unit)? = null,
) {
    private val leaves: Int = run {
        var size = 1
        while (size * LEAF_SIZE < points.size) size *= 2
        size
    }
    private val left = FloatArray(leaves * 2) { Float.POSITIVE_INFINITY }
    private val right = FloatArray(leaves * 2) { Float.NEGATIVE_INFINITY }
    private val top = FloatArray(leaves * 2) { Float.POSITIVE_INFINITY }
    private val bottom = FloatArray(leaves * 2) { Float.NEGATIVE_INFINITY }
    var minAlong = Float.MAX_VALUE
        private set
    var maxAlong = -Float.MAX_VALUE
        private set
    var minAcross = Float.MAX_VALUE
        private set
    var maxAcross = -Float.MAX_VALUE
        private set
    private var minAlongAt = 0
    private var maxAlongAt = 0
    private var minAcrossAt = 0
    private var maxAcrossAt = 0

    init {
        require(points.isNotEmpty())
        for (index in points.indices) {
            val point = points[index]
            val node = leaves + index / LEAF_SIZE
            left[node] = min(left[node], point.x)
            right[node] = max(right[node], point.x)
            top[node] = min(top[node], point.y)
            bottom[node] = max(bottom[node], point.y)
        }
        for (node in leaves - 1 downTo 1) {
            left[node] = min(left[node * 2], left[node * 2 + 1])
            right[node] = max(right[node * 2], right[node * 2 + 1])
            top[node] = min(top[node * 2], top[node * 2 + 1])
            bottom[node] = max(bottom[node * 2], bottom[node * 2 + 1])
        }
    }

    fun project(ux: Float, uy: Float) {
        val a = minAlongAt
        val b = maxAlongAt
        val c = minAcrossAt
        val d = maxAcrossAt
        minAlong = Float.MAX_VALUE
        maxAlong = -Float.MAX_VALUE
        minAcross = Float.MAX_VALUE
        maxAcross = -Float.MAX_VALUE
        projectPoint(a, ux, uy)
        var seeds = 1
        if (b != a) { projectPoint(b, ux, uy); seeds++ }
        if (c != a && c != b) { projectPoint(c, ux, uy); seeds++ }
        if (d != a && d != b && d != c) { projectPoint(d, ux, uy); seeds++ }
        recordPoints?.invoke(seeds)
        visit(1, ux, uy)
    }

    private fun visit(node: Int, ux: Float, uy: Float) {
        if (left[node] == Float.POSITIVE_INFINITY) return
        val x0 = left[node] * ux
        val x1 = right[node] * ux
        val y0 = top[node] * uy
        val y1 = bottom[node] * uy
        val a0 = -left[node] * uy
        val a1 = -right[node] * uy
        val b0 = top[node] * ux
        val b1 = bottom[node] * ux
        // Float multiplication/addition are monotone on these finite bounds. Total comparisons
        // keep -0 below +0, just like min/max in the exhaustive support scan.
        if ((min(x0, x1) + min(y0, y1)).compareTo(minAlong) >= 0 &&
            (max(x0, x1) + max(y0, y1)).compareTo(maxAlong) <= 0 &&
            (min(a0, a1) + min(b0, b1)).compareTo(minAcross) >= 0 &&
            (max(a0, a1) + max(b0, b1)).compareTo(maxAcross) <= 0) return
        if (node >= leaves) {
            val start = (node - leaves) * LEAF_SIZE
            val end = min(start + LEAF_SIZE, points.size)
            for (index in start until end) projectPoint(index, ux, uy)
            recordPoints?.invoke(end - start)
        } else {
            visit(node * 2, ux, uy)
            visit(node * 2 + 1, ux, uy)
        }
    }

    private fun projectPoint(index: Int, ux: Float, uy: Float) {
        val point = points[index]
        val along = point.x * ux + point.y * uy
        val across = -point.x * uy + point.y * ux
        if (along.compareTo(minAlong) < 0) { minAlong = along; minAlongAt = index }
        if (along.compareTo(maxAlong) > 0) { maxAlong = along; maxAlongAt = index }
        if (across.compareTo(minAcross) < 0) { minAcross = across; minAcrossAt = index }
        if (across.compareTo(maxAcross) > 0) { maxAcross = across; maxAcrossAt = index }
    }

    companion object {
        private const val LEAF_SIZE = 4
        const val MIN_HULL_SIZE = 128
        fun supports(points: List<TextDetection.Point>): Boolean = points.size >= MIN_HULL_SIZE &&
            points.all { abs(it.x) <= Float.MAX_VALUE / 8f && abs(it.y) <= Float.MAX_VALUE / 8f }
    }
}
