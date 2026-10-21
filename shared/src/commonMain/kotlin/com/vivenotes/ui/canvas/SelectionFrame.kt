package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Outline
import com.vivenotes.workspace.isPrimeObject
import com.vivenotes.workspace.primeHeight
import com.vivenotes.workspace.primeWidth

/** The visible bounds of a selection containing more than one canvas object, in page dp. */
internal fun selectedCanvasBounds(
    outlines: List<Outline>,
    selectedIds: Set<String>,
    measuredTextHeights: Map<String, Float> = emptyMap(),
    ink: com.vivenotes.model.ink.InkSelection? = null,
): Rect? {
    if (selectedIds.size < 2 && ink == null) return null
    val inkBounds = ink?.bounds
    if (selectedIds.isEmpty()) return inkBounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
    var count = 0
    var combined = inkBounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
    for (outline in outlines) {
        if (outline.id !in selectedIds || (outline !is Outline.Text && !outline.isPrimeObject())) continue
        count++
        val bounds = if (outline is Outline.Text) {
            val x = outline.x.coerceAtLeast(0f)
            val y = outline.y.coerceAtLeast(0f)
            Rect(x, y, x + outline.width.coerceIn(120f, 2000f),
                y + (measuredTextHeights[outline.id] ?: maxOf(outline.minHeight, 150f)))
        } else {
            Rect(outline.x, outline.y, outline.x + outline.primeWidth().coerceAtLeast(24f),
                outline.y + (measuredTextHeights[outline.id] ?: outline.primeHeight()).coerceAtLeast(24f))
        }
        val previous = combined
        combined = if (previous == null) bounds else Rect(minOf(previous.left, bounds.left),
            minOf(previous.top, bounds.top), maxOf(previous.right, bounds.right), maxOf(previous.bottom, bounds.bottom))
    }
    return if (count < 2 && ink == null) null else combined
}
