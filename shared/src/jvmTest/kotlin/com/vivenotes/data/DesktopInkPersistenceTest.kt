package com.vivenotes.data

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.*
import com.vivenotes.data.db.*
import com.vivenotes.ink.InkFixtures
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.ink.toEntity
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopInkPersistenceTest {
    @Test
    fun authoredStrokesAndWholeEraseReloadWithoutChangingOldRowsOrOperations() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(db, clock = { 10000L })
            repository.seedIfEmpty()
            val notebook = repository.createNotebook("Integration")
            val section = repository.createSection(notebook, "Ink")
            val pageId = repository.createPage(section)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.MOUSE, 10f, 50f, 0L)
                add(InputToolType.MOUSE, 90f, 50f, 80L)
            }
            val pen = ViveInkTool(sizeDp = 6f, colorFollowsTheme = true)
            val old = pen.complete(Stroke(pen.brush, inputs), "existing", pageId, 0, 1).row.toEntity()
            val opaque = old.copy(id = "opaque", seq = 40, enc = "ink/future", points = byteArrayOf(3, 2, 1))
            db.inkStrokeDao().insert(old)
            db.inkStrokeDao().insert(opaque)
            val eraseBlob = InkFixtures.twoPointStroke
            repository.addPartialErase(InkEraseEntity("old-partial", pageId, EraserMode.Normal,
                8f, eraseBlob, ViveInkCodec.ENCODING, 2), listOf(old.id))
            val new = pen.complete(Stroke(pen.brush, inputs), "fresh", pageId, 0, 3).row.toEntity()
            repository.applyInkEdit(pageId, InkEdit.AddStroke(new))
            assertEquals(41, repository.inkFor(pageId).first { it.id == new.id }.seq)
            assertNotNull(ViveInkCodec.decode(repository.inkFor(pageId).first { it.id == new.id }.let {
                StoredInkStroke(it.id, it.pageId, it.seq, it.brushFamily, it.brushVersion, it.sizeDp,
                    it.colorArgb, it.colorFollowsTheme, it.epsilon, it.stabilization, it.minX, it.minY,
                    it.maxX, it.maxY, it.points, it.enc, it.createdAt)
            }))
            repository.applyInkEdit(pageId, InkEdit.EraseStrokes(setOf(new.id)))
            assertFalse(repository.loadInk(pageId).desktopGeometry().projections.any { it.id == new.id })
            repository.applyInkEdit(pageId, InkEdit.EraseStrokes(setOf(new.id, "already-purged")))
            val pending = repository.overlayPendingInk(repository.loadInk(pageId), listOf(InkEdit.AddStroke(new)))
            assertEquals(setOf(old.id, new.id), pending.desktopGeometry().projections.map { it.id }.toSet())
            assertEquals(pending.desktopGeometry().projections.size,
                repository.overlayPendingInk(pending, listOf(InkEdit.AddStroke(new))).desktopGeometry().projections.size)
            assertContentEquals(old.points, repository.inkFor(pageId).first { it.id == old.id }.points)
            assertContentEquals(opaque.points, repository.inkFor(pageId).first { it.id == opaque.id }.points)
            assertContentEquals(eraseBlob, repository.partialErasesFor(pageId).single().erase.points)
            assertEquals(EraserMode.Normal, repository.partialErasesFor(pageId).single().erase.mode)
            val otherPage = repository.createPage(section)
            assertFailsWith<IllegalArgumentException> { repository.applyInkEdit(otherPage, InkEdit.EraseStrokes(setOf(old.id))) }
            assertFailsWith<IllegalArgumentException> { repository.applyInkEdit(otherPage, InkEdit.AddStroke(new)) }
            repository.restoreStrokes(listOf(new.id))
            assertContentEquals(new.points, repository.inkFor(pageId).first { it.id == new.id }.points)
        } finally { db.close() }
    }
}
