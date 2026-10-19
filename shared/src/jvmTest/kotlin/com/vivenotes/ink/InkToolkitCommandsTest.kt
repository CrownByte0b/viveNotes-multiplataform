package com.vivenotes.ink

import com.vivenotes.data.InkEdit
import com.vivenotes.data.PendingInkEdit
import com.vivenotes.data.InkWriter
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.ink.InkPage
import com.vivenotes.workspace.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InkToolkitCommandsTest {
    @Test
    fun mixedRecolourPreservesAutomaticIntentForEveryTypeAndUndoesAsOneChange() {
        val base = workspace()
        val objects = listOf(Outline.Shape("shape", x = 150f, y = 190f),
            com.vivenotes.model.newTable(1, 1, x = 300f, y = 190f, inkOnly = true),
            Outline.Equation("equation", 500f, 190f, 80f, 40f, "x^2"))
        val selected = base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = objects)) }
            .selectObjectsInRect(0f, 180f, 900f, 500f)
        for ((argb, follows) in listOf(0xff000000.toInt() to true, -1 to true, -1 to false,
            0xff000000.toInt() to false, 0xffef4444.toInt() to false)) {
            val colored = selected.colorSelectedObjects(argb, follows)
            val doc = colored.selectedPage!!.document
            assertEquals(follows, (doc.outlines.first() as Outline.Shape).borderFollowsTheme)
            assertEquals(follows, doc.outlines.filterIsInstance<Outline.Table>().single().borderFollowsTheme)
            assertEquals(argb.takeUnless { follows }, doc.outlines.filterIsInstance<Outline.Equation>().single().colorArgb)
            assertEquals(follows, colored.selectedPage!!.ink!!.strokes.single().colorFollowsTheme)
            assertEquals(1, colored.structuralUndo.size)
            val restored = colored.undoStructure()
            assertEquals(selected.selectedPage!!.document, restored.selectedPage!!.document)
            assertEquals(selected.selectedPage!!.ink!!.strokes, restored.selectedPage!!.ink!!.strokes)
            assertEquals(doc, restored.redoStructure().selectedPage!!.document)
            assertEquals(follows, restored.redoStructure().selectedPage!!.ink!!.strokes.single().colorFollowsTheme)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionLogsInkCopyAndSavedColoursWithoutStrokeIdsOrContents() = runTest {
        val store = FakeNotesStore()
        store.notebook("Notebook", "Section" to listOf("Page" to PageDoc.empty()))
        val writer = object : InkWriter {
            override suspend fun loadInk(pageId: String) = InkPageReader.read(pageId,
                listOf(selectionLine("private-stroke-id", pageId), selectionLine("private-second-id", pageId, 240f)), emptyList(), emptyList())
            override fun overlayPendingInk(page: InkPage, edits: List<InkEdit>) = page.withDesktopEdits(edits)
            override suspend fun applyInkEdit(pageId: String, edit: InkEdit) = Unit
        }
        val lines = mutableListOf<String>()
        val session = WorkspaceSession(store, backgroundScope, { "created" }, inkSource = writer,
            log = DebugLog(enabled = true, output = lines::add))
        session.start()
        runCurrent()
        session.update { it.selectObjectsInRect(0f, 180f, 120f, 260f).toggleInkGroup()
            .copySelectedObjects().colorSelectedObjects(0xff3b82f6.toInt()) }
        session.flush()
        assertTrue(lines.any { it.contains("selection copied") })
        assertTrue(lines.any { it.contains("ink colours saved") })
        assertTrue(lines.any { it.contains("ink groups saved") })
        assertTrue(lines.none { it.contains("private-stroke-id") })
    }

    private fun workspace(): WorkspaceState {
        val base = WorkspaceState.demo()
        val ink = InkPageReader.read(base.selectedPageId, listOf(selectionLine(pageId = base.selectedPageId)), emptyList(), emptyList())
        return base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = emptyList()), ink = ink) }
    }

    @Test
    fun recolouringKeepsInputsOperationsAndHeldPiecesAndRestoresTheOriginalStyleOnUndo() {
        val (_, page) = splitSelectionPage()
        val selected = assertNotNull(page.geometry!!.selectInRect(page, 0f, 180f, 55f, 220f))
        val change = assertNotNull(selected.recolor(0xff3b82f6.toInt()))
        assertEquals(selected.bounds, change.selection!!.bounds)
        assertEquals(1, (change.selection as DesktopInkSelection).held.projections.size)
        assertEquals(page.operations, change.after.operations)
        assertEquals(page.desktopGeometry().sourceStrokes.single().stroke.inputs,
            change.after.desktopGeometry().sourceStrokes.single().stroke.inputs)
        assertTrue(change.after.desktopGeometry().projections.all { it.stroke.brush.colorIntArgb == 0xff3b82f6.toInt() && it.colorFollowsTheme == false })
        val restored = change.after.withDesktopEdits(change.undo)
        assertEquals(page.desktopGeometry().sourceStrokes.single().stroke.brush.colorIntArgb,
            restored.desktopGeometry().sourceStrokes.single().stroke.brush.colorIntArgb)
        assertEquals(page.desktopGeometry().projections.map { it.pageBounds }, restored.desktopGeometry().projections.map { it.pageBounds })
        assertNull(change.selection!!.recolor(0xff3b82f6.toInt()))
    }

    @Test
    fun changingOnlyTheAutomaticIntentStillRecordsAReversibleInkEdit() {
        val page = workspace().selectedPage!!.ink!!
        val selection = page.geometry!!.selectAt(page, 40f, 200f)!!
        val argb = page.strokes.single().colorArgb
        val automatic = assertNotNull(selection.recolor(argb, true))
        val explicit = assertNotNull(automatic.selection!!.recolor(argb, false))
        assertFalse(explicit.after.strokes.single().colorFollowsTheme!!)
        assertTrue(explicit.after.withDesktopEdits(explicit.undo).strokes.single().colorFollowsTheme!!)
        assertNull(explicit.selection!!.recolor(argb, false))
    }

    @Test
    fun copyingOneMovedFragmentPastesOnlyThatFragmentWithAllErasesAndTransformsPreserved() {
        val (_, split) = splitSelectionPage()
        val selection = assertNotNull(split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f))
        val moved = assertNotNull(selection.move(20f, 40f)).selection!!
        val clipboard = assertNotNull(moved.copy())
        val target = InkPage("target", emptyList())
        val pasted = assertNotNull(clipboard.paste(target, 300f - clipboard.bounds.left, 500f - clipboard.bounds.top))
        val piece = pasted.after.desktopGeometry().projections.single()
        assertEquals(300f, piece.pageBounds!!.left, .01f)
        assertEquals(500f, piece.pageBounds!!.top, .01f)
        assertEquals(moved.bounds.right - moved.bounds.left, piece.pageBounds!!.right - piece.pageBounds!!.left, .01f)
        assertTrue(piece.pageBounds!!.right < 360f, "the erased gap and unselected sibling must not be pasted")
        assertNotEquals("source", piece.id)
        assertTrue(pasted.after.withDesktopEdits(pasted.undo).desktopGeometry().isEmpty)
        val redone = pasted.after.withDesktopEdits(pasted.undo + pasted.redo)
        assertEquals(piece.pageBounds, redone.desktopGeometry().projections.single().pageBounds)
    }

    @Test
    fun mixedOutlineAndInkCopyAndRecolourEachHaveOneCompleteUndoStep() {
        val base = workspace()
        val equation = Outline.Equation("equation", 150f, 190f, 80f, 40f, "x^2")
        val initial = base.updatePage(base.selectedPageId) { it.copy(document = PageDoc(outlines = listOf(equation))) }
        val selected = initial.selectObjectsInRect(0f, 180f, 250f, 260f)
        val colored = selected.colorSelectedObjects(0xff3b82f6.toInt())
        assertEquals(0xff3b82f6.toInt(), (colored.selectedPage!!.document.outlines.single() as Outline.Equation).colorArgb)
        assertEquals(1, colored.structuralUndo.size)
        assertEquals(equation, colored.undoStructure().selectedPage!!.document.outlines.single())
        val copied = selected.copySelectedObjects().pasteCanvasAt(300f, 500f)
        assertEquals(2, copied.selectedPage!!.document.outlines.size)
        assertEquals(2, copied.selectedPage!!.ink!!.desktopGeometry().projections.size)
        assertNotNull(copied.selectedInk)
        assertEquals(1, copied.structuralUndo.size)
        assertEquals(initial.selectedPage!!.document, copied.undoStructure().selectedPage!!.document)
        assertEquals(1, copied.undoStructure().selectedPage!!.ink!!.desktopGeometry().projections.size)
        assertEquals(2, copied.undoStructure().redoStructure().selectedPage!!.ink!!.desktopGeometry().projections.size)
    }

    @Test
    fun copyingAnInkGroupMintsNewRowAndGroupIdsForEveryPaste() {
        val original = InkPageReader.read("original", listOf(selectionLine("one", "original", group = "group"),
            selectionLine("two", "original", 240f, "group")), emptyList(), emptyList())
        val clipboard = assertNotNull(original.geometry!!.selectAt(original, 40f, 200f)!!.copy())
        val first = assertNotNull(clipboard.paste(InkPage("target", emptyList()), 300f, 300f))
        val firstRows = first.after.desktopGeometry().sourceStrokes
        assertEquals(2, firstRows.size)
        assertEquals(1, firstRows.map { it.groupId }.distinct().size)
        assertTrue(firstRows.all { it.id !in setOf("one", "two") && it.groupId != null && it.groupId != "group" })
        val second = assertNotNull(clipboard.paste(first.after, 500f, 500f))
        val copied = second.after.desktopGeometry().sourceStrokes.filter { row -> firstRows.none { it.id == row.id } }
        assertTrue(copied.all { it.groupId != firstRows.first().groupId })
    }

    @Test
    fun preparedPastesRejectStalePagesAndPreserveTheLatestViewAndPendingQueueAtTheHistoryLimit() {
        val initial = workspace().selectInkAt(40f, 200f).copySelectedObjects().selectPointer()
        val prior = PendingInkEdit("already-saved", initial.selectedPageId, InkEdit.EraseStrokes(emptySet()))
        val before = initial.copy(pendingInkEdits = listOf(prior), structuralUndo = List(100) {
            StructuralSnapshot(initial.selectedPageId, initial.selectedPage!!.document)
        })
        val prepared = before.pasteCanvasAt(300f, 400f)
        val current = before.copy(pendingInkEdits = emptyList(), navigationVisible = false)
        val pasted = current.applyPreparedCanvasPaste(before, prepared)
        assertEquals(2, pasted.selectedPage!!.ink!!.desktopGeometry().projections.size)
        assertFalse(pasted.navigationVisible)
        assertTrue(pasted.pendingInkEdits.none { it.id == prior.id })
        assertEquals(100, pasted.structuralUndo.size)
        val changed = before.updatePage(before.selectedPageId) { it.copy(document = PageDoc.empty()) }
        assertSame(changed, changed.applyPreparedCanvasPaste(before, prepared))
        val switched = before.selectPage("lecture-notes")
        assertSame(switched, switched.applyPreparedCanvasPaste(before, prepared))
    }
}
