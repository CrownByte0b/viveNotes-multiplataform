package com.vivenotes.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuDivider
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.components.onSecondaryPress
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.ui.icons.NavigationSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.theme.LocalDesktopColors
import com.vivenotes.workspace.NavigationItem

/** Semantics identifiers for the navigation panes, their menus and dialogs. */
object NavigationTestTags {
    const val Dialog = "navigation-dialog"
    const val Backdrop = "navigation-dialog-backdrop"
    const val Rename = "navigation-menu-rename"
    const val Delete = "navigation-menu-delete"
    const val NameField = "navigation-name-field"
    const val ConfirmRename = "navigation-confirm-rename"
    const val ConfirmCreate = "navigation-confirm-create"
    const val ConfirmDelete = "navigation-confirm-delete"
    const val Cancel = "navigation-dialog-cancel"
    fun notebook(id: String): String = "navigation-notebook-$id"
    fun sectionDrag(id: String): String = "navigation-section-drag-$id"
    fun addSection(notebookId: String): String = "navigation-add-section-$notebookId"
    const val AddNotebook = "navigation-add-notebook"
    const val SortPages = "navigation-sort-pages"
    fun sort(sort: PageSort): String = "navigation-sort-${sort.name}"
    fun pageDrag(id: String): String = "navigation-page-drag-$id"
    const val SectionTabs = "navigation-section-tabs"
    const val NotebookChooser = "navigation-notebook-chooser"
    fun notebookChoice(id: String): String = "navigation-notebook-choice-$id"
    fun sectionTab(id: String): String = "navigation-section-tab-$id"
}

/**
 * The right-click menu of one notebook, section or page: Rename, then Delete set apart below it.
 * [content] is told while the menu is open, so the row can show whose menu it is. [modifier] goes on
 * the outermost layout, which in a lazy list is what may be lifted over its neighbours.
 */
@Composable
internal fun WithItemMenu(
    item: NavigationItem,
    requests: NavigationRequests,
    modifier: Modifier = Modifier,
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
    Box(modifier.onSecondaryPress(item) { menuAt = it }) {
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

/**
 * A row's background: raised while it is open or being dragged — a dragged row is drawn over its
 * neighbours, so it cannot stay transparent — a quieter tone while its menu is open, else none.
 */
@Composable
internal fun rowContainer(selected: Boolean, dragging: Boolean, menuOpen: Boolean): Color {
    val target = when {
        selected || dragging -> LocalDesktopColors.current.selection
        menuOpen -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    return animateColorAsState(target, MaterialTheme.motionScheme.defaultEffectsSpec()).value
}

/** Android's row corner in both panes. */
internal val RowShape = RoundedCornerShape(4.dp)

/**
 * The grip in a reorderable row, shared so the two panes cannot drift apart. The glyph is smaller
 * than the area that grabs it, so it reads as texture rather than as one more button.
 *
 * Its alt text stays its own rather than merging into the row's: the row is the button that opens
 * the page or section, and its name is what it should be announced by.
 */
@Composable
internal fun DragHandle(description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(HandleArea)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NavigationSymbols.DragIndicator,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(HandleGlyph),
        )
    }
}

private val HandleArea = 20.dp
private val HandleGlyph = 15.dp

/** "+ New Section" and "+ New Notebook": a quiet row rather than a button, as on Android. */
@Composable
internal fun AddRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 28.dp, end = 10.dp)
            .height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NavigationSymbols.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
