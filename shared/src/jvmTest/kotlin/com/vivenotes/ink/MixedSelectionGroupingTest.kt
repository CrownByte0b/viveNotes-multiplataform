package com.vivenotes.ink

import com.vivenotes.model.*
import com.vivenotes.workspace.*
import kotlin.test.*

class MixedSelectionGroupingTest {
    private fun workspace(split: Boolean = false): WorkspaceState {
        val state = WorkspaceState.demo()
        val ink = if (split) splitSelectionPage(state.selectedPageId).second else
            InkPageReader.read(state.selectedPageId, listOf(selectionLine("one", state.selectedPageId)), emptyList(), emptyList())
        return state.updatePage(state.selectedPageId) { it.copy(ink = ink, document = PageDoc(outlines = listOf(
            Outline.Shape("shape", x = 150f, y = 200f, width = 40f, height = 40f),
            newTable(2, 2, x = 250f, y = 200f).copy(id = "table"),
        ))) }
    }

    @Test
    fun oneStrokeGroupsWithShapesAndTablesAndSelectingEitherSideMovesEverything() {
        val original = workspace()
        val selected = original.selectObjectsInRect(0f, 180f, 600f, 400f)
        val grouped = selected.toggleSelectionGroup()
        assertTrue(grouped.selectionGrouped)
        assertEquals(grouped.selectedInk!!.groupId, grouped.selectedPrimeObjects.first().groupId)
        assertEquals(1, grouped.structuralUndo.size)
        for (held in listOf(grouped.selectPointer().selectObject("table"), grouped.selectPointer().selectInkAt(40f, 200f))) {
            assertEquals(setOf("shape", "table"), held.selectedObjectIds)
            assertEquals(setOf("one"), held.selectedInk!!.targetIds)
            val moved = held.applyInkSelectionChange(held.selectedInk!!.move(20f, 30f)!!)
            assertEquals(170f, moved.selectedPrimeObjects.first().x)
            assertEquals(230f, moved.selectedPrimeObjects.first().y)
            assertEquals(230f, moved.selectedInk!!.bounds.let { (it.top + it.bottom) / 2f })
            assertEquals(2, moved.structuralUndo.size)
            val ungrouped = moved.toggleSelectionGroup()
            assertFalse(ungrouped.selectionGrouped)
            assertNull(ungrouped.selectedInk!!.groupId)
            assertTrue(ungrouped.selectedPrimeObjects.all { it.groupId == null })
            assertEquals(setOf("table"), ungrouped.selectPointer().selectObject("table").selectedObjectIds)
            assertNull(ungrouped.selectPointer().selectObject("table").selectedInk)
        }
        val undone = grouped.undoStructure()
        assertEquals(original.selectedPage!!.document, undone.selectedPage!!.document)
        assertNull(undone.selectedPage!!.ink!!.geometry!!.selectAt(undone.selectedPage!!.ink!!, 40f, 200f)!!.groupId)
        val redone = undone.redoStructure().selectObject("shape")
        assertTrue(redone.selectionGrouped)
        assertNotNull(redone.selectedInk)
    }

    @Test
    fun aSingleCutPieceCanGroupWithoutPullingInItsUnselectedSibling() {
        val original = workspace(split = true)
        val sibling = original.selectedPage!!.ink!!.desktopGeometry().projections.single { it.pageBounds!!.left > 60f }
        val selected = original.selectObjectsInRect(0f, 180f, 55f, 220f).copy(selectedObjectIds = setOf("shape"))
        val grouped = selected.toggleSelectionGroup()
        assertTrue(grouped.selectionGrouped)
        assertEquals(1, grouped.selectedInk!!.targetIds.size)
        val held = grouped.selectPointer().selectObject("shape")
        val moved = held.applyInkSelectionChange(held.selectedInk!!.move(20f, 30f)!!)
        assertEquals(sibling.pageBounds, moved.selectedPage!!.ink!!.desktopGeometry().projections.single { it.id == sibling.id }.pageBounds)
        assertFalse(moved.selectPointer().selectInkAt(90f, 200f).selectionGrouped)
        val undone = grouped.undoStructure()
        assertEquals(original.selectedPage!!.ink!!.desktopGeometry().projections.map { it.pageBounds },
            undone.selectedPage!!.ink!!.desktopGeometry().projections.map { it.pageBounds })
    }

    @Test
    fun pastedMixedGroupsRemainTogetherAndIndependentOfTheOriginalAndLaterPastes() {
        val grouped = workspace().selectObjectsInRect(0f, 180f, 600f, 400f).toggleSelectionGroup()
        val pasted = grouped.copySelectedObjects().pasteCanvasAt(20f, 500f)
        assertTrue(pasted.selectionGrouped)
        assertNotEquals(grouped.selectedInk!!.groupId, pasted.selectedInk!!.groupId)
        val shape = pasted.selectedPrimeObjects.filterIsInstance<Outline.Shape>().single()
        val held = pasted.selectPointer().selectObject(shape.id)
        assertEquals(pasted.selectedInk!!.targetIds, held.selectedInk!!.targetIds)
        assertEquals(pasted.selectedObjectIds, held.selectedObjectIds)
        val again = pasted.pasteCanvasAt(20f, 800f)
        assertTrue(again.selectionGrouped)
        assertNotEquals(pasted.selectedInk!!.groupId, again.selectedInk!!.groupId)
        assertEquals(grouped.selectedObjectIds, again.selectPointer().selectInkAt(40f, 200f).selectedObjectIds)
    }
}
