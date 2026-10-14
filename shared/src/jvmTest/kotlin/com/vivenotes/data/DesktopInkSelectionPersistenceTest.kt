package com.vivenotes.data

import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.*
import com.vivenotes.model.ink.InkPage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopInkSelectionPersistenceTest {
    @Test
    fun fragmentMoveAndDeleteReplayExactlyAfterReopenAndUndoWithoutRewritingSourceOrMasks() = runBlocking {
        val file = Files.createTempFile("ink-selection", ".db").toFile()
        var db = NotesDatabase.create(file)
        try {
            var repo = NotesRepository(db)
            val notebook = repo.createNotebook("Ink selection")
            val section = repo.createSection(notebook, "Page")
            val pageId = repo.createPage(section)
            val original = selectionLine(pageId = pageId)
            repo.applyInkEdit(pageId, InkEdit.AddStroke(original))
            val (cut, _) = splitSelectionPage(pageId)
            repo.applyInkEdit(pageId, cut)
            val split = repo.loadInk(pageId)
            val selection = split.geometry!!.selectInRect(split, 0f, 180f, 55f, 220f)!!
            val move = selection.move(20f, 40f)!!
            move.edits.forEach { repo.applyInkEdit(pageId, it) }
            move.edits.forEach { repo.applyInkEdit(pageId, it) }
            assertEquals(1, repo.inkMovesFor(pageId).size)
            equivalent(move.after, repo.loadInk(pageId))
            db.close(); db = NotesDatabase.create(file); repo = NotesRepository(db)
            equivalent(move.after, repo.loadInk(pageId))
            move.undo.forEach { repo.applyInkEdit(pageId, it) }
            equivalent(split, repo.loadInk(pageId))
            move.edits.forEach { repo.applyInkEdit(pageId, it) }
            val current = repo.loadInk(pageId)
            val movedSelection = current.geometry!!.selectInRect(current, 20f, 220f, 75f, 260f)!!
            val deletion = movedSelection.delete()!!
            deletion.edits.forEach { repo.applyInkEdit(pageId, it) }
            equivalent(deletion.after, repo.loadInk(pageId))
            assertEquals(1, repo.loadInk(pageId).desktopGeometry().projections.size)
            deletion.undo.forEach { repo.applyInkEdit(pageId, it) }
            equivalent(current, repo.loadInk(pageId))
            deletion.edits.forEach { repo.applyInkEdit(pageId, it) }
            db.close(); db = NotesDatabase.create(file); repo = NotesRepository(db)
            equivalent(deletion.after, repo.loadInk(pageId))
            assertContentEquals(original.points, repo.inkFor(pageId).single().points)
            assertContentEquals(cut.row.points, repo.partialErasesFor(pageId).single { it.erase.id == cut.row.id }.erase.points)
            assertContentEquals((move.edits.single() as InkEdit.Move).row.points, repo.inkMovesFor(pageId).single().move.points)
        } finally { db.close(); file.delete() }
    }

    @Test
    fun moveRejectsForeignTargetsDamagedPathsAndChangedPayloadsAtomically() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            val repo = NotesRepository(db)
            val notebook = repo.createNotebook("Validation")
            val section = repo.createSection(notebook, "Ink")
            val pageId = repo.createPage(section)
            val other = repo.createPage(section)
            val original = selectionLine(pageId = pageId)
            repo.applyInkEdit(pageId, InkEdit.AddStroke(original))
            val foreign = selectionLine("foreign", other)
            repo.applyInkEdit(other, InkEdit.AddStroke(foreign))
            val page = repo.loadInk(pageId)
            val move = page.geometry!!.selectAt(page, 40f, 200f)!!.move(20f, 10f)!!.edits.single() as InkEdit.Move
            assertFailsWith<IllegalArgumentException> { repo.applyInkEdit(pageId, move.copy(targetIds = setOf(foreign.id))) }
            assertFailsWith<IllegalArgumentException> { repo.applyInkEdit(pageId, move.copy(row = move.row.copy(points = byteArrayOf(1)))) }
            assertFailsWith<IllegalArgumentException> { repo.applyInkEdit(pageId, move.copy(row = move.row.copy(dxDp = Float.NaN))) }
            assertTrue(repo.inkMovesFor(pageId).isEmpty())
            repo.applyInkEdit(pageId, move)
            assertFailsWith<IllegalArgumentException> { repo.applyInkEdit(pageId, move.copy(row = move.row.copy(dxDp = 21f))) }
            assertFailsWith<IllegalArgumentException> { repo.applyInkEdit(pageId, move.copy(targetIds = setOf("missing"))) }
            assertEquals(1, repo.inkMovesFor(pageId).size)
            assertNull(repo.inkMovesFor(pageId).single().move.deletedAt)
        } finally { db.close() }
    }

    private fun equivalent(a: InkPage, b: InkPage) {
        val expected = a.desktopGeometry().projections; val actual = b.desktopGeometry().projections
        assertEquals(expected.map { it.id }, actual.map { it.id })
        expected.zip(actual).forEach { (x, y) ->
            assertEquals(x.pageBounds, y.pageBounds)
            assertEquals(x.offsetX, y.offsetX); assertEquals(x.offsetY, y.offsetY)
            repeat(x.stroke.shape.getRenderGroupCount()) { group ->
                val first = InkMeshes.triangles(x.stroke.shape, group); val second = InkMeshes.triangles(y.stroke.shape, group)
                assertEquals(first.size, second.size)
                first.zip(second).forEach { (m, n) ->
                    assertContentEquals(m.positions, n.positions); assertContentEquals(m.triangles, n.triangles)
                }
            }
        }
    }
}
