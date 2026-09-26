package com.vivenotes.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
) {
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
                label = if (navigationVisible) "Hide notebook navigation" else "Show notebook navigation",
                onClick = onToggleNavigation,
                modifier = Modifier.testTag(WorkspaceTestTags.NavigationToggle),
            ) {
                Icon(ShellSymbols.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = "ViveNotes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                                tab.name,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            TooltipIconButton("Undo canvas action", onClick = onUndo, enabled = canUndo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralUndo)) {
                Icon(ShellSymbols.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            TooltipIconButton("Redo canvas action", onClick = onRedo, enabled = canRedo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralRedo)) {
                Icon(ShellSymbols.Redo, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
