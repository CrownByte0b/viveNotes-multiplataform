package com.vivenotes.model

object PageSpace {

    enum class Axis {
        
        Vertical,

        Horizontal,
    }
}

data class SpaceCut(
    val axis: PageSpace.Axis,
    val at: Float,
    val amount: Float,
) {
    
    val isEmpty: Boolean get() = amount == 0f

    val dx: Float get() = if (axis == PageSpace.Axis.Horizontal) amount else 0f

    val dy: Float get() = if (axis == PageSpace.Axis.Vertical) amount else 0f

    fun nearEdge(x: Float, y: Float): Float = if (axis == PageSpace.Axis.Horizontal) x else y

    fun moves(x: Float, y: Float): Boolean = nearEdge(x, y) >= at

    fun limitedTo(nearestMovedEdge: Float?): SpaceCut {
        if (amount >= 0f || nearestMovedEdge == null) return this
        return copy(amount = amount.coerceAtLeast(at - nearestMovedEdge))
    }
}