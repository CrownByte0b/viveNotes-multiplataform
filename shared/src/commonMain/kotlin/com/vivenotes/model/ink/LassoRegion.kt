package com.vivenotes.model.ink

internal expect fun inkLassoCloses(path: List<InkSample>): Boolean

internal fun List<InkSample>.encloses(bounds: InkSelectionBounds): Boolean = PreparedLassoRegion(this).encloses(bounds)

/** Edge arithmetic depends only on the immutable gesture, not on each outline corner. */
internal class PreparedLassoRegion(path: List<InkSample>) {
    private val x = FloatArray(path.size) { path[it].x }
    private val y = FloatArray(path.size) { path[it].y }
    private val previousX = FloatArray(path.size) { path[if (it == 0) path.lastIndex else it - 1].x }
    private val previousY = FloatArray(path.size) { path[if (it == 0) path.lastIndex else it - 1].y }
    private val dx = FloatArray(path.size) { x[it] - previousX[it] }
    private val dy = FloatArray(path.size) { y[it] - previousY[it] }
    private val length = FloatArray(path.size) { dx[it] * dx[it] + dy[it] * dy[it] }

    fun encloses(bounds: InkSelectionBounds): Boolean = x.size >= 3 &&
        contains(bounds.left, bounds.top) && contains(bounds.right, bounds.top) &&
        contains(bounds.right, bounds.bottom) && contains(bounds.left, bounds.bottom)

    private fun contains(atX: Float, atY: Float): Boolean {
        var inside = false
        for (index in x.indices) {
            val beforeX = previousX[index]
            val beforeY = previousY[index]
            val stepX = dx[index]
            val stepY = dy[index]
            val fraction = if (length[index] == 0f) 0f else
                ((atX - beforeX) * stepX + (atY - beforeY) * stepY) / length[index]
            val nearestX = beforeX + fraction.coerceIn(0f, 1f) * stepX
            val nearestY = beforeY + fraction.coerceIn(0f, 1f) * stepY
            if ((atX - nearestX) * (atX - nearestX) + (atY - nearestY) * (atY - nearestY) <= 16f) return true
            if ((y[index] > atY) != (beforeY > atY) &&
                atX < (beforeX - x[index]) * (atY - y[index]) / (beforeY - y[index]) + x[index]) inside = !inside
        }
        return inside
    }
}
