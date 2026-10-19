package com.vivenotes.ui.canvas

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vivenotes.data.ClipboardHtml
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.copySelectedText
import com.vivenotes.workspace.pasteText

/** The platform's [ClipboardHtml], beside Compose's clipboard; none unless the host provides one. */
internal val LocalClipboardHtml = staticCompositionLocalOf<ClipboardHtml?> { null }

/**
 * Copy, cut and paste for the focused text box — one behaviour for the ribbon, the right-click menu
 * and the keyboard shortcuts. The system clipboard gets plain text; the workspace keeps its formatting.
 * Paste also reads the HTML another app copied, through [html], for that app's formatting.
 *
 * Each action takes the range to act on when the caller captured one before a click could move the
 * editor's selection, and [onEditorCommand] hands the keyboard back to the text box afterwards.
 */
internal class TextClipboardActions(
    private val clipboard: ClipboardManager,
    private val onEditorCommand: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val html: ClipboardHtml? = null,
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
        val formatted = if (keepFormatting) html?.read() else null
        onEditorCommand { it.at(selection).pasteText(text, keepFormatting, formatted) }
    }

    fun canPaste(): Boolean = clipboard.hasText()

    private fun WorkspaceState.at(selection: TextSelection?): WorkspaceState = selection?.let(::selectText) ?: this
}
