package com.vivenotes.ui.ribbon.draw

import com.vivenotes.model.PageDoc
import com.vivenotes.model.SpaceCut
import com.vivenotes.model.ink.InkPage
import com.vivenotes.model.ink.InkSelectionChange
import com.vivenotes.workspace.WorkspaceState

data class SpaceInsertion(val pageId: String, val document: PageDoc, val ink: InkPage?,
    val cut: SpaceCut, val outlineIds: Set<String>, val change: InkSelectionChange?)

internal fun prepareSpaceInsertion(state: WorkspaceState, requested: SpaceCut): SpaceInsertion? {
    if (requested.isEmpty || !requested.at.isFinite() || !requested.amount.isFinite()) return null
    val page = state.selectedPage?.takeIf { it.editable && it.inkReady } ?: return null
    val outlines = page.document.outlines.filter { requested.moves(it.x, it.y) }
    val ink = page.ink
    val selected = ink?.geometry?.selectPastLine(ink, requested)
    val near = (outlines.map { requested.nearEdge(it.x, it.y) } +
        listOfNotNull(selected?.bounds?.let { requested.nearEdge(it.left, it.top) })).minOrNull()
    if (near == null) return null
    val cut = requested.limitedTo(near)
    if (cut.isEmpty) return null
    val change = selected?.move(cut.dx, cut.dy)
    return SpaceInsertion(page.id, page.document, ink, cut, outlines.map { it.id }.toSet(), change)
}
