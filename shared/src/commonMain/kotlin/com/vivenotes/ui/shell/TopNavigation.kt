package com.vivenotes.ui.shell

import com.vivenotes.localization.Strings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.AccountSymbols
import com.vivenotes.ui.icons.ShellSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.RibbonTab

/** The window's top bar: navigation toggle, app name, the ribbon's tabs, and canvas undo/redo. */
@Composable
internal fun TopNavigation(
    activeTab: RibbonTab,
    navigationVisible: Boolean,
    onToggleNavigation: () -> Unit,
    onSelectTab: (RibbonTab) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** Whether this installation holds a device registration — puts a dot on the account button. */
    accountConnected: Boolean,
    /**
     * Whether the last sync run could not reach that server — turns the dot into Cloud Off. See
     * Android's `SyncStatus.serverUnreachable` for what does and does not count.
     */
    serverUnreachable: Boolean = false,
    onOpenAccount: () -> Unit,
    onSearch: () -> Unit = {},
    searchAvailable: Boolean = false,
    onTabPress: () -> Unit = {},
) {
    val currentTabPress by rememberUpdatedState(onTabPress)
    Surface(modifier = Modifier.testTag(WorkspaceTestTags.HeaderBar),
        color = LocalDesktopColors.current.headerBar, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TooltipIconButton(
                label = if (navigationVisible) Strings.hide_notebook_navigation else Strings.show_notebook_navigation,
                onClick = onToggleNavigation,
                modifier = Modifier.testTag(WorkspaceTestTags.NavigationToggle)
                    .semantics { selected = navigationVisible },
            ) {
                Icon(if (navigationVisible) ShellSymbols.MenuOpen else ShellSymbols.Menu,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).testTag(WorkspaceTestTags.NavigationIcon))
            }
            Text(
                text = Strings.vivenotes,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            currentTabPress()
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        }
                    },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RibbonTab.entries.forEach { tab ->
                        val isSelected = activeTab == tab
                        Surface(
                            onClick = { onSelectTab(tab) },
                            color = if (isSelected) LocalDesktopColors.current.selection
                                else LocalDesktopColors.current.headerBar,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .testTag(WorkspaceTestTags.ribbonTab(tab))
                                .semantics { selected = isSelected },
                        ) {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            TooltipIconButton(Strings.search_notebook, onClick = onSearch, enabled = searchAvailable,
                modifier = Modifier.testTag(com.vivenotes.ui.search.SearchTags.Open)) {
                Icon(ShellSymbols.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            TooltipIconButton(Strings.undo_canvas_action, onClick = onUndo, enabled = canUndo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralUndo)) {
                Icon(ShellSymbols.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            TooltipIconButton(Strings.redo_canvas_action, onClick = onRedo, enabled = canRedo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralRedo)) {
                Icon(ShellSymbols.Redo, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            // A dot rather than pressed-state chrome: that chrome means "this control is on", and the
            // account button is not a toggle — it opens a screen. A badge says something is true
            // *about* what it opens, which is what being connected is. The label carries the state
            // too, so it is announced rather than left to a coloured dot nobody can hear.
            TooltipIconButton(
                when {
                    serverUnreachable -> Strings.ribbon_account_unreachable
                    accountConnected -> Strings.ribbon_account_connected
                    else -> Strings.ribbon_account
                },
                onClick = onOpenAccount, modifier = Modifier.testTag(WorkspaceTestTags.Account),
            ) {
                Box {
                    Icon(ShellSymbols.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    val headerBar = LocalDesktopColors.current.headerBar
                    when {
                        // Cloud Off *instead of* the dot, never beside it. The dot's claim is "there is
                        // a server"; while it cannot be reached that claim is what is being corrected.
                        serverUnreachable -> Icon(
                            AccountSymbols.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)
                                .size(13.dp).background(headerBar, CircleShape).padding(1.dp)
                                .testTag(WorkspaceTestTags.AccountOffline),
                        )
                        // On the glyph's top-right arc, ringed in the bar's own colour so it stays
                        // separate from the icon stroke it overlaps.
                        accountConnected -> Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp).size(9.dp)
                                .border(1.5.dp, headerBar, CircleShape).padding(1.5.dp)
                                .background(LocalDesktopColors.current.success, CircleShape)
                                .testTag(WorkspaceTestTags.AccountConnected),
                        )
                    }
                }
            }
        }
    }
}
