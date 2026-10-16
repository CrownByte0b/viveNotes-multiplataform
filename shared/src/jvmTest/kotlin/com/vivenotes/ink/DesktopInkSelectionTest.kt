package com.vivenotes.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.data.InkEdit
import com.vivenotes.data.InkWriter
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal fun selectionLine(id: String = "source", pageId: String = "page", y: Float = 200f, group: String? = null,
    endX: Float = 110f): InkStrokeEntity {
    val tool = ViveInkTool(sizeDp = 6f, colorArgb = 0xffe01b24.toInt())
    val inputs = MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, 10f, y, 0L); add(InputToolType.MOUSE, endX, y, 80L)
    }
    return tool.complete(Stroke(tool.brush, inputs), id, pageId, 0, 1L, group).row.toEntity()
}

internal fun splitSelectionPage(pageId: String = "page"): Pair<InkEdit.PartialErase, InkPage> {
    val initial = InkPageReader.read(pageId, listOf(selectionLine(pageId = pageId)), emptyList(), emptyList())
    val gesture = DesktopPartialEraseGesture(initial, 1f, sizeDp = 12f)
    gesture.observe(InkPointerSample(60f, 180f, 100L)); gesture.observe(InkPointerSample(60f, 220f, 140L))
    return assertNotNull(gesture.request().prepare())
}

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopInkSelectionTest {
    @Test
    fun clickingEitherCutFragmentSelectsOnlyTheActualHitAndTheErasedGapStaysEmpty() {
        val (_, page) = splitSelectionPage()
        for ((x, right) in listOf(30f to false, 90f to true)) {
            val selected = assertNotNull(page.geometry!!.selectAt(page, x, 200f))
            val piece = selected.forRecognition()!!.desktopGeometry().projections.single()
            assertEquals(right, piece.pageBounds!!.left > 60f)
            assertEquals(1, (selected as DesktopInkSelection).held.projections.size)
        }
        assertNull(page.geometry!!.selectAt(page, 60f, 200f, reach = 0f))
    }

    @Test
    fun inkLassosDropLocksAndMixedMovesNeverShiftALockedOutline() {
        val initial = WorkspaceState.demo()
        val ink = InkPageReader.read(initial.selectedPageId,
            listOf(selectionLine(pageId = initial.selectedPageId)), emptyList(), emptyList())
        val pinned = Outline.Image("pinned", 200f, 220f, 80f, attachmentId = "attachment", height = 60f, lockGroup = "group")
        val free = Outline.Equation("free", 300f, 220f, 80f, 60f, "x^2")
        val base = initial.updatePage(initial.selectedPageId) {
            it.copy(document = PageDoc(outlines = listOf(pinned, free)), ink = ink)
        }
        val selected = base.selectObjectsInRect(0f, 180f, 400f, 320f)
        assertNotNull(selected.selectedInk)
        assertEquals(setOf(free.id), selected.selectedObjectIds)
        assertSame(selected, selected.toggleObjectLock())
        val mixed = selected.copy(selectedObjectIds = setOf(pinned.id, free.id))
        val moved = mixed.applyInkSelectionChange(assertNotNull(selected.selectedInk.move(20f, 40f)))
        assertEquals(pinned, moved.selectedPage!!.document.outlines.first())
        assertEquals(320f, moved.selectedPage!!.document.outlines.last().x)
    }

    @Test
    fun nativeHitsSelectTheTopStrokeAndExpandStoredGroupsWhileMissingEmptyBounds() {
        val rows = listOf(selectionLine("first", group = "group"), selectionLine("second", y = 240f, group = "group"),
            selectionLine("top", y = 200f))
        val page = InkPageReader.read("page", rows, emptyList(), emptyList())
        val geometry = page.geometry!!
        assertEquals(setOf("top"), geometry.selectAt(page, 40f, 200f)!!.targetIds)
        assertEquals(setOf("first", "second"), geometry.selectAt(page, 40f, 240f)!!.targetIds)
        assertNull(geometry.selectAt(page, 40f, 220f))
        assertNull(geometry.selectAt(page, Float.NaN, 200f))
        assertNull(geometry.selectInRect(page, 0f, 0f, Float.POSITIVE_INFINITY, 300f))
        assertNull(geometry.selectInRect(page, 20f, 190f, 50f, 210f), "lassos require enclosing the ink")
    }

    @Test
    fun lassoMovesOnlyTheChosenFragmentAndDeletingItKeepsItsSiblingAndOriginalInputs() {
        val (_, page) = splitSelectionPage()
        assertEquals(2, page.desktopGeometry().projections.size)
        val selection = assertNotNull(page.geometry!!.selectInRect(page, 0f, 180f, 55f, 220f))
        val held = selection as DesktopInkSelection
        assertEquals(1, held.held.projections.size)
        val untouched = page.desktopGeometry().projections.maxBy { it.pageBounds!!.left }
        val move = assertNotNull(selection.move(20f, 40f))
        val moved = move.after.desktopGeometry().projections
        assertEquals(untouched.pageBounds, moved.maxBy { it.pageBounds!!.right }.pageBounds)
        assertEquals(selection.bounds.top + 40f, move.selection!!.bounds.top, 0.01f)
        assertEquals(1, (move.selection as DesktopInkSelection).held.projections.size)
        assertNull(selection.move(Float.NaN, 1f))
        val clamped = assertNotNull(selection.move(-1000f, -1000f))
        assertEquals(0f, clamped.selection!!.bounds.left, 0.01f)
        val deleted = assertNotNull(selection.delete())
        assertEquals(1, deleted.after.desktopGeometry().projections.size)
        assertEquals(untouched.pageBounds, deleted.after.desktopGeometry().projections.single().pageBounds)
        assertTrue(deleted.edits.single() is InkEdit.PartialErase)
        assertEquals(page.desktopGeometry().sourceStrokes.single().stroke.inputs,
            deleted.after.desktopGeometry().sourceStrokes.single().stroke.inputs)
        assertEquals(2, deleted.after.withDesktopEdits(deleted.undo).desktopGeometry().projections.size)
        assertEquals(1, deleted.after.withDesktopEdits(deleted.undo + deleted.edits).desktopGeometry().projections.size)
    }

    @Test
    fun mixedSelectionMovesAndDeletesInOneUndoStepAndRejectsStaleOrReadOnlyEdits() {
        val initial = WorkspaceState.demo()
        val id = initial.selectedPageId
        val ink = InkPageReader.read(id, listOf(selectionLine(pageId = id)), emptyList(), emptyList())
        val shape = Outline.Shape(id = "shape", x = 20f, y = 190f, width = 30f, height = 20f)
        val base = initial.updatePage(id) { it.copy(document = PageDoc(outlines = listOf(shape)), ink = ink) }
        val selected = base.selectObjectsInRect(0f, 180f, 120f, 230f)
        assertEquals(setOf(shape.id), selected.selectedObjectIds)
        assertNotNull(selected.selectedInk)
        val change = assertNotNull(selected.selectedInk!!.move(20f, 40f))
        val moved = selected.applyInkSelectionChange(change)
        assertEquals(40f, moved.selectedPage!!.document.outlines.single().x)
        assertEquals(230f, moved.selectedPage!!.document.outlines.single().y)
        assertEquals(1, moved.structuralUndo.size)
        val undone = moved.undoStructure()
        assertEquals(shape, undone.selectedPage!!.document.outlines.single())
        assertEquals(base.selectedPage!!.ink!!.geometry!!.bottomDp, undone.selectedPage!!.ink!!.geometry!!.bottomDp)
        assertEquals(moved.selectedPage!!.document, undone.redoStructure().selectedPage!!.document)
        val deleted = selected.deleteSelectedObjects()
        assertTrue(deleted.selectedPage!!.document.outlines.isEmpty())
        assertTrue(deleted.selectedPage!!.ink!!.desktopGeometry().isEmpty)
        assertEquals(1, deleted.structuralUndo.size)
        assertEquals(shape, deleted.undoStructure().selectedPage!!.document.outlines.single())
        assertNull(selected.selectPointer().selectedInk)
        assertNull(selected.toggleInkTool(InkTool.Pen).selectedInk)
        assertNull(selected.selectPage(selected.selectedSection!!.pages.last().id).selectedInk)
        assertEquals(selected.selectPointer(), selected.selectPointer().applyInkSelectionChange(change))
        val readOnly = selected.updatePage(id) { it.copy(content = PageContent.Unreadable) }
        assertEquals(readOnly, readOnly.applyInkSelectionChange(change))
    }

    @Test
    fun failedMovesAndUndoStayQueuedInOrderUntilFlushAndDebugLogsContainNoRowIds() = runTest {
        val store = FakeNotesStore()
        store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
        val written = mutableListOf<InkEdit>()
        var fail = true
        val source = object : InkWriter {
            override suspend fun loadInk(pageId: String) = InkPageReader.read(pageId,
                listOf(selectionLine("private-stroke-id", pageId)), emptyList(), emptyList())
            override fun overlayPendingInk(page: InkPage, edits: List<InkEdit>) = page.withDesktopEdits(edits)
            override suspend fun applyInkEdit(pageId: String, edit: InkEdit) {
                if (fail) error("disk unavailable")
                written += edit
            }
        }
        val logs = mutableListOf<String>()
        val session = WorkspaceSession(store, backgroundScope, { "" }, inkSource = source, log = DebugLog(true, logs::add))
        session.start(); runCurrent()
        session.update { it.selectInkAt(40f, 200f) }
        session.update { it.applyInkSelectionChange(it.selectedInk!!.move(20f, 40f)!!) }
        runCurrent()
        session.update { it.undoStructure() }; runCurrent()
        assertEquals(2, session.state.value!!.pendingInkEdits.size)
        assertTrue(written.isEmpty())
        fail = false; session.flush()
        assertEquals(listOf(true, false), written.map { (it as InkEdit.Move).active })
        assertTrue(session.state.value!!.pendingInkEdits.isEmpty())
        assertTrue(logs.any { it.contains("ink selected") })
        assertTrue(logs.any { it.contains("ink move saved") })
        assertTrue(logs.none { it.contains("private-stroke-id") })
        assertTrue(store.saves.isEmpty())
    }
}
