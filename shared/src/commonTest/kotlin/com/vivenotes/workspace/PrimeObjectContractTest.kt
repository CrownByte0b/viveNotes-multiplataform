package com.vivenotes.workspace

import com.vivenotes.model.*
import com.vivenotes.model.ink.InkSample
import com.vivenotes.model.ink.ShapeKind
import com.vivenotes.model.ink.seedSegments
import kotlin.test.*

class PrimeObjectContractTest {
    private fun objects(): List<Outline> = listOf(
        Outline.Shape(id = "shape", kind = ShapeKind.Rectangle,
            segments = seedSegments(ShapeKind.Rectangle, 100f, 220f, 180f, 280f, ::newId)).withRecomputedBounds(),
        newTable(2, 2, x = 230f, y = 220f, inkOnly = false).copy(id = "table"),
        Outline.Equation("equation", 600f, 220f, 80f, 60f, "x^2"),
        Outline.Image("image", 750f, 220f, 80f, attachmentId = "attachment", height = 60f),
    )

    private fun workspace(outlines: List<Outline>): WorkspaceState {
        val state = WorkspaceState.demo()
        return state.updatePage(state.selectedPageId) { it.copy(document = PageDoc(outlines = outlines)) }
    }

    private fun loop(left: Float, top: Float, right: Float, bottom: Float) = listOf(
        InkSample(left, top), InkSample(right, top), InkSample(right, bottom),
        InkSample(left, bottom), InkSample(left, top),
    )

    @Test
    fun lockingEveryOutlineKindMakesOneGroupAndEitherTapOrLassoHoldsAllMembers() {
        val outlines = objects()
        val locked = workspace(outlines).selectObjectsInRect(80f, 200f, 850f, 500f).toggleObjectLock()
        assertTrue(locked.selectedObjectsLocked)
        val group = assertNotNull(locked.selectedPage!!.document.outlines.first().lockGroup)
        assertTrue(locked.selectedPage!!.document.outlines.all { it.lockGroup == group })
        for (outline in outlines) {
            val tapped = locked.selectPointer().selectObject(outline.id)
            assertEquals(outlines.map { it.id }.toSet(), tapped.selectedObjectIds)
            assertEquals(locked.selectedPage!!.document, tapped.moveSelectedObjects(30f, 20f).selectedPage!!.document)
            assertEquals(locked.selectedPage!!.document,
                tapped.resizeSelectedObjects(0f, 0f, 2f, 0.5f).selectedPage!!.document)
        }
        val lasso = locked.selectObjectsInLasso(loop(90f, 210f, 190f, 290f))
        assertEquals(outlines.map { it.id }.toSet(), lasso.selectedObjectIds)
        val unlocked = lasso.toggleObjectLock()
        assertEquals(lasso.selectedObjectIds, unlocked.selectedObjectIds)
        assertTrue(unlocked.selectedPage!!.document.outlines.all { it.lockGroup == null })
        assertEquals(setOf("image"), unlocked.selectObject("image").selectedObjectIds)
    }

    @Test
    fun mixedLassosDropLockedObjectsAcrossEveryOutlineKindButLockedOnlyLassosKeepThem() {
        for (pinned in objects()) {
            val free = Outline.Image("free", 900f, 220f, 60f, attachmentId = "another-attachment", height = 60f)
            val state = workspace(listOf(pinned.withLockGroup("pinned"), free))
            assertEquals(setOf(free.id), state.selectObjectsInLasso(loop(80f, 200f, 1000f, 500f)).selectedObjectIds)
            val alone = state.selectObjectsInLasso(loop(pinned.x - 10f, pinned.y - 10f,
                pinned.x + pinned.primeWidth() + 10f, pinned.y + pinned.primeHeight() + 10f))
            assertEquals(setOf(pinned.id), alone.selectedObjectIds)
            assertTrue(alone.selectedObjectsLocked)
        }
    }

