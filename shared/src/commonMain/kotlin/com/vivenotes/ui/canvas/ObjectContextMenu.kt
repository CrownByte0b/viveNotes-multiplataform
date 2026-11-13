package com.vivenotes.ui.canvas

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuDivider
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.workspace.ObjectCommand
import com.vivenotes.workspace.ShortcutAction

/** Semantics identifiers for the prime object right-click menu, and a table's rows in its cells' menu. */
object ObjectMenuTags {
    const val Cut = "object-menu-cut"
    const val Copy = "object-menu-copy"
    const val Delete = "object-menu-delete"
    const val CutTable = "table-menu-cut-table"
    const val CopyTable = "table-menu-copy-table"
    const val DeleteTable = "table-menu-delete-table"
}

/**
 * The prime object right-click menu (`diagram.md`) at [anchor], or closed when it is null: Cut, Copy
 * and Delete of what is selected — the object right-clicked, or the selection it is part of — with
 * the keys that do the same. Cut and Delete need an [editable] page; Copy does not.
 */
@Composable
internal fun ObjectContextMenu(
    anchor: Offset?,
    editable: Boolean,
    onCommand: (ObjectCommand) -> Unit,
    onDismiss: () -> Unit,
    /** The selected object's own commands, between Copy and Delete: a table's rows and columns. */
    own: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // The keys shown are the ones in force, which Settings → Hardware can change.
    val bindings = LocalKeyBindings.current
    ContextMenu(anchor = anchor, onDismiss = onDismiss) {
        ContextMenuItem(
            label = Strings.cut,
            icon = DocumentSymbols.ContentCut,
            shortcut = bindings.primary(ShortcutAction.Cut)?.label,
            enabled = editable,
            onClick = { onCommand(ObjectCommand.Cut) },
            modifier = Modifier.testTag(ObjectMenuTags.Cut),
        )
        ContextMenuItem(
            label = Strings.copy,
            icon = DocumentSymbols.ContentCopy,
            shortcut = bindings.primary(ShortcutAction.Copy)?.label,
            onClick = { onCommand(ObjectCommand.Copy) },
            modifier = Modifier.testTag(ObjectMenuTags.Copy),
        )
        ContextMenuDivider()
        if (own != null) {
            own()
            ContextMenuDivider()
        }
        ContextMenuItem(
            label = Strings.delete,
            icon = ObjectSymbols.Delete,
            shortcut = bindings.primary(ShortcutAction.DeleteSelection)?.label,
            enabled = editable,
            destructive = true,
            onClick = { onCommand(ObjectCommand.Delete) },
            modifier = Modifier.testTag(ObjectMenuTags.Delete),
        )
    }
}

/**
 * A table's own Cut, Copy and Delete, under a cell's text commands: a right-click in a cell edits
 * that cell, so the table's object commands come with it, named for the table so they are not read as
 * the text's. No shortcuts: in a cell, Ctrl+X and Ctrl+C act on the text.
 */
@Composable
internal fun ColumnScope.TableObjectMenuItems(editable: Boolean, onCommand: (ObjectCommand) -> Unit) {
    ContextMenuDivider()
    ContextMenuItem(
        label = Strings.cut_table,
        icon = DocumentSymbols.ContentCut,
        enabled = editable,
        onClick = { onCommand(ObjectCommand.Cut) },
        modifier = Modifier.testTag(ObjectMenuTags.CutTable),
    )
    ContextMenuItem(
        label = Strings.copy_table,
        icon = DocumentSymbols.ContentCopy,
        onClick = { onCommand(ObjectCommand.Copy) },
        modifier = Modifier.testTag(ObjectMenuTags.CopyTable),
    )
    ContextMenuItem(
        label = Strings.delete_table,
        icon = ObjectSymbols.Delete,
        enabled = editable,
        destructive = true,
        onClick = { onCommand(ObjectCommand.Delete) },
        modifier = Modifier.testTag(ObjectMenuTags.DeleteTable),
    )
}
