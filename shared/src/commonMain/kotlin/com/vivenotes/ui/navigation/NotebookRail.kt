package com.vivenotes.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.NotebookSummary
import com.vivenotes.workspace.SectionSummary
import com.vivenotes.workspace.WorkspaceState

/** Android's `RAIL_WIDTH`. */
private val NotebookPaneWidth = 232.dp
private val SectionRowHeight = 32.dp

/**
 * The notebooks and their sections — the leftmost pane, Android's `NotebookRail`.
 *
 * A notebook header folds its sections open or shut; a section is what is opened, since the page
 * pane always shows one section's pages. Right-clicking a row opens its menu, carried out through
 * [requests]; the New rows ask for a name the same way.
 *
 * Sections reorder within their own notebook and nowhere else: `sortIndex` belongs to a notebook, so
 * a section dropped under another would be a move rather than a reorder. The draggable set is the
 * grabbed notebook's sections alone, which also stops a long drag sweeping one through a neighbour.
 */
@Composable
internal fun NotebookPane(
    state: WorkspaceState,
    requests: NavigationRequests,
    navigation: NavigationActions,
    onSelectSection: (String) -> Unit,
) {
    /** Whose section is being dragged. Set before the drag starts, so the key set is ready for it. */
    var grabbedNotebookId by remember { mutableStateOf<String?>(null) }

    /** That notebook's order as the drag has made it, shown until the notebooks agree with it. */
    var pending by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    fun sectionsOf(notebook: NotebookSummary): List<SectionSummary> {
        val order = pending?.takeIf { it.first == notebook.id }?.second ?: return notebook.sections
        val byId = notebook.sections.associateBy { it.id }
        return order.mapNotNull(byId::get) + notebook.sections.filterNot { it.id in order }
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        keys = state.notebooks.firstOrNull { it.id == grabbedNotebookId }
            ?.let { notebook -> sectionsOf(notebook).map { it.id } }
            .orEmpty(),
        onMove = { from, to ->
            val notebook = state.notebooks.firstOrNull { it.id == grabbedNotebookId }
            if (notebook != null) {
                val order = sectionsOf(notebook).map { it.id }.toMutableList()
                order.add(to, order.removeAt(from))
                pending = notebook.id to order
            }
        },
        onSettle = {
            pending?.let { (notebookId, order) -> navigation.reorderSections(notebookId, order) }
            grabbedNotebookId = null
        },
    )

    LaunchedEffect(state.notebooks, reorder.dragging) {
        val (notebookId, order) = pending ?: return@LaunchedEffect
        if (reorder.dragging) return@LaunchedEffect
        val live = state.notebooks.firstOrNull { it.id == notebookId }?.sections?.map { it.id }
        // Either the order was stored and the two agree, or the notebook changed underneath — a
        // section added or removed — and the notebooks are the ones to believe.
        if (live == null || live == order || live.toSet() != order.toSet()) pending = null
    }

    Surface(
        modifier = Modifier
            .width(NotebookPaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.NotebookPane),
        color = LocalDesktopColors.current.sidebar,
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                state.notebooks.forEach { notebook ->
                    item(key = notebook.id) {
                        WithItemMenu(NavigationItem.Notebook(notebook.id), requests) { menuOpen ->
                            NotebookHeader(
                                notebook = notebook,
                                menuOpen = menuOpen,
                                onClick = { navigation.setNotebookExpanded(notebook.id, !notebook.expanded) },
                            )
                        }
                    }
                    if (notebook.expanded) {
                        items(sectionsOf(notebook), key = { it.id }) { section ->
                            WithItemMenu(
                                item = NavigationItem.Section(section.id),
                                requests = requests,
                                modifier = Modifier.reorderable(reorder, section.id),
                            ) { menuOpen ->
                                SectionRow(
                                    section = section,
                                    selected = section.id == state.selectedSectionId,
                                    dragging = reorder.draggedKey == section.id,
                                    menuOpen = menuOpen,
                                    onClick = { onSelectSection(section.id) },
                                ) {
                                    DragHandle(
                                        description = "Reorder ${section.name}",
                                        modifier = Modifier
                                            .testTag(NavigationTestTags.sectionDrag(section.id))
                                            .reorderHandle(reorder, section.id) { grabbedNotebookId = notebook.id },
                                    )
                                }
                            }
                        }
                        item(key = "add-section-${notebook.id}") {
                            AddRow(
                                label = "New Section",
                                onClick = { requests.creating = NewItem.Section(notebook.id) },
                                modifier = Modifier.testTag(NavigationTestTags.addSection(notebook.id)),
                            )
                        }
                    }
                }
            }
            AddRow(
                label = "New Notebook",
                onClick = { requests.creating = NewItem.Notebook },
                modifier = Modifier.padding(bottom = 8.dp).testTag(NavigationTestTags.AddNotebook),
            )
        }
    }
}

/** A notebook: its disclosure, its book in its colour, and its name. A click folds it open or shut. */
@Composable
private fun NotebookHeader(
    notebook: NotebookSummary,
    menuOpen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(rowContainer(selected = false, dragging = false, menuOpen = menuOpen))
            .clickable(onClickLabel = if (notebook.expanded) "Collapse" else "Expand", onClick = onClick)
            .semantics { stateDescription = if (notebook.expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 10.dp)
            .testTag(NavigationTestTags.notebook(notebook.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (notebook.expanded) NavigationSymbols.ExpandLess else NavigationSymbols.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = NavigationSymbols.Book,
            contentDescription = null,
            tint = Color(notebook.colorArgb),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = notebook.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A section: its grip, its colour chip standing in for a tab, and its name, bolder while open. */
@Composable
private fun SectionRow(
    section: SectionSummary,
    selected: Boolean,
    dragging: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    handle: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .shadow(if (dragging) 6.dp else 0.dp, RowShape)
            .clip(RowShape)
            .background(rowContainer(selected, dragging, menuOpen))
            .clickable(onClick = onClick)
            .testTag(WorkspaceTestTags.section(section.id))
            .padding(horizontal = 2.dp)
            .height(SectionRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        handle()
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(section.colorArgb)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
