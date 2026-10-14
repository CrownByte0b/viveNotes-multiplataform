package com.vivenotes.model.ink

internal expect fun inkLassoCloses(path: List<InkSample>): Boolean

internal fun List<InkSample>.encloses(bounds: InkSelectionBounds): Boolean {
    if (size < 3) return false
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        var previous = last()
        for (current in this) {
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val length = dx * dx + dy * dy
            val fraction = if (length == 0f) 0f else ((x - previous.x) * dx + (y - previous.y) * dy) / length
            val nearestX = previous.x + fraction.coerceIn(0f, 1f) * dx
            val nearestY = previous.y + fraction.coerceIn(0f, 1f) * dy
            if ((x - nearestX) * (x - nearestX) + (y - nearestY) * (y - nearestY) <= 16f) return true
            if ((current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x) inside = !inside
            previous = current
        }
        return inside
    }
    return contains(bounds.left, bounds.top) && contains(bounds.right, bounds.top) &&
        contains(bounds.right, bounds.bottom) && contains(bounds.left, bounds.bottom)
}
