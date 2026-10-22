package com.vivenotes.workspace

import com.vivenotes.model.ink.InkPage

/** Accepts an equivalent native representation without creating an edit, history or storage work. */
internal fun WorkspaceState.applyPreparedInkGeometry(source: InkPage, prepared: InkPage): WorkspaceState {
    val page = selectedPage ?: return this
    if (page.id != source.pageId || page.ink !== source || !page.editable || !page.inkReady ||
        notebookTransfer.running || filePane.busy || selectedInk != null) return this
    if (prepared.pageId != source.pageId || prepared.strokes !== source.strokes || prepared.operations !== source.operations ||
        prepared.latestOperationAt != source.latestOperationAt || prepared.geometry == null || prepared.geometry === source.geometry) return this
    if (prepared.geometry.rightDp.toBits() != source.geometry?.rightDp?.toBits() ||
        prepared.geometry.bottomDp.toBits() != source.geometry?.bottomDp?.toBits()) return this
    return updatePage(page.id) { it.copy(ink = prepared) }
}
