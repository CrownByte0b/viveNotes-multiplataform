package com.vivenotes.ui.shell

import com.vivenotes.model.Mark
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.ribbon.view.ViewActions
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.WorkspaceState

/**
 * What each keyboard shortcut does in the workspace — the same commands the header, ribbon and
 * right-click menus run. Returns whether the key was used: an undo with nothing to undo leaves the
 * key to whatever else wants it.
 */
internal class WorkspaceShortcuts(
    private val state: () -> WorkspaceState,
    private val onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    /** Applies a text command and hands the keyboard back to the text box. */
    private val onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val navigation: NavigationActions,
    private val view: ViewActions,
    private val clipboard: TextClipboardActions,
) {
    fun run(action: ShortcutAction): Boolean {
        val current = state()
        val selected = !current.editorSelection.collapsed
        when (action) {
            ShortcutAction.NewPage -> navigation.addPage()
            ShortcutAction.Undo -> if (current.structuralUndo.isEmpty()) return false
                else onStateChange { it.undoStructure() }
            ShortcutAction.Redo -> if (current.structuralRedo.isEmpty()) return false
                else onStateChange { it.redoStructure() }
            // With nothing selected these still use the key, as the text field's own would.
            ShortcutAction.Cut -> if (selected) clipboard.cut(current, null)
            ShortcutAction.Copy -> if (selected) clipboard.copy(current, null)
            ShortcutAction.Paste -> clipboard.paste(null, keepFormatting = true)
            ShortcutAction.PastePlainText -> clipboard.paste(null, keepFormatting = false)
            ShortcutAction.SelectAll -> current.focusedTextOutlineId?.let { id ->
                onEditorCommand { it.selectAllTextBox(id) }
            }
            ShortcutAction.ZoomIn -> view.zoomIn()
            ShortcutAction.ZoomOut -> view.zoomOut()
            ShortcutAction.ActualSize -> view.setZoom(1f)
            ShortcutAction.SelectTool -> onStateChange { it.selectPointer() }
            ShortcutAction.Bold -> onEditorCommand { it.toggleSelectedMark(Mark.Bold) }
            ShortcutAction.Italic -> onEditorCommand { it.toggleSelectedMark(Mark.Italic) }
            ShortcutAction.Underline -> onEditorCommand { it.toggleSelectedMark(Mark.Underline) }
            ShortcutAction.Indent -> onStateChange { it.indentSelectedText(1) }
            ShortcutAction.Outdent -> onStateChange { it.indentSelectedText(-1) }
        }
        return true
    }
}
