package com.vivenotes.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuDivider
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.NotebookSummary
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.SectionSummary
import com.vivenotes.workspace.WorkspaceState

private val NotebookPaneWidth = 248.dp
private val PagePaneWidth = 280.dp

/** Semantics identifiers for the navigation panes' menus and dialogs. */
object NavigationTestTags {
    const val Dialog = "navigation-dialog"
    const val Backdrop = "navigation-dialog-backdrop"
    const val Rename = "navigation-menu-rename"
    const val Delete = "navigation-menu-delete"
    const val NameField = "navigation-name-field"
    const val ConfirmRename = "navigation-confirm-rename"
    const val ConfirmDelete = "navigation-confirm-delete"
    const val Cancel = "navigation-dialog-cancel"
    fun notebook(id: String): String = "navigation-notebook-$id"
}

/**
 * The notebooks with the open one's sections. Right-clicking a notebook or section opens its menu;
 * what the menu asks for is carried out through [requests].
 */
@Composable
internal fun NotebookPane(
    state: WorkspaceState,
    requests: NavigationRequests,
    onSelectNotebook: (String) -> Unit,
    onSelectSection: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(NotebookPaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.NotebookPane),
        color = LocalDesktopColors.current.sidebar,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "Notebooks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.notebooks, key = NotebookSummary::id) { notebook ->
                    WithItemMenu(NavigationItem.Notebook(notebook.id), requests) { menuOpen ->
                        NotebookRow(
                            notebook = notebook,
                            selected = notebook.id == state.selectedNotebookId,
                            menuOpen = menuOpen,
                            onClick = { onSelectNotebook(notebook.id) },
                        )
                    }
                    if (notebook.id == state.selectedNotebookId) {
                        notebook.sections.forEach { section ->
                            WithItemMenu(NavigationItem.Section(section.id), requests) { menuOpen ->
                                SectionRow(
                                    section = section,
                                    selected = section.id == state.selectedSectionId,
                                    menuOpen = menuOpen,
                                    onClick = { onSelectSection(section.id) },
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.padding(10.dp),
            ) {
                Text("＋ New notebook")
            }
        }
    }
}

/**
 * The right-click menu of one notebook, section or page: Rename, then Delete set apart below it.
 * [content] is told while the menu is open, so the row can show whose menu it is.
 */
@Composable
private fun WithItemMenu(
    item: NavigationItem,
    requests: NavigationRequests,
    content: @Composable (menuOpen: Boolean) -> Unit,
) {
    var menuAt by remember(item) { mutableStateOf<Offset?>(null) }
    var pendingCommand by remember(item) { mutableStateOf<NavigationCommand?>(null) }
    val closeMotion = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(pendingCommand) {
        val command = pendingCommand ?: return@LaunchedEffect
        // Wait for Material's menu close motion before showing the in-window confirmation. The
        // menu is a native popup layer, and opening a confirmation while it is closing leaves a
        // ghost of that layer above the workspace on native Wayland.
        animate(1f, 0f, animationSpec = closeMotion) { _, _ -> }
        withFrameNanos { }
        when (command) {
            NavigationCommand.Rename -> requests.renaming = item
            NavigationCommand.Delete -> requests.deleting = item
        }
        pendingCommand = null
    }
    Box(Modifier.onSecondaryPress(item) { menuAt = it }) {
        content(menuAt != null)
        ContextMenu(anchor = menuAt, onDismiss = { menuAt = null }) {
            ContextMenuItem(
                label = "Rename ${item.noun}",
                icon = ContextSymbols.Edit,
                onClick = {
                    menuAt = null
                    pendingCommand = NavigationCommand.Rename
                },
                modifier = Modifier.testTag(NavigationTestTags.Rename),
            )
            ContextMenuDivider()
            ContextMenuItem(
                label = "Delete ${item.noun}",
                icon = ObjectSymbols.Delete,
                destructive = true,
                onClick = {
                    menuAt = null
                    pendingCommand = NavigationCommand.Delete
                },
                modifier = Modifier.testTag(NavigationTestTags.Delete),
            )
        }
    }
}

private enum class NavigationCommand { Rename, Delete }

/** The container a row shows: its selection, else a quieter one while its menu is open. */
@Composable
private fun rowContainer(selected: Color, menuOpen: Boolean, isSelected: Boolean): Color {
    val target = when {
        isSelected -> selected
        menuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    return animateColorAsState(target, MaterialTheme.motionScheme.defaultEffectsSpec()).value
}

@Composable
private fun NotebookRow(
    notebook: NotebookSummary,
    selected: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = rowContainer(LocalDesktopColors.current.selection, menuOpen, selected),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = 8.dp).testTag(NavigationTestTags.notebook(notebook.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(notebook.colorArgb)))
            Text(notebook.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (selected) "⌄" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionRow(
    section: SectionSummary,
    selected: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .testTag(WorkspaceTestTags.section(section.id))
            .clip(MaterialTheme.shapes.small)
            .background(rowContainer(LocalDesktopColors.current.selection, menuOpen, selected))
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .height(36.dp)
            .padding(start = 28.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(section.colorArgb)),
        )
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The open section's pages. Right-clicking a page opens its menu, carried out through [requests]. */
@Composable
internal fun PageListPane(
    section: SectionSummary?,
    selectedPageId: String,
    requests: NavigationRequests,
    onAddPage: () -> Unit,
    onSelectPage: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(PagePaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.PagePane),
        color = LocalDesktopColors.current.sidebar,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = section?.name ?: "No section",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${section?.pages?.size ?: 0} pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onAddPage,
                    enabled = section != null,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier.testTag(WorkspaceTestTags.AddPage),
                ) {
                    Text("＋ Page")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(section?.pages.orEmpty(), key = PageSummary::id) { page ->
                    WithItemMenu(NavigationItem.Page(page.id), requests) { menuOpen ->
                        PageRow(
                            page = page,
                            selected = page.id == selectedPageId,
                            menuOpen = menuOpen,
                            onClick = { onSelectPage(page.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    page: PageSummary,
    selected: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = rowContainer(LocalDesktopColors.current.selection, menuOpen, selected),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WorkspaceTestTags.page(page.id)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = page.title.ifBlank { UntitledPage },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = page.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = page.createdLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
