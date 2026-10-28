package com.vivenotes.ui.shell

import androidx.compose.ui.geometry.Offset
import com.vivenotes.model.PageStyle
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.titleFloor

/**
 * Ctrl+V on the canvas, answered by [PageCanvas]: [paste] puts the canvas clipboard at
 * [keyboardPastePoint] and says whether there was anything to paste.
 */
internal class CanvasKeyboardPaste {
    var paste: () -> Boolean = { false }
}

/**
 * Where Ctrl+V puts the canvas clipboard, as the page point for its top left: under the mouse
 * [pointer] when the page takes content there, as a double click's Paste does, else
 * [WorkspaceState.PICTURE_INSERT_MARGIN] inside the [visibleOrigin] and below the title, where a
 * picture is inserted.
 */
internal fun keyboardPastePoint(
    pointer: Offset?,
    visibleOrigin: Offset,
    style: PageStyle,
    canPlaceAt: (Float, Float) -> Boolean,
): Offset {
    pointer?.takeIf { canPlaceAt(it.x, it.y) }?.let { return it }
    val margin = WorkspaceState.PICTURE_INSERT_MARGIN
    return Offset(visibleOrigin.x + margin, maxOf(visibleOrigin.y + margin, style.titleFloor))
}
