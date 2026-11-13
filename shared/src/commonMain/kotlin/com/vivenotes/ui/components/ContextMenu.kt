package com.vivenotes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupProperties
import com.vivenotes.ui.icons.AccountSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onPress] with the pointer's position in this element when the secondary (right) mouse
 * button goes down anywhere in it, or when a finger is held still on it — the touch screen's
 * right-click, as Android's long-press opens the same menus.
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
}.onTouchLongPress(key, onPress)

/**
 * A single finger held within the touch slop for the long-press time. Nothing is taken until then,
 * so a tap still clicks and a drag still scrolls; once it fires, the rest of the touch is the menu's,
 * so lifting the finger does not also click what is under it.
 */
private fun Modifier.onTouchLongPress(key: Any?, onPress: (Offset) -> Unit): Modifier = pointerInput(key, onPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                if (!change.pressed || change.isConsumed || moved || event.changes.count { it.pressed } > 1) break
            }
        }
        if (ended != null) return@awaitEachGesture
        onPress(down.position)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
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
    val contentDensity = LocalDensity.current
    // Component popup layers use host window coordinates. Keep their placement at the host density,
    // while menu rows retain the user's Interface scale, as HoverTooltip does for its popup.
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    // Where the menu is, kept through its closing animation after [anchor] has gone.
    var shownAt by remember { mutableStateOf(Offset.Zero) }
    if (anchor != null) shownAt = anchor
    // A point-sized anchor at the pointer: the menu opens from it, and flips at the window's edges.
    CompositionLocalProvider(LocalDensity provides layerDensity) {
        Box(Modifier.offset { shownAt.round() }) {
            DropdownMenuPopup(expanded = anchor != null, onDismissRequest = onDismiss) {
                PopupTooltipScope("context menu") {
                    CompositionLocalProvider(LocalDensity provides contentDensity) {
                        val shape = MenuDefaults.standaloneGroupShape
                        // One step above the panes and the canvas, with an outline: a dark shadow barely shows
                        // against the dark theme, so the edge carries the separation.
                        val scope = rememberCoroutineScope()
                        val submenus = remember(scope) { ContextSubmenus(scope) }
                        // A closing menu takes its submenu with it rather than leaving it up alone.
                        submenus.menuOpen = anchor != null
                        CompositionLocalProvider(LocalContextSubmenus provides submenus) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShapes(shape = shape, inactiveShape = shape),
                                modifier = modifier.dismissPopupOnEscape(onDismiss),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                content = content,
                            )
                        }
                    }
                }
            }
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
    // Moving on to a plain command closes the submenu a neighbour opened; a command inside a
    // submenu is not one of the menu's own rows, so it leaves its own submenu alone.
    val submenus = LocalContextSubmenus.current
    DropdownMenuItem(
        onClick = onClick,
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        shape = MaterialTheme.shapes.medium,
        modifier = if (submenus == null) modifier else modifier.onPointerEnter(submenus) { submenus.point(null) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
        trailingContent = shortcut?.let { keys -> { Text(keys, style = MaterialTheme.typography.bodySmall) } },
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

/**
 * Which of a [ContextMenu]'s submenus is open. Pointing at a row asks for its submenu, or for none,
 * after a pause: straight away would close a submenu under a pointer crossing a neighbouring row on
 * its way into it, and open one for a pointer only passing through.
 */
@Stable
internal class ContextSubmenus(private val scope: CoroutineScope) {
    var open by mutableStateOf<Any?>(null)
        private set
    var menuOpen by mutableStateOf(true)
    private var pending: Job? = null

    /** The pointer is over the row that opens [key], or with null over a row that opens nothing. */
    fun point(key: Any?) {
        pending?.cancel()
        if (key == open) return
        pending = scope.launch {
            delay(if (open == null) OPEN_DELAY_MILLIS else SWITCH_DELAY_MILLIS)
            open = key
        }
    }

    /** A click, or the pointer inside the submenu itself: no waiting. */
    fun show(key: Any?) {
        pending?.cancel()
        open = key
    }

    private companion object {
        const val OPEN_DELAY_MILLIS = 120L
        const val SWITCH_DELAY_MILLIS = 300L
    }
}

/** The enclosing [ContextMenu]'s submenus; null inside a submenu, and outside any menu. */
internal val LocalContextSubmenus = compositionLocalOf<ContextSubmenus?> { null }

private fun Modifier.onPointerEnter(key: Any?, onEnter: () -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            // Moves as well as the entry: a pointer already on the row when the menu opened never enters it.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) onEnter()
        }
    }
}

/**
 * A row of a [ContextMenu] that opens a second menu beside it — [icon], [label], then a chevron —
 * holding [content]'s [ContextMenuItem]s. It opens when the pointer rests on the row or clicks it,
 * and closes when the pointer moves on to another row or the menu closes.
 *
 * The second menu takes no keyboard focus: Escape and a click outside stay the first menu's, which
 * closes both. Its commands close the whole menu themselves, as the first menu's do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextSubmenu(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val submenus = LocalContextSubmenus.current ?: return
    val key = remember { Any() }
    val contentDensity = LocalDensity.current
    val layerDensity = LocalPopupLayerDensity.current ?: contentDensity
    Box {
        DropdownMenuItem(
            onClick = { submenus.show(key) },
            text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
            shape = MaterialTheme.shapes.medium,
            modifier = if (enabled) modifier.onPointerEnter(submenus) { submenus.point(key) } else modifier,
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
            trailingContent = {
                Icon(AccountSymbols.ChevronRight, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize))
            },
            enabled = enabled,
        )
        // Placed in host-window pixels like the menu it belongs to, beside this row.
        CompositionLocalProvider(LocalDensity provides layerDensity) {
            DropdownMenuPopup(
                expanded = enabled && submenus.menuOpen && submenus.open == key,
                onDismissRequest = { submenus.show(null) },
                popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.End),
                properties = PopupProperties(focusable = false),
            ) {
                PopupTooltipScope("context submenu") {
                    CompositionLocalProvider(LocalDensity provides contentDensity, LocalContextSubmenus provides null) {
                        val shape = MenuDefaults.standaloneGroupShape
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShapes(shape = shape, inactiveShape = shape),
                            modifier = Modifier.onPointerEnter(submenus) { submenus.show(key) },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
