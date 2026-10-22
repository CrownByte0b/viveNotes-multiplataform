package com.vivenotes.data

import com.vivenotes.data.db.*
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkFixtures
import com.vivenotes.ink.desktopGeometry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class InkReopeningPersistenceTest {
    @Test
    fun restoringHistoryAndReopeningTheDatabaseUseStoredInkAfterCachedErasure() = runBlocking {
        val dir = Files.createTempDirectory("ink-reopen-history").toFile()
        var now = 1000L
        val db = NotesDatabase.create(java.io.File(dir, "notes.db"))
        val repository = NotesRepository(db, clock = { now })
        var page = ""
        try {
            repository.seedIfEmpty()
            val book = repository.createNotebook("History")
            val section = repository.createSection(book, "Ink")
            page = repository.createPage(section)
            repository.saveDoc(page, com.vivenotes.model.PageDoc.empty())
            val row = repository.addStroke(LoadedInkCacheTest.row().copy(pageId = page))
            repository.loadInk(page)
            now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS + 1
            repository.eraseStrokes(listOf(row.id))
            assertTrue(repository.loadInk(page).strokes.isEmpty())
            val revision = repository.revisionHistory(page).first { revision ->
                val saved = db.pageRevisionDao().byId(page, revision.id)!!
                InkRevisionPayload.unpack(saved).strokes.any { it.id == row.id }
            }
            repository.restoreRevision(page, revision.id)
            val restored = repository.loadInk(page)
            assertEquals(listOf(row.id), restored.strokes.map { it.id })
            assertSame(restored, repository.loadInk(page))
        } finally { repository.releaseLoadedInk(); db.close() }
        val reopened = NotesDatabase.create(java.io.File(dir, "notes.db"))
        try {
            val fresh = NotesRepository(reopened)
            assertEquals(0, fresh.loadedInk.retainedPages)
            assertEquals(listOf("stroke"), fresh.loadInk(page).strokes.map { it.id })
            fresh.releaseLoadedInk()
        } finally { reopened.close(); dir.deleteRecursively() }
    }

    @Test
    fun realSqliteReplacementsTombstonesAndOwnershipAreAuthoritativeWithoutTimestampChanges() = runBlocking {
        val dir = Files.createTempDirectory("ink-reopen").toFile()
        val library = NotesLibrary.open(dir)
        try {
            val repository = library.repository
            val db = library.database
            repository.seedIfEmpty()
            val book = repository.createNotebook("Reopening")
            val section = repository.createSection(book, "Ink")
            val page = repository.createPage(section)
            val row = LoadedInkCacheTest.row().copy(pageId = page)
            db.inkStrokeDao().insert(row)
            val first = repository.loadInk(page)
            assertSame(first, repository.loadInk(page))
            db.inkStrokeDao().insert(row.copy(colorArgb = 0xff008800.toInt(), groupId = "group"))
            val recolored = repository.loadInk(page)
            assertNotSame(first.geometry, recolored.geometry)
            assertEquals(0xff008800.toInt(), recolored.strokes.single().colorArgb)
            assertEquals("group", recolored.desktopGeometry().sourceStrokes.single().groupId)
            db.inkStrokeDao().insert(row.copy(points = InkFixtures.blob("stylus-pressure-tilt-orientation")))
            val replaced = repository.loadInk(page)
            assertNotSame(recolored.geometry, replaced.geometry)
            assertTrue(replaced.strokes.single().samples.any { it.pressure != null })
            db.inkStrokeDao().insert(row.copy(deletedAt = 10))
            assertTrue(repository.loadInk(page).strokes.isEmpty())
            db.inkStrokeDao().insert(row)
            assertEquals(1, repository.loadInk(page).strokes.size)
            val undone = InkEraseEntity("undone", page, sizeDp = 30f, points = InkFixtures.twoPointStroke,
                enc = InkCodec.ENCODING, createdAt = 700, deletedAt = 701)
            db.inkEraseDao().insert(undone)
            val clocked = repository.loadInk(page)
            assertEquals(700, clocked.latestOperationAt)
            db.syncDao().putState(SyncStateEntity(accountId = "another-account"))
            assertNotSame(clocked, repository.loadInk(page))
            repository.closeNotebook(book)
            repository.loadInk(page)
            assertEquals(0, repository.loadedInk.retainedPages)
            repository.reopenNotebook(book)
            repository.loadInk(page)
            assertEquals(1, repository.loadedInk.retainedPages)
            repository.deletePage(page)
            repository.loadInk(page)
            assertEquals(0, repository.loadedInk.retainedPages)
            assertContentEquals(row.points, db.inkStrokeDao().byIds(listOf(row.id)).single().points)
        } finally {
            library.close()
            assertEquals(0, library.repository.loadedInk.retainedPages)
            dir.deleteRecursively()
        }
    }

    @Test
    fun changedEraseTargetsAndUndoNeverReturnCachedProjections() = runBlocking {
        val db = NotesDatabase.inMemory()
        try {
            val repository = NotesRepository(db)
            repository.seedIfEmpty()
            val book = repository.createNotebook("Erase")
            val section = repository.createSection(book, "Ink")
            val page = repository.createPage(section)
            val row = LoadedInkCacheTest.row().copy(pageId = page)
            db.inkStrokeDao().insert(row)
            val erase = InkEraseEntity("erase", page, sizeDp = 30f, points = InkFixtures.twoPointStroke,
                enc = InkCodec.ENCODING, createdAt = 1)
            db.inkEraseDao().insert(erase)
            val missingTarget = repository.loadInk(page)
            assertEquals(1, missingTarget.desktopGeometry().projections.size)
            db.inkEraseDao().insertTargets(listOf(InkEraseTargetEntity(erase.id, row.id)))
            val erased = repository.loadInk(page)
            assertTrue(erased.desktopGeometry().projections.isEmpty())
            assertSame(erased, repository.loadInk(page))
            db.inkEraseDao().setDeletedAt(erase.id, 2)
            assertEquals(1, repository.loadInk(page).desktopGeometry().projections.size)
            db.inkEraseDao().setDeletedAt(erase.id, null)
            assertTrue(repository.loadInk(page).desktopGeometry().projections.isEmpty())
        } finally { db.close() }
    }
}
