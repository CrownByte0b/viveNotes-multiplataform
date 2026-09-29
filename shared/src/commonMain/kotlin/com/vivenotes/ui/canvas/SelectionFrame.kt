package com.vivenotes.ui.canvas

import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Outline
import com.vivenotes.workspace.isPrimeObject
import com.vivenotes.workspace.primeHeight

/** The visible bounds of a selection containing more than one canvas object, in page dp. */
internal fun selectedCanvasBounds(
    outlines: List<Outline>,
    selectedIds: Set<String>,
    measuredTextHeights: Map<String, Float> = emptyMap(),
): Rect? {
    val selected = outlines.filter { it.id in selectedIds && (it is Outline.Text || it.isPrimeObject()) }
    if (selected.size < 2) return null
    val bounds = selected.map { outline ->
        if (outline is Outline.Text) {
            val x = outline.x.coerceAtLeast(0f)
            val y = outline.y.coerceAtLeast(0f)
            Rect(x, y, x + outline.width.coerceIn(120f, 2000f),
                y + (measuredTextHeights[outline.id] ?: maxOf(outline.minHeight, 150f)))
        } else {
            Rect(outline.x, outline.y, outline.x + outline.width.coerceAtLeast(24f),
                outline.y + outline.primeHeight().coerceAtLeast(24f))
        }
    }
    return Rect(bounds.minOf { it.left }, bounds.minOf { it.top },
        bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
}
