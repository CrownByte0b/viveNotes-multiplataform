package com.vivenotes.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.components.TooltipIconButton
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ViewSymbols
import com.vivenotes.ui.shell.WorkspaceTestTags
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.NavigationItem
import com.vivenotes.workspace.PageSummary
import com.vivenotes.workspace.SectionSummary

/** Android's `PAGE_LIST_WIDTH`. */
private val PagePaneWidth = 260.dp

/** The page pane's orders, with Android's labels. */
enum class PageSort(val label: String) {
    Manual("Section order"),
    Alphabetical("By title"),
    Recent("By date modified"),
}

/** [pages] in [sort]'s order. Untitled pages sort by the name the list gives them. */
internal fun sortedPages(pages: List<PageSummary>, sort: PageSort): List<PageSummary> = when (sort) {
    PageSort.Manual -> pages
    PageSort.Alphabetical -> pages.sortedBy { it.title.ifBlank { UntitledPage }.lowercase() }
    PageSort.Recent -> pages.sortedByDescending { it.updatedAt }
}

/**
 * The open section's pages — the middle pane, Android's `PageListPane` — with its Add Page command
 * and sort menu. Right-clicking a page opens its menu, carried out through [requests].
 *
 * **Dragging is offered only under Section order.** The other sorts come from the pages themselves,
 * so a row dropped somewhere under them would be sorted straight back out of it; a grip that visibly
 * does nothing is worse than none, so it is absent.
 */
@Composable
internal fun PageListPane(
    section: SectionSummary?,
    selectedPageId: String,
    requests: NavigationRequests,
    navigation: NavigationActions,
    onSelectPage: (String) -> Unit,
) {
    var sort by remember { mutableStateOf(PageSort.Manual) }
    val pages = section?.pages.orEmpty()
    val ordered = remember(pages, sort) { sortedPages(pages, sort) }

    /**
     * The order the drag has made, which leads the section's own. Held past the drop, because the
     * store's write is not instant, and letting go of it at once would snap the row back to where
     * it started until the stored order arrives.
     */
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    val shown = dragOrder?.let { order ->
        val byId = ordered.associateBy { it.id }
        order.mapNotNull(byId::get) + ordered.filterNot { it.id in order }
    } ?: ordered
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        keys = if (sort == PageSort.Manual) shown.map { it.id } else emptyList(),
        onMove = { from, to ->
            // Read afresh rather than from `shown`: two moves can land before the next composition.
            dragOrder = (dragOrder ?: ordered.map { it.id }).toMutableList().apply { add(to, removeAt(from)) }
        },
        onSettle = {
            val order = dragOrder
            if (section != null && order != null) navigation.reorderPages(section.id, order)
        },
    )

    LaunchedEffect(pages, sort, reorder.dragging) {
        val pending = dragOrder
        if (pending == null || reorder.dragging) return@LaunchedEffect
        val live = pages.map { it.id }
        // Either the order was stored and the two agree, or the section changed under it — a page
        // added or deleted, another section opened — and the section is the one to believe.
        if (sort != PageSort.Manual || live == pending || live.toSet() != pending.toSet()) dragOrder = null
    }

    Surface(
        modifier = Modifier
            .width(PagePaneWidth)
            .fillMaxHeight()
            .testTag(WorkspaceTestTags.PagePane),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (section != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AddPageCommand(onClick = navigation::addPage)
                    SortMenu(sort, onPick = { sort = it })
                }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
                items(shown, key = { it.id }) { page ->
                    WithItemMenu(
                        item = NavigationItem.Page(page.id),
                        requests = requests,
                        modifier = Modifier.reorderable(reorder, page.id),
                    ) { menuOpen ->
                        PageRow(
                            page = page,
                            selected = page.id == selectedPageId,
                            dragging = reorder.draggedKey == page.id,
                            menuOpen = menuOpen,
                            onClick = { onSelectPage(page.id) },
                            handle = if (sort == PageSort.Manual) {
                                {
                                    DragHandle(
                                        description = "Reorder ${page.title.ifBlank { UntitledPage }}",
                                        modifier = Modifier
                                            .testTag(NavigationTestTags.pageDrag(page.id))
                                            .reorderHandle(reorder, page.id),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPageCommand(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RowShape)
            .clickable(onClick = onClick)
            .testTag(WorkspaceTestTags.AddPage)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NavigationSymbols.NoteAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Add Page",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SortMenu(current: PageSort, onPick: (PageSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            label = "Sort pages",
            onClick = { open = true },
            modifier = Modifier.size(30.dp).testTag(NavigationTestTags.SortPages),
        ) {
            Icon(
                imageVector = NavigationSymbols.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PageSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    // The desktop menus' tick beside the current choice.
                    leadingIcon = {
                        if (option == current) {
                            Icon(ViewSymbols.Check, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.width(18.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                    modifier = Modifier.testTag(NavigationTestTags.sort(option))
                        .semantics { selected = option == current },
                )
            }
        }
    }
}

/**
 * A page: its grip under Section order, then its title — bolder while open, quieter while untitled —
 * the first line written on it when there is one, and when it last changed.
 */
@Composable
private fun PageRow(
    page: PageSummary,
    selected: Boolean,
    dragging: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    handle: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .shadow(if (dragging) 6.dp else 0.dp, RowShape)
            .clip(RowShape)
            .background(rowContainer(selected, dragging, menuOpen))
            .clickable(onClick = onClick)
            .testTag(WorkspaceTestTags.page(page.id))
            .padding(start = if (handle == null) 12.dp else 4.dp, end = 12.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) {
            handle()
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = page.title.ifBlank { UntitledPage },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (page.title.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (page.preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = page.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (page.updatedLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = page.updatedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
