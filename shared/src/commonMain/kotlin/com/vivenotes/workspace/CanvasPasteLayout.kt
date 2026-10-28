package com.vivenotes.workspace

import com.vivenotes.model.Outline

internal const val CANVAS_PASTE_MARGIN = 24f

/** Heights include text padding, minimum box sizes, and the table's handle gutter. */
internal fun stackPastedOutlines(outlines: List<Outline>, heights: Map<String, Float>): List<Outline> {
    var top = 0f
    return outlines.map { outline ->
        val height = requireNotNull(heights[outline.id])
        require(height.isFinite() && height >= 0f)
        val placed = when (outline) {
            is Outline.Text -> outline.copy(y = top)
            is Outline.Table -> outline.copy(y = top)
            is Outline.Image -> outline.copy(y = top)
            is Outline.Shape -> outline.copy(y = top)
            is Outline.Equation -> outline.copy(y = top)
            is Outline.Ink -> outline.copy(y = top)
        }
        top += height + CANVAS_PASTE_MARGIN
        placed
    }
}
