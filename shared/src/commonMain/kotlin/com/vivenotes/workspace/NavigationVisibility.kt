package com.vivenotes.workspace

/**
 * The top bar's navigation button. After a swipe has put the page list away it brings both panes
 * back, as Android's does; otherwise it shows or hides the notebook pane, as it always has here.
 */
fun WorkspaceState.toggleNavigation(): WorkspaceState =
    if (!pageListVisible) copy(navigationVisible = true, pageListVisible = true)
    else copy(navigationVisible = !navigationVisible)

/** A swipe left across the notebook pane: Android's `hideNotebookRail`. The page list stays. */
fun WorkspaceState.hideNotebookPane(): WorkspaceState = copy(navigationVisible = false)

/** A swipe left across the page list: Android's `hideNavigation`, which puts both panes away. */
fun WorkspaceState.hideNavigation(): WorkspaceState = copy(navigationVisible = false, pageListVisible = false)
