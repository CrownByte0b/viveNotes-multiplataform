package com.vivenotes.search

import com.vivenotes.model.Outline
import com.vivenotes.model.search.ContentHit
import com.vivenotes.model.search.ContentKind
import com.vivenotes.richtext.TextSelection
import com.vivenotes.workspace.WorkspaceState

/** Revalidates the target against the current document, which may have changed since the query. */
fun WorkspaceState.revealSearchHit(hit: ContentHit): WorkspaceState {
    if (selectedPageId != hit.unit.pageId || selectedPage?.editable != true) return this
    return when (hit.unit.kind) {
        ContentKind.Title -> selectPointer()
        ContentKind.Text -> {
            val outline = selectedPage?.document?.outlines?.filterIsInstance<Outline.Text>()
                ?.firstOrNull { it.id == hit.unit.boxId } ?: return this
            val text = outline.blocks.joinToString("\n") { it.editorText }
            if (hit.editorStart < 0 || hit.editorEnd > text.length) return this
            focusTextBox(outline.id).selectText(TextSelection(hit.editorStart, hit.editorEnd))
        }
        ContentKind.Ink -> selectPointer()
        ContentKind.Image -> selectPointer().selectObject(hit.unit.boxId)
        ContentKind.Cell -> selectPointer().selectObject(hit.unit.tableId ?: return this)
    }
}
