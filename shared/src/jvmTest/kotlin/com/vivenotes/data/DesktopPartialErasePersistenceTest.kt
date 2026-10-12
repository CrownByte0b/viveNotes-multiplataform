package com.vivenotes.data

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.data.db.*
import com.vivenotes.ink.*
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopPartialErasePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun normalGestureSurvivesDatabaseReopenUndoAndRedoWithOriginalRowsAndOperationsIntact() = runBlocking {
        val file = temporary.newFile("notes.db")
        var db = NotesDatabase.create(file)
        try {
            var repository = NotesRepository(db, clock = { 10000L })
            repository.seedIfEmpty()
            val notebook = repository.createNotebook("Partial erase")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val row = line("source", pageId)
            val opaque = row.copy(id = "future", seq = 1, enc = "ink/future", points = byteArrayOf(3, 1, 4))
            db.inkStrokeDao().insert(row); db.inkStrokeDao().insert(opaque)
            val path = listOf(InkPoint(0f, 35f), InkPoint(120f, 35f), InkPoint(120f, 70f), InkPoint(0f, 70f))
            val move = ViveInkCodec.encodeMove(InkLassoMove(path, setOf(row.id), emptySet(), 20f, 10f),
                "old-move", pageId, 100L)
            val moveRow = InkMoveEntity(move.id, pageId, move.dxDp, move.dyDp, points = move.points, enc = move.enc, createdAt = move.createdAt)
            repository.addInkMove(moveRow, listOf(row.id))
            val inactive = InkEraseEntity("old-inactive", pageId, sizeDp = 8f, points = row.points,
                enc = ViveInkCodec.ENCODING, createdAt = 9000L, deletedAt = 9001L)
            repository.addPartialErase(inactive, listOf(row.id))
            val initial = repository.loadInk(pageId)
            assertEquals(9000L, initial.latestOperationAt)
            val gesture = DesktopPartialEraseGesture(initial, 1f, now = 10L)
            gesture.observe(InkPointerSample(80.12345f, 35.34567f, 100L))
            gesture.observe(InkPointerSample(80.12345f, 90.76543f, 180L))
            val (edit, preview) = assertNotNull(gesture.request().prepare())
            assertEquals(9001L, edit.row.createdAt, "inactive operations still advance the clock")
            assertEquals(setOf(row.id), edit.targetIds)
            assertEquals(2, preview.desktopGeometry().projections.size)
            repository.applyInkEdit(pageId, edit)
            repository.applyInkEdit(pageId, edit)
            assertEquals(1, repository.partialErasesFor(pageId).size, "retry acknowledges the same immutable operation")
            assertEquivalent(preview, repository.loadInk(pageId))
            assertContentEquals(row.points, repository.inkFor(pageId).single { it.id == row.id }.points)
            assertContentEquals(opaque.points, repository.inkFor(pageId).single { it.id == opaque.id }.points)
            assertContentEquals(moveRow.points, repository.inkMovesFor(pageId).single().move.points)
            db.close()
            db = NotesDatabase.create(file)
            repository = NotesRepository(db, clock = { 11000L })
            assertEquivalent(preview, repository.loadInk(pageId))
            repository.applyInkEdit(pageId, edit.copy(active = false))
            assertEquivalent(initial, repository.loadInk(pageId))
            val inactiveNew = db.inkEraseDao().byIds(listOf(edit.row.id)).single()
            assertNotNull(inactiveNew.deletedAt)
            assertContentEquals(edit.row.points, inactiveNew.points)
            assertEquals(edit.targetIds, db.inkEraseDao().targetsForErases(listOf(edit.row.id)).map { it.strokeId }.toSet())
            repository.applyInkEdit(pageId, edit)
            assertEquivalent(preview, repository.loadInk(pageId))
            assertNull(db.inkEraseDao().byIds(listOf(edit.row.id)).single().deletedAt)
            assertContentEquals(inactive.points, db.inkEraseDao().byIds(listOf(inactive.id)).single().points)
        } finally { db.close() }
    }

    @Test
    fun eraseTargetsProtectLaterInkAndUndoWorksEvenWhenTheMaskTookEveryFragment() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(db, clock = { 20000L })
            repository.seedIfEmpty()
            val notebook = repository.createNotebook("Targets")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val old = line("old", pageId)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(old))
            val initial = repository.loadInk(pageId)
            val gesture = DesktopPartialEraseGesture(initial, 1f, sizeDp = 240f, now = 100L)
            gesture.observe(InkPointerSample(60f, 50f, 100L))
            val (erase, preview) = assertNotNull(gesture.request().prepare())
            assertTrue(preview.desktopGeometry().isEmpty)
            repository.applyInkEdit(pageId, erase)
            assertTrue(repository.loadInk(pageId).desktopGeometry().isEmpty)
            assertNull(repository.inkFor(pageId).single().deletedAt, "a Normal gesture appends a mask, preserving its source row")
            val later = old.copy(id = "later", createdAt = 101L)
            repository.applyInkEdit(pageId, InkEdit.AddStroke(later))
            assertEquals(listOf(later.id), repository.loadInk(pageId).desktopGeometry().projections.map { it.id })
            repository.applyInkEdit(pageId, erase.copy(active = false))
            assertEquals(listOf(old.id, later.id), repository.loadInk(pageId).desktopGeometry().projections.map { it.id })
            repository.applyInkEdit(pageId, erase)
            assertEquals(listOf(later.id), repository.loadInk(pageId).desktopGeometry().projections.map { it.id })
            assertContentEquals(old.points, repository.inkFor(pageId).single { it.id == old.id }.points)
        } finally { db.close() }
    }

    @Test
    fun foreignTargetsAndChangedOperationPayloadsAreRejectedAtomically() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(db, clock = { 30000L })
            repository.seedIfEmpty()
            val section = repository.createSection(repository.createNotebook("Validation"), "Ink")
            val pageId = repository.createPage(section)
            val other = repository.createPage(section)
            val row = line("source", pageId); val foreign = line("foreign", other)
            repository.addStroke(row); repository.addStroke(foreign)
            val gesture = DesktopPartialEraseGesture(repository.loadInk(pageId), 1f)
            gesture.observe(InkPointerSample(60f, 50f, 100L))
            val (erase, _) = assertNotNull(gesture.request().prepare())
            assertFailsWith<IllegalArgumentException> {
                repository.applyInkEdit(pageId, erase.copy(targetIds = erase.targetIds + foreign.id))
            }
            assertTrue(repository.partialErasesFor(pageId).isEmpty())
            assertTrue(db.inkEraseDao().targetsForErases(listOf(erase.row.id)).isEmpty())
            assertFailsWith<IllegalArgumentException> { repository.applyInkEdit(other, erase) }
            repository.applyInkEdit(pageId, erase)
            assertFailsWith<IllegalArgumentException> { repository.applyInkEdit(pageId, erase.copy(row = erase.row.copy(sizeDp = 1f))) }
            assertFailsWith<IllegalArgumentException> { repository.applyInkEdit(pageId, erase.copy(targetIds = setOf("missing"))) }
            assertNull(db.inkEraseDao().byIds(listOf(erase.row.id)).single().deletedAt)
            assertContentEquals(erase.row.points, repository.partialErasesFor(pageId).single().erase.points)
        } finally { db.close() }
    }

    @Test
    fun pendingPartialEraseUndoRedoIsIdempotentAndKeepsTheSameMasksAndNativeSources() {
        val row = line("source", "page")
        val initial = InkPageReader.read("page", listOf(row), emptyList(), emptyList())
        val gesture = DesktopPartialEraseGesture(initial, 1f)
        gesture.observe(InkPointerSample(60f, 35f, 100L)); gesture.observe(InkPointerSample(60f, 75f, 140L))
        val (erase, preview) = assertNotNull(gesture.request().prepare())
        val duplicate = initial.withDesktopEdits(listOf(erase, erase))
        assertEquivalent(preview, duplicate)
        val undo = preview.withDesktopEdit(erase.copy(active = false))
        assertEquivalent(initial, undo)
        val redo = undo.withDesktopEdit(erase)
        assertEquivalent(preview, redo)
        assertEquals(erase.row.createdAt, undo.latestOperationAt)
        assertEquivalent(initial, initial.withDesktopEdits(listOf(erase, erase.copy(active = false))))
        val erasedRow = redo.withDesktopEdit(InkEdit.EraseStrokes(setOf(row.id)))
        val restored = erasedRow.withDesktopEdit(InkEdit.RestoreStrokes(setOf(row.id), redo))
        assertEquivalent(redo, restored)
        assertEquals(1, initial.desktopGeometry().projections.size)
    }

    @Test
    fun exactRoundMaskAvoidsTheSquareHitRegionAndIgnoresOutOfOrderObservations() {
        val source = line("source", "page", dot = true)
        val page = InkPageReader.read("page", listOf(source), emptyList(), emptyList())
        val miss = DesktopPartialEraseGesture(page, 1f)
        miss.observe(InkPointerSample(33f, 63f, 100L))
        assertNull(miss.request().prepare(), "a square-corner candidate is outside the round mask")
        val hit = DesktopPartialEraseGesture(page, 1f)
        assertTrue(hit.observe(InkPointerSample(20f, 50f, 100L)))
        assertFalse(hit.observe(InkPointerSample(20f, 50f, 100L)))
        assertFalse(hit.observe(InkPointerSample(0f, 0f, 90L)))
        assertFalse(hit.observe(InkPointerSample(20f, 50f, 110L, InputToolType.TOUCH)))
        assertEquals(setOf(source.id), assertNotNull(hit.request().prepare()).first.targetIds)
    }

    private fun line(id: String, pageId: String, dot: Boolean = false): InkStrokeEntity {
        val tool = ViveInkTool(ViveBrushes.MARKER, sizeDp = 6f)
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, if (dot) 20f else 10.234567f, 50.345678f, 0L)
            if (!dot) add(InputToolType.MOUSE, 110.76543f, 50.345678f, 100L)
        }
        return tool.complete(Stroke(tool.brush, inputs), id, pageId, 0, 1L).row.toEntity()
    }

    private fun assertEquivalent(expected: InkPage, actual: InkPage) {
        val a = expected.desktopGeometry().projections; val b = actual.desktopGeometry().projections
        assertEquals(a.map { it.id }, b.map { it.id })
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.pageBounds, y.pageBounds)
            assertEquals(x.offsetX, y.offsetX); assertEquals(x.offsetY, y.offsetY)
            assertEquals(x.scaleX, y.scaleX); assertEquals(x.scaleY, y.scaleY)
            repeat(x.stroke.shape.getRenderGroupCount()) { group ->
                val xm = InkMeshes.triangles(x.stroke.shape, group); val ym = InkMeshes.triangles(y.stroke.shape, group)
                assertEquals(xm.size, ym.size)
                xm.zip(ym).forEach { (m, n) -> assertContentEquals(m.positions, n.positions); assertContentEquals(m.triangles, n.triangles) }
            }
        }
    }
}
