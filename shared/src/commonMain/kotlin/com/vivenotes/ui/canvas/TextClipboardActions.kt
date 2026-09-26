package com.vivenotes.ui.canvas

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.copySelectedText
import com.vivenotes.workspace.pasteText

/**
 * Copy, cut and paste for the focused text box — one behaviour for the ribbon, the right-click menu
 * and the keyboard. The system clipboard gets plain text; the workspace keeps its formatting.
 *
 * Each action takes the range to act on when the caller captured one before a click could move the
 * editor's selection, and [onEditorCommand] hands the keyboard back to the text box afterwards.
 */
internal class TextClipboardActions(
    private val clipboard: ClipboardManager,
    private val onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    /** [state] is what is on screen: the clipboard takes the text the user can see selected. */
    fun copy(state: WorkspaceState, selection: TextSelection?) {
        clipboard.setText(AnnotatedString(state.at(selection).selectedText))
        onEditorCommand { it.at(selection).copySelectedText() }
    }

    fun cut(state: WorkspaceState, selection: TextSelection?) {
        clipboard.setText(AnnotatedString(state.at(selection).selectedText))
        onEditorCommand { it.at(selection).copySelectedText().replaceSelectedText("") }
    }

    fun paste(selection: TextSelection?, keepFormatting: Boolean) {
        val text = clipboard.getText()?.text ?: return
        onEditorCommand { it.at(selection).pasteText(text, keepFormatting) }
    }

    fun canPaste(): Boolean = clipboard.hasText()

    /**
     * Ctrl+C, Ctrl+X, Ctrl+V, and Ctrl+Shift+V for paste without formatting, in a text box. True
     * when [event] was one of them, which the text field must then not handle itself.
     */
    fun onShortcut(event: KeyEvent, state: WorkspaceState): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
        val selected = state.editorSelection.let { !it.collapsed }
        when (event.key) {
            Key.C -> if (selected) copy(state, null)
            Key.X -> if (selected) cut(state, null)
            Key.V -> paste(null, keepFormatting = !event.isShiftPressed)
            else -> return false
        }
        return true
    }

    private fun WorkspaceState.at(selection: TextSelection?): WorkspaceState = selection?.let(::selectText) ?: this
}
