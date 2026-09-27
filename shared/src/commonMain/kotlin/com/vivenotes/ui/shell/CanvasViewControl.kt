package com.vivenotes.ui.shell

import com.vivenotes.workspace.ViewSettings

/**
 * What the View tab needs from the laid-out canvas, and the one request it passes back. These are
 * layout facts known only where the canvas is measured; nothing renders from them, so they are
 * plain fields rather than state.
 */
internal class CanvasViewControl {
    /** The canvas's width at 100%, in page dp; written on each layout. */
    var viewportWidthDp = 0f

    /** The page's width — the sheet while it binds, else what the content needs — in page dp. */
    var pageWidthDp = 0f

    /** Set by Page Width: the next zoom change brings the page's left edge to the window's. */
    var alignLeftOnNextZoom = false

    /** The zoom that fits the page's width to the window, or null before the canvas is measured. */
    fun pageWidthZoom(): Float? = ViewSettings.fitZoom(viewportWidthDp, pageWidthDp)
}
