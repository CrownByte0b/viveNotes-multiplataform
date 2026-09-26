package com.vivenotes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

/**
 * Calls [onPress] with the pointer's position in this element when the secondary (right) mouse
 * button goes down anywhere in it.
 *
 * The press is taken in the initial pass, before anything inside sees it, so a text field's own
 * menu, a row's click and a selection gesture all stay out of it. Other buttons pass untouched.
 */
fun Modifier.onSecondaryPress(key: Any?, onPress: (Offset) -> Unit): Modifier = pointerInput(key, onPress) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
            val change = event.changes.firstOrNull() ?: continue
            event.changes.forEach { it.consume() }
            onPress(change.position)
        }
    }
}

/**
 * A right-click menu at [anchor], a point in the parent's coordinates, or closed when [anchor] is
 * null: a column of [ContextMenuItem]s, each an icon, its name and its shortcut, in sections split
 * by [ContextMenuDivider].
 *
 * Material 3 Expressive's menu popup and group, which bring the menu's open and close motion,
 * keyboard focus, and dismissal by Escape or a click outside.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenu(
    anchor: Offset?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Where the menu is, kept through its closing animation after [anchor] has gone.
    var shownAt by remember { mutableStateOf(Offset.Zero) }
    if (anchor != null) shownAt = anchor
    // A point-sized anchor at the pointer: the menu opens from it, and flips at the window's edges.
    Box(Modifier.offset { shownAt.round() }) {
        DropdownMenuPopup(expanded = anchor != null, onDismissRequest = onDismiss) {
            val shape = MenuDefaults.standaloneGroupShape
            // One step above the panes and the canvas, with an outline: a dark shadow barely shows
            // against the dark theme, so the edge carries the separation.
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShapes(shape = shape, inactiveShape = shape),
                modifier = modifier,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(vertical = 4.dp),
                content = content,
            )
        }
    }
}

/**
 * One command in a [ContextMenu]: [icon], then [label], then its keyboard [shortcut] when it has
 * one. A [destructive] command is drawn in the error colour.
 */
@Composable
fun ContextMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = if (destructive) {
        MaterialTheme.colorScheme.error.let { MenuDefaults.itemColors(textColor = it, leadingIconColor = it) }
    } else {
        MenuDefaults.itemColors()
    }
    DropdownMenuItem(
        onClick = onClick,
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
        trailingIcon = shortcut?.let { keys -> { Text(keys, style = MaterialTheme.typography.bodySmall) } },
        enabled = enabled,
        colors = colors,
    )
}

/** The rule between two sections of a [ContextMenu]. */
@Composable
fun ContextMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
