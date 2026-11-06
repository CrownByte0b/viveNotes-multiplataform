package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.Outline
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.isUnwritten
import com.vivenotes.workspace.primeHeight
import com.vivenotes.workspace.primeWidth

/** Whether [point] (page dp) is in the box's body, which is where it is drawn rather than where it is stored. */
internal fun Outline.Text.bodyContains(point: Offset, heights: Map<String, Float>): Boolean {
    val left = x.coerceAtLeast(0f)
    val top = y.coerceAtLeast(0f)
    return point.x in left..(left + width.coerceIn(120f, 2000f)) &&
        point.y in top..(top + (heights[id] ?: maxOf(150f, minHeight)))
}

/** Whether a Prime Object, drawn over every text box, lies at [point] (page dp). */
internal fun WorkspaceState.objectCovers(point: Offset, heights: Map<String, Float>): Boolean =
    selectedPage?.document?.outlines.orEmpty().any { outline ->
        outline !is Outline.Text && point.x in outline.x..(outline.x + outline.primeWidth()) &&
            point.y in outline.y..(outline.y + (heights[outline.id] ?: outline.primeHeight()))
    }

/**
 * The unwritten text box at [point] (page dp), when nothing written or placed lies there too. It
 * shows only a caret, if that, so what the user sees at such a point is bare page.
 */
internal fun WorkspaceState.emptyTextBoxAt(point: Offset, heights: Map<String, Float>): String? {
    if (objectCovers(point, heights) || textBoxUnderMouse(point, hovered = null, heights) != null) return null
    return selectedPage?.document?.outlines.orEmpty().filterIsInstance<Outline.Text>()
        .lastOrNull { it.isUnwritten() && it.bodyContains(point, heights) }?.id
}
