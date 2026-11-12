package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import com.vivenotes.richtext.TextSelection
import kotlin.test.*

/** The prime object right-click menu's Cut, Copy and Delete (`diagram.md`, Prime Object Class). */
class ObjectCommandsTest {
    private val shape = Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
        segments = seedSegments(ShapeKind.Rectangle, 100f, 220f, 180f, 280f, ::newId)).withRecomputedBounds()
    private val table = newTable(2, 2, x = 230f, y = 220f, inkOnly = false).copy(id = "table")
    private val equation = Outline.Equation("equation", 600f, 220f, 80f, 60f, "x^2")
    private val image = Outline.Image("image", 750f, 220f, 80f, attachmentId = "attachment", height = 60f)
    private val objects = listOf(shape, table, equation, image)

    private fun workspace(outlines: List<Outline> = objects): WorkspaceState {
        val state = WorkspaceState.demo()
        return state.updatePage(state.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }
    }

    private val WorkspaceState.outlines get() = selectedPage!!.document.outlines

    @Test
    fun eachCommandActsOnTheSelectionAsItsKeyDoesForEveryKind() {
        for (outline in objects) {
            val held = workspace().selectObject(outline.id)
            assertEquals(held.copySelectedObjects(), held.runObjectCommand(ObjectCommand.Copy))
            assertEquals(held.cutSelectedObjects(), held.runObjectCommand(ObjectCommand.Cut))
            assertEquals(held.deleteSelectedObjects(), held.runObjectCommand(ObjectCommand.Delete))

            val cut = held.runObjectCommand(ObjectCommand.Cut)
            assertEquals(listOf(outline), cut.canvasClipboard.objects)
            assertEquals(objects - outline, cut.outlines)
            assertEquals(held.structuralUndo.size + 1, cut.structuralUndo.size, "cut is one undo step")
            assertEquals(objects, cut.undoStructure().outlines)
        }
    }

    @Test
    fun aSelectionOfSeveralIsCutCopiedAndDeletedWhole() {
        val text = Outline.Text("text", 100f, 400f, blocks = listOf(Block.of("Written text")))
        val held = workspace(objects + text).selectObjectsInRect(80f, 200f, 850f, 600f)
        assertEquals(objects.map { it.id }.toSet(), held.selectedObjectIds)

        val copied = held.runObjectCommand(ObjectCommand.Copy)
        assertEquals(objects, copied.canvasClipboard.objects)
        assertEquals(listOf(text), copied.canvasClipboard.texts)
        assertEquals(held.outlines, copied.outlines)
        assertEquals(held.selectedObjectIds, copied.selectedObjectIds)

        assertTrue(held.runObjectCommand(ObjectCommand.Delete).outlines.isEmpty())
        assertTrue(held.runObjectCommand(ObjectCommand.Cut).outlines.isEmpty())
    }

    /** Lock means stays put, not protected: the menu works on a locked group as on any other. */
    @Test
    fun lockedObjectsAreCutCopiedAndDeleted() {
        val held = workspace(objects.map { it.withLockGroup("pinned") }).selectObject("shape")
        assertTrue(held.selectedObjectsLocked)
        assertEquals(4, held.runObjectCommand(ObjectCommand.Copy).canvasClipboard.objects.size)
        assertTrue(held.runObjectCommand(ObjectCommand.Delete).outlines.isEmpty())
        val cut = held.runObjectCommand(ObjectCommand.Cut)
        assertTrue(cut.outlines.isEmpty())
        assertEquals(4, cut.canvasClipboard.objects.size)
    }

    @Test
    fun aPageThatCannotBeEditedIsCopiedFromButNotCutOrDeleted() {
        val held = workspace().selectObject("shape")
        val standIn = held.updatePage(held.selectedPageId) { it.copy(content = PageContent.Unreadable) }
        assertSame(standIn, standIn.runObjectCommand(ObjectCommand.Cut))
        assertSame(standIn, standIn.runObjectCommand(ObjectCommand.Delete))
        assertSame(standIn, standIn.runObjectCommand(ObjectCommand.Delete, "table"))
        assertEquals(listOf(shape), standIn.runObjectCommand(ObjectCommand.Copy).canvasClipboard.objects)
    }

    @Test
    fun nothingSelectedOrAnObjectThePageDoesNotHoldChangesNothing() {
        val idle = workspace()
        assertSame(idle, idle.runObjectCommand(ObjectCommand.Copy))
        assertSame(idle, idle.runObjectCommand(ObjectCommand.Cut))
        val held = idle.selectObject("shape")
        for (command in ObjectCommand.entries) {
            assertSame(held, held.runObjectCommand(command, "gone"), "$command on a missing object")
        }
    }

    /**
     * A table's own commands in its cells' menu: copying it keeps the cell being edited, as Copy text
     * box does; cutting or deleting it takes the table, and the cell, away in one undo step.
     */
    @Test
    fun aTableCommandFromOneOfItsCellsActsOnThatTableAlone() {
        val cell = table.rows.first().cells.first()
        val editing = workspace().selectObject("shape").activateTextSelection(cell.id, TextSelection(0))
        assertEquals(cell.id, editing.focusedTextOutlineId)

        val copied = editing.runObjectCommand(ObjectCommand.Copy, table.id)
        assertEquals(listOf(table), copied.canvasClipboard.objects)
        assertEquals(cell.id, copied.focusedTextOutlineId)
        assertEquals(editing.selectedObjectIds, copied.selectedObjectIds)
        assertEquals(editing.outlines, copied.outlines)
        assertEquals(editing.structuralUndo, copied.structuralUndo)

        val cut = editing.runObjectCommand(ObjectCommand.Cut, table.id)
        assertEquals(listOf(table), cut.canvasClipboard.objects)
        assertEquals(objects - table, cut.outlines)
        assertNull(cut.focusedTextOutlineId)
        assertEquals(editing.structuralUndo.size + 1, cut.structuralUndo.size)
        assertEquals(objects, cut.undoStructure().outlines)

        val deleted = editing.runObjectCommand(ObjectCommand.Delete, table.id)
        assertEquals(objects - table, deleted.outlines)
        assertNull(deleted.focusedTextOutlineId)
        assertEquals(editing.canvasClipboard, deleted.canvasClipboard, "delete leaves the clipboard")
    }

    /** As a click would hold it: an object locked to others takes them along. */
    @Test
    fun anObjectNamedByIdBringsItsLockGroup() {
        val grouped = workspace(listOf(table.withLockGroup("pinned"), equation.withLockGroup("pinned"), shape))
        val copied = grouped.runObjectCommand(ObjectCommand.Copy, table.id)
        assertEquals(setOf(table.id, equation.id), copied.canvasClipboard.objects.map { it.id }.toSet())
        assertTrue(copied.selectedObjectIds.isEmpty())
        assertEquals(listOf(shape), grouped.runObjectCommand(ObjectCommand.Delete, table.id).outlines)
    }
}
