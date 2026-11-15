package com.vivenotes.workspace

import com.vivenotes.model.Outline
import com.vivenotes.model.newId

private val WorkspaceState.selectedGroupOutlines: List<Outline> get() =
    selectedPage?.document?.outlines.orEmpty().filter {
        it.id in selectedObjectIds && it.isPrimeObject() || it is Outline.Text && it.id in selectedTextOutlineIds
    }

val WorkspaceState.selectionGrouped: Boolean get() {
    val outlines = selectedGroupOutlines
    val group = outlines.firstOrNull()?.groupId ?: selectedInk?.groupId ?: return false
    return hasObjectSelection && outlines.size == selectedObjectIds.size + selectedTextOutlineIds.size &&
        outlines.all { it.groupId == group } && (selectedInk == null || selectedInk.groupId == group)
}

val WorkspaceState.canGroupSelection: Boolean get() =
    selectedGroupOutlines.size == selectedObjectIds.size + selectedTextOutlineIds.size &&
        (selectionGrouped || selectedGroupOutlines.size + (selectedInk?.targetIds?.size ?: 0) > 1)

/** One history step for the document objects and any ink whose stored group changes with them. */
fun WorkspaceState.toggleSelectionGroup(): WorkspaceState {
    val page = selectedPage ?: return this
    if (!page.editable || !page.inkReady || notebookTransfer.running || filePane.busy || !canGroupSelection ||
        selectedInk?.page?.let { it !== page.ink } == true) return this
    val group = if (selectionGrouped) null else newId()
    val ids = selectedObjectIds + selectedTextOutlineIds
    val document = page.document.copy(outlines = page.document.outlines.map {
        if (it.id in ids) it.withGroup(group) else it
    })
    val ink = selectedInk
    if (ink == null) return editOutlines { document.outlines }
    val change = (if (group == null) ink.ungroup() else ink.groupWith(group)) ?: return this
    val changed = applyInkSelectionChange(change)
    if (changed === this) return this
    val history = changed.structuralUndo.last()
    return changed.updatePage(page.id) { it.withDocument(document) }.copy(
        structuralUndo = changed.structuralUndo.dropLast(1) + history.copy(document = page.document,
            ink = history.ink!!.copy(afterDocument = document)),
    )
}

/** Publishes worker preparation only while the page and selection it used are still current. */
fun WorkspaceState.applyPreparedSelectionGrouping(before: WorkspaceState, prepared: WorkspaceState): WorkspaceState {
    val page = selectedPage ?: return this
    val old = before.selectedPage ?: return this
    val next = prepared.selectedPage ?: return this
    if (prepared === before || !page.editable || !page.inkReady || notebookTransfer.running || filePane.busy ||
        page.id != old.id || next.id != page.id || page.document !== old.document || page.ink !== old.ink ||
        selectedObjectIds != before.selectedObjectIds || selectedTextOutlineIds != before.selectedTextOutlineIds ||
        selectedInk !== before.selectedInk || inkTool != before.inkTool || drawPlacement != before.drawPlacement ||
        textToolArmed != before.textToolArmed || shapeToolArmed != before.shapeToolArmed ||
        objectLassoArmed != before.objectLassoArmed || structuralUndo != before.structuralUndo ||
        structuralRedo != before.structuralRedo ||
        prepared.structuralUndo.size != (before.structuralUndo.size + 1).coerceAtMost(100)) return this
    val pending = before.pendingInkEdits.mapTo(HashSet()) { it.id }
    return updatePage(page.id) { it.withDocument(next.document).copy(ink = next.ink) }.copy(
        selectedInk = prepared.selectedInk,
        pendingInkEdits = pendingInkEdits.queueInk(prepared.pendingInkEdits.filter { it.id !in pending }),
        structuralUndo = prepared.structuralUndo, structuralRedo = emptyList(), typingRun = null,
    )
}

/** A tap or lasso reaching any member holds every member, including linked ink outside its bounds. */
internal fun WorkspaceState.expandSelectionGroups(): WorkspaceState {
    val page = selectedPage ?: return this
    val outlines = page.document.outlines
    val ids = (selectedObjectIds + selectedTextOutlineIds).toMutableSet()
    val groups = selectedInk?.groupIds.orEmpty().toMutableSet()
    val locks = mutableSetOf<String>()
    do {
        val previousSize = ids.size
        outlines.filter { it.id in ids }.forEach {
            it.groupId?.let(groups::add)
            it.lockGroup?.let(locks::add)
        }
        outlines.filter { (it.isPrimeObject() || it is Outline.Text) &&
            (it.groupId in groups || it.lockGroup in locks) }.forEach { ids.add(it.id) }
    } while (ids.size != previousSize)
    val ink = page.ink
    val selection = if (ink != null && groups.any { it !in selectedInk?.groupIds.orEmpty() })
        ink.geometry?.selectGroups(ink, groups, selectedInk) ?: selectedInk else selectedInk
    return copy(selectedObjectIds = outlines.filter { it.isPrimeObject() && it.id in ids }.mapTo(LinkedHashSet()) { it.id },
        selectedTextOutlineIds = outlines.filterIsInstance<Outline.Text>().filter { it.id in ids }.mapTo(LinkedHashSet()) { it.id },
        selectedInk = selection)
}
