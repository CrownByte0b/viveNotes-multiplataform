package com.vivenotes.ink

import com.vivenotes.model.*
import com.vivenotes.model.ink.InkPage
import com.vivenotes.ui.ribbon.draw.prepareSpaceInsertion
import com.vivenotes.workspace.*
import kotlin.test.*

class InsertSpaceTest {
    @Test
    fun insertingSpaceLeavesLockedOutlinesInPlaceAlongsideMovingInkAndText() {
        val base = workspace()
        val pinned = Outline.Image("pinned", 200f, 230f, 80f, attachmentId = "attachment", height = 60f, lockGroup = "group")
        val initial = base.updatePage(base.selectedPageId) {
            it.copy(document = it.document.copy(outlines = it.document.outlines + pinned))
        }
        val plan = assertNotNull(prepareSpaceInsertion(initial, SpaceCut(PageSpace.Axis.Vertical, 200f, 40f)))
        assertTrue(pinned.id !in plan.outlineIds)
        val moved = initial.applySpaceInsertion(plan)
        assertEquals(pinned, moved.selectedPage!!.document.outlines.last())
        assertEquals(280f, moved.selectedPage!!.document.outlines.first().y)
        assertEquals(initial.selectedPage!!.document, moved.undoStructure().selectedPage!!.document)
    }

    private fun workspace(): WorkspaceState {
        val base = WorkspaceState.demo().selectDrawPlacement(DrawPlacementTool.InsertSpace)
        val rows = listOf(selectionLine("before", base.selectedPageId, y = 150f), selectionLine("after", base.selectedPageId, y = 250f))
        val ink = InkPageReader.read(base.selectedPageId, rows, emptyList(), emptyList())
        return base.updatePage(base.selectedPageId) { it.copy(ink = ink, document = PageDoc(outlines = listOf(
            Outline.Text("text", 200f, 240f, blocks = listOf(Block.of("Move with ink"))),
            Outline.Equation("formula", 200f, 160f, latex = "x^2")))) }
    }

    @Test
    fun openingAndClosingSpaceMoveInkAndOutlinesByTheSameLimitedDeltaWithOneUndo() {
        for (amount in listOf(40f, -200f)) {
            val initial = workspace()
            val plan = assertNotNull(prepareSpaceInsertion(initial, SpaceCut(PageSpace.Axis.Vertical, 200f, amount)))
            val delta = if (amount > 0) 40f else -40f
            assertEquals(delta, plan.cut.dy)
            val moved = initial.applySpaceInsertion(plan)
            assertEquals(240f + delta, moved.selectedPage!!.document.outlines.first().y)
            assertEquals(160f, moved.selectedPage!!.document.outlines.last().y)
            val projections = moved.selectedPage!!.ink!!.desktopGeometry().projections
            assertEquals(150f, projections.single { it.id == "before" }.stroke.inputs[0].y)
            assertEquals(delta, projections.single { it.id == "after" }.offsetY)
            assertEquals(1, moved.structuralUndo.size)
            assertEquals(1, moved.pendingInkEdits.size)
            val undone = moved.undoStructure()
            assertEquals(initial.selectedPage!!.document, undone.selectedPage!!.document)
            assertEquals(0f, undone.selectedPage!!.ink!!.desktopGeometry().projections.single { it.id == "after" }.offsetY)
            assertEquals(moved.selectedPage!!.document, undone.redoStructure().selectedPage!!.document)
            assertNull(moved.selectedInk)
        }
    }

    @Test
    fun aPartiallyErasedRowAndAnInkGroupStraddlingTheLineRemainWhole() {
        val (cut, split) = splitSelectionPage()
        assertEquals(2, split.desktopGeometry().projections.size)
        assertNull(split.geometry!!.selectPastLine(split, SpaceCut(PageSpace.Axis.Horizontal, 60f, 20f)))
        val rows = listOf(selectionLine("left", "page", 150f, "word"), selectionLine("right", "page", 250f, "word"))
        val grouped = InkPageReader.read("page", rows, emptyList(), emptyList())
        assertNull(grouped.geometry!!.selectPastLine(grouped, SpaceCut(PageSpace.Axis.Vertical, 200f, 20f)))
        assertEquals(setOf("left", "right"), grouped.geometry!!.selectPastLine(grouped, SpaceCut(PageSpace.Axis.Vertical, 100f, 20f))!!.targetIds)
    }

    @Test
    fun staleReadOnlyEmptyAndZeroSpaceEditsAreRejected() {
        val initial = workspace()
        val plan = prepareSpaceInsertion(initial, SpaceCut(PageSpace.Axis.Vertical, 200f, 40f))!!
        assertSame(initial.selectPointer().selectedPage, initial.selectPointer().applySpaceInsertion(plan).selectedPage)
        val changed = initial.updatePage(initial.selectedPageId) { it.copy(document = it.document.copy(outlines = emptyList())) }
        assertSame(changed, changed.applySpaceInsertion(plan))
        assertNull(prepareSpaceInsertion(initial, SpaceCut(PageSpace.Axis.Vertical, 200f, 0f)))
        assertNull(prepareSpaceInsertion(initial, SpaceCut(PageSpace.Axis.Vertical, 1000f, 40f)))
    }
}
