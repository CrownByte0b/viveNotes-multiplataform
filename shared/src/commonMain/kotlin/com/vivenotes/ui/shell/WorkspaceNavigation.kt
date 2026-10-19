package com.vivenotes.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.localization.Strings
import com.vivenotes.ui.navigation.NavigationDivider
import com.vivenotes.ui.navigation.NavigationPaneWidths
import com.vivenotes.ui.navigation.NavigationRequests
import com.vivenotes.ui.navigation.NotebookPane
import com.vivenotes.ui.navigation.PageListPane
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.hideNavigation
import com.vivenotes.workspace.hideNotebookPane
import com.vivenotes.workspace.resizeNotebookPane
import com.vivenotes.workspace.resizePagePane

@Composable
internal fun RowScope.WorkspaceNavigation(
    state: WorkspaceState,
    onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    showNotebookPane: Boolean,
    showPagePane: Boolean,
    widths: NavigationPaneWidths,
    paneMotion: FiniteAnimationSpec<IntSize>,
    navigationRequests: NavigationRequests,
    navigation: NavigationActions,
    debugLog: DebugLog,
) {
    val currentState by rememberUpdatedState(state)
    AnimatedVisibility(
        visible = showNotebookPane,
        enter = expandHorizontally(animationSpec = paneMotion),
        exit = shrinkHorizontally(animationSpec = paneMotion),
    ) {
        Row {
            NotebookPane(
                state = state,
                requests = navigationRequests,
                navigation = navigation,
                onSelectSection = { id -> onStateChange { it.selectSection(id) } },
                onSwipeAway = { onStateChange { it.hideNotebookPane() } },
                width = widths.notebook.dp,
            )
            NavigationDivider(
                width = widths.notebook,
                maxWidth = widths.maxNotebook,
                background = LocalDesktopColors.current.sidebar,
                label = Strings.resize_notebook_panel,
                tag = WorkspaceTestTags.NotebookDivider,
                onResize = { width, restore -> onStateChange { it.resizeNotebookPane(width, restore) } },
                onResizeFinished = {
                    debugLog.event("navigation") { "notebooks resized width=${currentState.notebookPaneWidth} open=${currentState.navigationVisible}" }
                },
            )
        }
    }
    AnimatedVisibility(
        visible = showPagePane,
        enter = expandHorizontally(animationSpec = paneMotion),
        exit = shrinkHorizontally(animationSpec = paneMotion),
    ) {
        Row {
            PageListPane(
                section = state.selectedSection,
                selectedPageId = state.selectedPageId,
                requests = navigationRequests,
                navigation = navigation,
                onSelectPage = { id -> onStateChange { it.selectPage(id) } },
                onSwipeAway = { onStateChange { it.hideNavigation() } },
                width = widths.pages.dp,
            )
            NavigationDivider(
                width = widths.pages,
                maxWidth = widths.maxPages,
                background = MaterialTheme.colorScheme.surfaceContainerHigh,
                label = Strings.resize_pages_panel,
                tag = WorkspaceTestTags.PageDivider,
                onResize = { width, restore -> onStateChange { it.resizePagePane(width, restore) } },
                onResizeFinished = {
                    debugLog.event("navigation") { "pages resized width=${currentState.pagePaneWidth} open=${currentState.pageListVisible}" }
                },
            )
        }
    }
}
