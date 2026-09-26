package com.vivenotes.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.TooltipIconButton
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TooltipIconButton(
                label = if (navigationVisible) "Hide notebook navigation" else "Show notebook navigation",
                onClick = onToggleNavigation,
                modifier = Modifier.testTag(WorkspaceTestTags.NavigationToggle),
            ) {
                Text(text = "☰", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "ViveNotes",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RibbonTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.name) },
                        modifier = Modifier.testTag(WorkspaceTestTags.ribbonTab(tab)),
                    )
                }
            }
            TooltipIconButton("Undo canvas action", onClick = onUndo, enabled = canUndo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralUndo)) {
                Text("↶", style = MaterialTheme.typography.titleLarge)
            }
            TooltipIconButton("Redo canvas action", onClick = onRedo, enabled = canRedo,
                modifier = Modifier.testTag(WorkspaceTestTags.StructuralRedo)) {
                Text("↷", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "Local preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
