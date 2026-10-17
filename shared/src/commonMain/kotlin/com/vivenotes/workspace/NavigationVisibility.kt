package com.vivenotes.workspace

/** Closing notebooks closes their page list; reopening restores both at their last usable widths. */
fun WorkspaceState.toggleNavigation(): WorkspaceState =
    if (navigationVisible) hideNavigation()
    else copy(navigationVisible = true, pageListVisible = true)

fun WorkspaceState.hideNotebookPane(): WorkspaceState = hideNavigation()

/** A swipe left across the page list: Android's `hideNavigation`, which puts both panes away. */
fun WorkspaceState.hideNavigation(): WorkspaceState = copy(navigationVisible = false, pageListVisible = false)

/** Zero closes the pane without losing the width used when it is opened again. */
fun WorkspaceState.resizeNotebookPane(width: Float, restoreWidth: Float = notebookPaneWidth): WorkspaceState = when {
    !width.isFinite() -> this
    width <= 0f -> hideNotebookPane().copy(
        notebookPaneWidth = restoreWidth.takeIf { it.isFinite() && it > 0f } ?: notebookPaneWidth,
    )
    else -> copy(notebookPaneWidth = width)
}

fun WorkspaceState.resizePagePane(width: Float, restoreWidth: Float = pagePaneWidth): WorkspaceState = when {
    !width.isFinite() -> this
    width <= 0f -> copy(pageListVisible = false,
        pagePaneWidth = restoreWidth.takeIf { it.isFinite() && it > 0f } ?: pagePaneWidth)
    else -> copy(pagePaneWidth = width)
}
