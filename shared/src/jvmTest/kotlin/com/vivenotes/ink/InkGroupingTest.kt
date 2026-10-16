package com.vivenotes.ink

import com.vivenotes.data.InkEdit
import com.vivenotes.model.PageDoc
import com.vivenotes.workspace.WorkspaceState
import kotlin.test.*

class InkGroupingTest {
    @Test
    fun groupingPersistsOneMovableObjectAndUngroupingKeepsTheExpandedSelectionMovable() {
        val rows = listOf(selectionLine("one", group = "old"), selectionLine("two", y = 240f, group = "old"),
            selectionLine("three", y = 280f), selectionLine("outside", y = 500f))
        val page = InkPageReader.read("page", rows, emptyList(), emptyList())
        val selected = page.geometry!!.selectInRect(page, 0f, 180f, 120f, 300f)!!
        val change = assertNotNull(selected.group())
        val sources = change.after.desktopGeometry().sourceStrokes
        val group = sources.first().groupId
        assertNotNull(group)
        assertNotEquals("old", group)
        assertTrue(sources.filter { it.id != "outside" }.all { it.groupId == group })
        assertNull(sources.single { it.id == "outside" }.groupId)
        assertEquals(page.desktopGeometry().projections.map { it.pageBounds }, change.after.desktopGeometry().projections.map { it.pageBounds })
        assertEquals(page.desktopGeometry().sourceStrokes.map { it.stroke.inputs }, sources.map { it.stroke.inputs })
        val grouped = change.after.geometry!!.selectInRect(change.after, 0f, 190f, 120f, 210f)!!
        assertEquals(setOf("one", "two", "three"), grouped.targetIds)
        assertTrue(grouped.isGrouped)
        assertNull(grouped.group())
        val ungrouped = assertNotNull(grouped.ungroup())
        assertFalse(ungrouped.selection!!.isGrouped)
        assertEquals(setOf("one"), ungrouped.after.geometry!!.selectAt(ungrouped.after, 40f, 200f)!!.targetIds)
        val move = assertNotNull(ungrouped.selection!!.move(20f, 30f))
        assertEquals(mapOf("one" to 230f, "two" to 270f, "three" to 310f, "outside" to 500f), move.after.desktopGeometry().projections.associate {
            val bounds = it.pageBounds!!
            it.id to (bounds.top + bounds.bottom) / 2f
        })
        val restored = change.after.withDesktopEdits(change.undo)
        assertEquals(mapOf("one" to "old", "two" to "old", "three" to null, "outside" to null),
            restored.desktopGeometry().sourceStrokes.associate { it.id to it.groupId })
        val redone = restored.withDesktopEdits(change.redo)
        assertTrue(redone.geometry!!.selectAt(redone, 40f, 200f)!!.isGrouped)
    }

    @Test
    fun groupingSelectedFragmentsLeavesTheirUnselectedSiblingOutsideTheGroupAndMove() {
        val (_, split) = splitSelectionPage()
        val page = split.withDesktopEdit(InkEdit.AddStroke(selectionLine("second", y = 240f, endX = 45f)))
        val selected = page.geometry!!.selectInRect(page, 0f, 180f, 55f, 260f)!!
        val sibling = page.desktopGeometry().projections.single { it.pageBounds!!.left > 60f }
        val change = assertNotNull(selected.group())
        assertEquals(3, change.after.desktopGeometry().projections.size)
        val group = change.after.geometry!!.selectAt(change.after, 30f, 200f)!!
        assertTrue(group.isGrouped)
        assertEquals(2, group.targetIds.size)
        assertTrue("source" !in group.targetIds)
        val ungrouped = assertNotNull(group.ungroup())
        assertEquals(2, ungrouped.selection!!.forRecognition()!!.desktopGeometry().projections.size)
        val untouched = change.after.desktopGeometry().projections.single { it.id == "source" }
        assertEquals(sibling.pageBounds, untouched.pageBounds)
        assertNull(untouched.groupId)
        val moved = assertNotNull(group.move(20f, 30f))
        assertEquals(sibling.pageBounds, moved.after.desktopGeometry().projections.single { it.id == "source" }.pageBounds)
        val restored = change.after.withDesktopEdits(change.undo)
        assertEquals(page.desktopGeometry().projections.map { it.pageBounds }, restored.desktopGeometry().projections.map { it.pageBounds })
        assertEquals(page.desktopGeometry().sourceStrokes.map { it.id }, restored.desktopGeometry().sourceStrokes.map { it.id })
        val redone = restored.withDesktopEdits(change.redo)
        assertEquals(change.after.desktopGeometry().projections.map { it.pageBounds }, redone.desktopGeometry().projections.map { it.pageBounds })
        assertEquals(2, redone.geometry!!.selectAt(redone, 30f, 200f)!!.targetIds.size)
    }

    @Test
    fun groupingIsOneHistoryStepAndRejectsStaleMixedAndReadOnlySelections() {
        val initial = WorkspaceState.demo()
        val ink = InkPageReader.read(initial.selectedPageId, listOf(selectionLine("one", initial.selectedPageId),
            selectionLine("two", initial.selectedPageId, 240f)), emptyList(), emptyList())
        val base = initial.updatePage(initial.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList()), ink = ink) }
        val selected = base.selectObjectsInRect(0f, 180f, 120f, 260f)
        val grouped = selected.toggleInkGroup()
        assertTrue(grouped.selectedInk!!.isGrouped)
        assertEquals(1, grouped.structuralUndo.size)
        assertEquals(ink, grouped.undoStructure().selectedPage!!.ink)
        assertTrue(grouped.undoStructure().redoStructure().selectedPage!!.ink!!.geometry!!.selectAt(
            grouped.undoStructure().redoStructure().selectedPage!!.ink!!, 40f, 200f)!!.isGrouped)
        assertFalse(grouped.toggleInkGroup().selectedInk!!.isGrouped)
        val change = selected.selectedInk!!.group()!!
        val stale = selected.selectPointer()
        assertSame(stale, stale.applyInkGrouping(change))
        val mixed = selected.copy(selectedObjectIds = setOf("outline"))
        assertSame(mixed, mixed.applyInkGrouping(change))
        val readOnly = selected.updatePage(selected.selectedPageId) { it.copy(content = com.vivenotes.workspace.PageContent.Unreadable) }
        assertSame(readOnly, readOnly.applyInkGrouping(change))
    }

    @Test
    fun recognitionSnapshotContainsOnlyTheHeldVisibleMovedPiece() {
        val (_, split) = splitSelectionPage()
        val held = split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f)!!.move(20f, 40f)!!.selection!!
        val snapshot = assertNotNull(held.forRecognition())
        val piece = snapshot.desktopGeometry().projections.single()
        assertEquals(held.bounds.left, piece.pageBounds!!.left)
        assertEquals(held.bounds.top, piece.pageBounds!!.top)
        assertTrue(piece.pageBounds!!.right < 80f)
        assertEquals(2, held.page.desktopGeometry().projections.size)
        assertEquals(split.strokes.single().samples, snapshot.strokes.single().samples)
    }
}
