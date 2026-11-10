package com.vivenotes.workspace

import com.vivenotes.model.Outline

internal const val CANVAS_PASTE_MARGIN = 24f

/**
 * Android's `PasteFrames`: how tall each pasted outline is drawn, by id — on desktop including what
 * Android keeps apart as insets, the table's handle gutter — and how far down the page what is
 * already on it reaches, as the canvas drew it.
 */
data class PasteFrames(
    val heights: Map<String, Float>,
    /** How far down the page what is already on it reaches, as the canvas drew it. */
    val contentBottom: Float? = null,
)

/**
 * How tall the document alone says an outline is, for a paste no canvas measured: a text box's
 * floor or a line a paragraph, a table's row floors and its handle gutter. Android's
 * `documentHeight`, with the gutter the desktop's heights include.
 */
internal fun Outline.documentHeight(): Float = when (this) {
    is Outline.Text -> maxOf(minHeight, blocks.size * ESTIMATED_LINE_HEIGHT)
    is Outline.Table -> TABLE_GUTTER + height
    is Outline.Image -> height
    is Outline.Shape -> height
    is Outline.Equation -> height
    is Outline.Graph -> height
    is Outline.Ink -> height
}

private const val ESTIMATED_LINE_HEIGHT = 24f

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
            is Outline.Graph -> outline.copy(y = top)
            is Outline.Ink -> outline.copy(y = top)
        }
        top += height + CANVAS_PASTE_MARGIN
        placed
    }
}
