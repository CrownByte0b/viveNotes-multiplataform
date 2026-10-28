package com.vivenotes.ui.shell

import com.vivenotes.model.Mark
import com.vivenotes.ui.canvas.TextClipboardActions
import com.vivenotes.ui.ribbon.view.ViewActions
import com.vivenotes.workspace.NavigationActions
import com.vivenotes.workspace.ShortcutAction
import com.vivenotes.workspace.StructuralSnapshot
import com.vivenotes.workspace.WorkspaceState

/**
 * What each keyboard shortcut does in the workspace — the same commands the header, ribbon and
 * right-click menus run. Returns whether the key was used: on the canvas, an undo with nothing to
 * undo leaves the key to whatever else wants it.
 */
internal class WorkspaceShortcuts(
    private val state: () -> WorkspaceState,
    private val onStateChange: ((WorkspaceState) -> WorkspaceState) -> Unit,
    /** Applies a text command and hands the keyboard back to the text box. */
    private val onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val navigation: NavigationActions,
    private val view: ViewActions,
    private val clipboard: TextClipboardActions,
    /** Pastes the canvas clipboard where Ctrl+V puts it; false when there was nothing to paste. */
    private val canvasPaste: () -> Boolean = { false },
    private val onSearch: (() -> Unit)? = null,
) {
    fun run(action: ShortcutAction): Boolean {
        val current = state()
        val selected = !current.editorSelection.collapsed
        // Outside a text box, Cut, Copy and Paste act on the canvas's objects and its clipboard.
        val onCanvas = current.focusedTextOutlineId == null
        when (action) {
            ShortcutAction.Search -> onSearch?.invoke() ?: return false
            ShortcutAction.NewPage -> navigation.addPage()
            // In a text box the key is used up even with nothing to undo: the text field's own undo
            // would put back characters without their formatting.
            ShortcutAction.Undo -> if (!undo()) return !onCanvas
            ShortcutAction.Redo -> if (!redo()) return !onCanvas
            ShortcutAction.DeleteSelection -> {
                if (!current.hasObjectSelection) return false
                onStateChange { it.deleteSelectedObjects() }
            }
            // With nothing selected in a text box these still use the key, as the text field's own would.
            ShortcutAction.Cut -> when {
                !onCanvas -> if (selected) clipboard.cut(current, null)
                current.hasObjectSelection -> onStateChange { it.cutSelectedObjects() }
                else -> return false
            }
            ShortcutAction.Copy -> when {
                !onCanvas -> if (selected) clipboard.copy(current, null)
                current.hasObjectSelection -> onStateChange { it.copySelectedObjects() }
                else -> return false
            }
            ShortcutAction.Paste -> if (onCanvas) return canvasPaste()
                else clipboard.paste(null, keepFormatting = true)
            ShortcutAction.PastePlainText -> clipboard.paste(null, keepFormatting = false)
            ShortcutAction.SelectAll -> current.focusedTextOutlineId?.let { id ->
                onEditorCommand { it.selectAllTextBox(id) }
            }
            ShortcutAction.ZoomIn -> view.zoomIn()
            ShortcutAction.ZoomOut -> view.zoomOut()
            ShortcutAction.ActualSize -> view.setZoom(1f)
            ShortcutAction.SelectTool -> onStateChange { it.selectPointer() }
            ShortcutAction.TextTool -> onStateChange { it.toggleTextTool() }
            ShortcutAction.Bold -> onEditorCommand { it.toggleSelectedMark(Mark.Bold) }
            ShortcutAction.Italic -> onEditorCommand { it.toggleSelectedMark(Mark.Italic) }
            ShortcutAction.Underline -> onEditorCommand { it.toggleSelectedMark(Mark.Underline) }
            ShortcutAction.Indent -> onStateChange { it.indentSelectedText(1) }
            ShortcutAction.Outdent -> onStateChange { it.indentSelectedText(-1) }
            ShortcutAction.ExitList -> onStateChange { it.insertPlainParagraph() }
        }
        return true
    }

    /** Undo, from the keyboard or the header; false when there is nothing to undo. */
    fun undo(): Boolean = step(state().structuralUndo.lastOrNull()) { it.undoStructure() }

    fun redo(): Boolean = step(state().structuralRedo.lastOrNull()) { it.redoStructure() }

    private fun step(next: StructuralSnapshot?, transform: (WorkspaceState) -> WorkspaceState): Boolean {
        next ?: return false
        // A text step leaves its box being edited, with the caret where the text changed. A step from
        // another page undoes nothing here, and leaves no box to give the keyboard to.
        if (next.text != null && next.pageId == state().selectedPageId) onEditorCommand(transform)
        else onStateChange(transform)
        return true
    }
}