    @Test
    fun locksLeaveCopyRecolorDeleteAndUndoAvailableForAllFourKinds() {
        val locked = workspace(objects()).selectObjectsInRect(80f, 200f, 850f, 500f).toggleObjectLock()
        val recolored = locked.colorSelectedObjects(0xffe53935.toInt())
        val outlines = recolored.selectedPage!!.document.outlines
        assertEquals(0xffe53935.toInt(), (outlines[0] as Outline.Shape).borderArgb)
        assertEquals(0xffe53935.toInt(), (outlines[1] as Outline.Table).borderArgb)
        assertEquals(0xffe53935.toInt(), (outlines[2] as Outline.Equation).colorArgb)
        assertEquals(locked.selectedPage!!.document.outlines[3], outlines[3])
        val copied = recolored.copySelectedObjects()
        assertEquals(outlines, copied.canvasClipboard.objects)
        val pasted = copied.pasteCanvasAt(100f, 600f)
        val duplicates = pasted.selectedPage!!.document.outlines.filter { it.id in pasted.selectedObjectIds }
        assertEquals(4, duplicates.size)
        assertTrue(duplicates.all { it.lockGroup == null && it.id !in copied.selectedObjectIds })
        val oldTable = outlines.filterIsInstance<Outline.Table>().single()
        val newTable = duplicates.filterIsInstance<Outline.Table>().single()
        assertTrue(newTable.rows.flatMap { it.cells }.none { cell -> oldTable.locate(cell.id) != null })
        val deleted = copied.deleteSelectedObjects()
        assertTrue(deleted.selectedPage!!.document.outlines.isEmpty())
        assertEquals(outlines, deleted.undoStructure().selectedPage!!.document.outlines)
        assertTrue(deleted.undoStructure().redoStructure().selectedPage!!.document.outlines.isEmpty())
    }

    @Test
    fun textAndOutlineSelectionsCannotPartiallyLockOnlyTheOutline() {
        val shape = objects().first()
        val text = Outline.Text("text", 100f, 400f, blocks = listOf(Block.of("Written text")))
        val selected = workspace(listOf(shape, text)).selectObjectsInRect(80f, 200f, 850f, 600f)
        assertEquals(setOf(text.id), selected.selectedTextOutlineIds)
        assertSame(selected, selected.toggleObjectLock())
    }

    @Test
    fun aMixedSelectionNeverTransformsItsLockedMembers() {
        val pinned = objects().first().withLockGroup("pinned")
        val free = Outline.Image("free", 900f, 220f, 60f, attachmentId = "attachment", height = 60f)
        val state = workspace(listOf(pinned, free)).copy(selectedObjectIds = setOf(pinned.id, free.id))
        val moved = state.moveSelectedObjects(30f, 20f)
        assertEquals(pinned, moved.selectedPage!!.document.outlines.first())
        assertEquals(930f, moved.selectedPage!!.document.outlines.last().x)
        val resized = state.resizeSelectedObjects(0f, 0f, 2f, 0.5f)
        assertEquals(pinned, resized.selectedPage!!.document.outlines.first())
        assertEquals(120f, resized.selectedPage!!.document.outlines.last().width)
    }

    @Test
    fun lockedTypedTableCellsCannotTakeTheCaretAwayFromTheUnlockButton() {
        val table = objects().filterIsInstance<Outline.Table>().single().copy(lockGroup = "pinned")
        val selected = workspace(listOf(table)).selectObject(table.id)
        assertSame(selected, selected.focusTextBox(table.rows.first().cells.first().id))
        assertTrue(selected.selectedObjectsLocked)
    }

    @Test
    fun imageAndShapeFramesScaleIndependentlyOnBothAxesInTheSameSelection() {
        val sources = objects().filter { it is Outline.Shape || it is Outline.Image }
        val selected = workspace(sources).selectObjectsInRect(80f, 200f, 850f, 500f)
        val resized = selected.resizeSelectedObjects(100f, 220f, 2f, 0.5f)
        val image = resized.selectedPage!!.document.outlines.filterIsInstance<Outline.Image>().single()
        assertEquals(1400f, image.x)
        assertEquals(160f, image.width)
        assertEquals(30f, image.height)
        val shape = resized.selectedPage!!.document.outlines.filterIsInstance<Outline.Shape>().single()
        assertEquals(160f, shape.width)
        assertEquals(30f, shape.height)
        assertEquals(sources, resized.undoStructure().selectedPage!!.document.outlines)
    }
}
