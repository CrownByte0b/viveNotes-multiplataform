package com.vivenotes.workspace

import com.vivenotes.model.Block
import com.vivenotes.richtext.pastedHtmlBlocks

/**
 * Text copied from a text box: the plain [text] that went to the system clipboard, and the same
 * text as formatted [blocks].
 *
 * The system clipboard only carries plain text, so the formatting stays in the workspace. Pasting
 * checks that the system clipboard still holds [text]: if something else has been copied since, in
 * this app or another, that is what gets pasted.
 */
data class TextClipboard(val text: String, val blocks: List<Block>)

/**
 * Keeps the selection's formatting for [pasteText]. The caller puts [WorkspaceState.selectedText] on
 * the system clipboard. Nothing changes when nothing is selected.
 */
fun WorkspaceState.copySelectedText(): WorkspaceState {
    val fragment = richText?.selectedFragment().orEmpty()
    if (fragment.isEmpty()) return this
    return copy(textClipboard = TextClipboard(selectedText, fragment))
}

/**
 * Pastes [clipboardText] — what the system clipboard holds — over the selection of the focused box.
 *
 * With [keepFormatting], text this workspace copied comes back as it was copied: marks, lists and
 * paragraph styles. Text another app copied with its HTML, [clipboardHtml] — a web page, a document
 * — comes with the headings, lists and marks it showed there, in the font and size at the caret
 * ([pastedHtmlBlocks]). Anything else, and everything when [keepFormatting] is off, is pasted as
 * plain text in the formatting at the caret, as if it had been typed there.
 */
fun WorkspaceState.pasteText(clipboardText: String, keepFormatting: Boolean, clipboardHtml: String? = null): WorkspaceState {
    val text = clipboardText.replace("\r\n", "\n").replace('\r', '\n')
    if (text.isEmpty()) return this
    val buffer = richText ?: return this
    if (keepFormatting) {
        textClipboard?.takeIf { it.text == text }?.let { return withRichText(buffer.insertFragment(it.blocks)) }
        clipboardHtml?.let { pastedHtmlBlocks(it, buffer.activeMarks) }?.let { return withRichText(buffer.insertFormatted(it)) }
    }
    return withRichText(buffer.replace(text))
}
