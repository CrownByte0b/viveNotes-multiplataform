package com.vivenotes.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import com.vivenotes.richtext.TextSelection
import com.vivenotes.ui.components.ContextMenu
import com.vivenotes.ui.components.ContextMenuDivider
import com.vivenotes.ui.components.ContextMenuItem
import com.vivenotes.ui.icons.ContextSymbols
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ObjectSymbols
import com.vivenotes.ui.keyboard.LocalKeyBindings
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.WorkspaceState

/** Semantics identifiers for the text box's right-click menu. */
object TextMenuTags {
    const val Cut = "text-menu-cut"
    const val SelectAll = "text-menu-select-all"
    const val Copy = "text-menu-copy"
    const val Paste = "text-menu-paste"
    const val PastePlainText = "text-menu-paste-plain"
    const val CopyBox = "text-menu-copy-box"
    const val DeleteBox = "text-menu-delete-box"
}

/**
 * A right-click in text box [outlineId] at [at], in the box's coordinates. [selection] is the range
 * the menu's commands act on, captured at the click: opening the menu takes the keyboard from the
 * editor, which can collapse the editor's own selection.
 */
internal data class TextMenuRequest(val outlineId: String, val at: Offset, val selection: TextSelection)

/**
 * Where a right-click at [point] leaves the caret, as desktop editors do it: inside the current
 * selection keeps that selection, so Copy copies it; anywhere else puts the caret there, so Paste
 * pastes there. [current] is null when the box is not being edited; [layout] is the box's text,
 * laid out with its top left at the origin [point] is measured from.
 */
internal fun selectionForRightClick(current: TextSelection?, layout: TextLayoutResult?, point: Offset): TextSelection {
    val clicked = layout?.getOffsetForPosition(point)
    return when {
        current != null && !current.collapsed && (clicked == null || clicked in current.min..current.max) -> current
        clicked != null -> TextSelection(clicked)
        else -> current ?: TextSelection(0)
    }
}

/**
 * The text box right-click menu: text editing commands plus whole-box Copy and Delete.
 * Cut and Copy need a selection and the pastes need text on the clipboard.
 */
@Composable
internal fun TextContextMenu(
    request: TextMenuRequest?,
    state: WorkspaceState,
    clipboard: TextClipboardActions,
    onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    onDeleteBox: (String) -> Unit,
    onClose: () -> Unit,
) {
    // Asked once per opening: reading the system clipboard is not free.
    val canPaste = remember(request) { request != null && clipboard.canPaste() }
    // The keys shown are the ones in force, which Settings → Hardware can change.
    val bindings = LocalKeyBindings.current
    ContextMenu(
        anchor = request?.at,
        onDismiss = {
            onClose()
            request?.let { onEditorCommand { current -> current.selectText(it.selection) } }
        },
    ) {
        val selection = request?.selection ?: TextSelection(0)
        ContextMenuItem(
            label = "Cut",
            icon = DocumentSymbols.ContentCut,
            shortcut = bindings.primary(ShortcutAction.Cut)?.label,
            enabled = !selection.collapsed,
            onClick = {
                onClose()
                clipboard.cut(state, selection)
            },
            modifier = Modifier.testTag(TextMenuTags.Cut),
        )
        ContextMenuItem(
            label = "Copy",
            icon = DocumentSymbols.ContentCopy,
            shortcut = bindings.primary(ShortcutAction.Copy)?.label,
            enabled = !selection.collapsed,
            onClick = {
                onClose()
                clipboard.copy(state, selection)
            },
            modifier = Modifier.testTag(TextMenuTags.Copy),
        )
        ContextMenuItem(
            label = "Paste",
            icon = DocumentSymbols.ContentPaste,
            shortcut = bindings.primary(ShortcutAction.Paste)?.label,
            enabled = canPaste,
            onClick = {
                onClose()
                clipboard.paste(selection, keepFormatting = true)
            },
            modifier = Modifier.testTag(TextMenuTags.Paste),
        )
        ContextMenuItem(
            label = "Paste as plain text",
            icon = ContextSymbols.PasteAsText,
            shortcut = bindings.primary(ShortcutAction.PastePlainText)?.label,
            enabled = canPaste,
            onClick = {
                onClose()
                clipboard.paste(selection, keepFormatting = false)
            },
            modifier = Modifier.testTag(TextMenuTags.PastePlainText),
        )
        ContextMenuDivider()
        ContextMenuItem(
            label = "Select all",
            icon = ContextSymbols.SelectAll,
            shortcut = bindings.primary(ShortcutAction.SelectAll)?.label,
            onClick = {
                onClose()
                request?.let { onEditorCommand { current -> current.selectAllTextBox(it.outlineId) } }
            },
            modifier = Modifier.testTag(TextMenuTags.SelectAll),
        )
        ContextMenuDivider()
        ContextMenuItem(
            label = "Copy text box",
            icon = DocumentSymbols.ContentCopy,
            onClick = {
                onClose()
                request?.let { onEditorCommand { current -> current.copyTextBox(it.outlineId) } }
            },
            modifier = Modifier.testTag(TextMenuTags.CopyBox),
        )
        ContextMenuItem(
            label = "Delete text box",
            icon = ObjectSymbols.Delete,
            destructive = true,
            onClick = {
                onClose()
                request?.let { onDeleteBox(it.outlineId) }
            },
            modifier = Modifier.testTag(TextMenuTags.DeleteBox),
        )
    }
}
