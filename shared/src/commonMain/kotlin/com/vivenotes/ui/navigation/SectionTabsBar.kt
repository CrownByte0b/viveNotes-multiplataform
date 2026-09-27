package com.vivenotes.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.WorkspaceState

private val BarHeight = 40.dp
private val TabHeight = 32.dp

/**
 * Sections as a strip of tabs across the top — the View tab's horizontal Tabs Layout, Android's
 * `SectionTabsBar`. It makes the same selection the notebook pane does, arranged for a wide window:
 * the notebook becomes a chooser and its sections become tabs, each with the pane's right-click menu.
 */
@Composable
internal fun SectionTabsBar(
    state: WorkspaceState,
    requests: NavigationRequests,
    onSelectNotebook: (String) -> Unit,
    onSelectSection: (String) -> Unit,
) {
    val notebook = state.selectedNotebook ?: state.notebooks.firstOrNull()
    val sections = notebook?.sections.orEmpty()
    val accent = sections.firstOrNull { it.id == state.selectedSectionId }?.let { Color(it.colorArgb) }
        ?: MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().background(LocalDesktopColors.current.sidebar).testTag(NavigationTestTags.SectionTabs)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BarHeight).horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NotebookChooser(state, onSelectNotebook)
            sections.forEach { section ->
                WithItemMenu(NavigationItem.Section(section.id), requests) { menuOpen ->
                    SectionTab(section.name, Color(section.colorArgb), section.id == state.selectedSectionId,
                        menuOpen, Modifier.testTag(NavigationTestTags.sectionTab(section.id))) {
                        onSelectSection(section.id)
                    }
                }
            }
        }
        // The selected section's colour runs under the strip, tying the page below to its tab.
        HorizontalDivider(thickness = 2.dp, color = accent)
    }
}

@Composable
private fun NotebookChooser(state: WorkspaceState, onSelectNotebook: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.selectedNotebook
    Box(Modifier.height(BarHeight), contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.DropdownList) { open = true }
                .testTag(NavigationTestTags.NotebookChooser)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                .background(current?.let { Color(it.colorArgb) } ?: Color.Transparent))
            Text(current?.name ?: "Notebooks", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp))
            Icon(DocumentSymbols.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.notebooks.forEach { notebook ->
                DropdownMenuItem(
                    text = { Text(notebook.name) },
                    leadingIcon = {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(notebook.colorArgb)))
                    },
                    onClick = {
                        open = false
                        onSelectNotebook(notebook.id)
                    },
                    modifier = Modifier.testTag(NavigationTestTags.notebookChoice(notebook.id))
                        .semantics { selected = notebook.id == state.selectedNotebookId },
                )
            }
        }
    }
}

/** One section's tab: raised onto the page's surface while selected, quiet otherwise. */
@Composable
private fun SectionTab(
    name: String,
    color: Color,
    selected: Boolean,
    menuOpen: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = when {
            selected -> MaterialTheme.colorScheme.background
            menuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        modifier = modifier.height(TabHeight).semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
