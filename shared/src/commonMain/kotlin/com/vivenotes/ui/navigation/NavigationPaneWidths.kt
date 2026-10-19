package com.vivenotes.ui.navigation

internal data class NavigationPaneWidths(
    val notebook: Float,
    val pages: Float,
    val maxNotebook: Float,
    val maxPages: Float,
)

/** Fit remembered widths to the current window without overwriting the user's choices. */
internal fun navigationPaneWidths(
    available: Float,
    notebook: Float,
    pages: Float,
    dividerWidth: Float,
): NavigationPaneWidths {
    val dividers = (if (notebook > 0f) dividerWidth else 0f) +
        (if (pages > 0f) dividerWidth else 0f)
    val budget = (available - dividers).coerceAtLeast(0f)
    val total = notebook + pages
    val scale = if (total > budget && total > 0f) budget / total else 1f
    val fittedNotebook = notebook * scale
    val fittedPages = pages * scale
    return NavigationPaneWidths(fittedNotebook, fittedPages,
        budget - fittedPages, budget - fittedNotebook)
}
