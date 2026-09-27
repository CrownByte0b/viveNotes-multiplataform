package com.vivenotes.data

import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.StrokeColor
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.model.PageDoc
import com.vivenotes.model.newId
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkFixtures
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ink storage: rows are kept exactly and removed only by tombstone, whatever the desktop can draw.
 *
 * The desktop has no ink tools yet, but pages arrive with ink from Android, and every revision,
 * blank test and purge reads these tables. The collection cases are the repository half of the
 * Android `ErasedInkCollectionTest`; its other half replays stroke geometry, which comes with ink.
 */
class InkStorageTest {

    private lateinit var db: NotesDatabase
    private lateinit var repository: NotesRepository
    private var now = 10_000L

    @BeforeTest
    fun setUp() {
        db = NotesDatabase.inMemory()
        repository = NotesRepository(db, clock = { now })
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun erasingTombstonesStrokesAndRestoringBringsBackTheSameRows() = runBlocking<Unit> {
        val pageId = newPage()
        val kept = repository.addStroke(stroke(pageId, points = byteArrayOf(1, 2, 3)))
        val erased = repository.addStroke(stroke(pageId, points = byteArrayOf(4, 5, 6)))

        repository.eraseStrokes(listOf(erased.id))

        assertEquals(listOf(kept.id), repository.inkFor(pageId).map { it.id })
        assertNotNull(deletedAt(erased.id), "an erase must stay a replicable tombstone")

        repository.restoreStrokes(listOf(erased.id))

        val restored = repository.inkFor(pageId)
        assertEquals(listOf(kept.id, erased.id), restored.map { it.id })
        assertContentEquals(byteArrayOf(4, 5, 6), restored.last().points)
    }

    @Test
    fun storedAndroidInkLoadsForDisplayWithoutRewritingItsBlob() = runBlocking<Unit> {
        val pageId = newPage()
        val blob = InkFixtures.twoPointStroke
        val row = repository.addStroke(stroke(pageId, points = blob).copy(enc = InkCodec.ENCODING))

        val shown = repository.loadInk(pageId)

        assertEquals(listOf(row.id), shown.strokes.map { it.id })
        assertEquals(listOf(10f, 30f), shown.strokes.single().samples.map { it.x })
        assertContentEquals(blob, repository.inkFor(pageId).single().points)
    }

    @Test
    fun copiedStrokesJoinTheDrawOrderAsOneContiguousBlock() = runBlocking<Unit> {
        val pageId = newPage()
        db.inkStrokeDao().insert(stroke(pageId, seq = 7))

        val copies = repository.addStrokes(List(3) { stroke(pageId, seq = 0) })

        assertEquals(listOf(8, 9, 10), copies.map { it.seq })
        assertEquals(copies.map { it.id }, repository.inkFor(pageId).drop(1).map { it.id })
    }

    @Test
    fun aCopiedSelectionCannotSpanPages() = runBlocking<Unit> {
        val first = newPage()
        val second = newPage()

        assertFailsWith<IllegalArgumentException> {
            repository.addStrokes(listOf(stroke(first), stroke(second)))
        }
        assertTrue(repository.inkFor(first).isEmpty())
    }

    @Test
    fun groupingAndRecolouringEditOnlyThoseColumns() = runBlocking<Unit> {
        val pageId = newPage()
        val row = repository.addStroke(stroke(pageId, points = byteArrayOf(9, 9)))

        repository.setInkGroups(mapOf(row.id to "group"))
        repository.setInkColors(mapOf(row.id to StrokeColor(0xFF0000FF.toInt(), false)))

        val stored = repository.inkFor(pageId).single()
        assertEquals("group", stored.groupId)
        assertEquals(0xFF0000FF.toInt(), stored.colorArgb)
        assertEquals(false, stored.colorFollowsTheme)
        assertContentEquals(byteArrayOf(9, 9), stored.points)

        repository.setInkGroups(mapOf(row.id to null))
        assertNull(repository.inkFor(pageId).single().groupId)
    }

    @Test
    fun aLassoMoveIsStoredWithItsTargetsAndUndoKeepsItOnTheClock() = runBlocking<Unit> {
        val pageId = newPage()
        val row = repository.addStroke(stroke(pageId))
        val move = InkMoveEntity(
            id = "move",
            pageId = pageId,
            dxDp = 12f,
            dyDp = -4f,
            scaleX = 2f,
            anchorX = 5f,
            points = byteArrayOf(1),
            enc = "test/1",
            createdAt = 7_000L,
        )

        repository.addInkMove(move, listOf(row.id, row.id))

        val stored = repository.inkMovesFor(pageId).single()
        assertEquals(2f, stored.move.scaleX)
        assertEquals(listOf(row.id), stored.targets.map { it.strokeId })

        repository.setInkMoveActive("move", active = false)

        assertTrue(repository.inkMovesFor(pageId).isEmpty())
        assertEquals(7_000L, repository.latestInkOperationAt(pageId))

        repository.setInkMoveActive("move", active = true)
        assertEquals(listOf("move"), repository.inkMovesFor(pageId).map { it.move.id })
    }

    /** Deadness is a conclusion every device reaches for itself, so it is never pushed. */
    @Test
    fun collectingDoesNotQueueTheStrokeForSyncTheWayADeleteDoes() = runBlocking<Unit> {
        db.syncDao().putState(SyncStateEntity(accountId = "account"))
        val pageId = newPage()
        val collected = repository.addStroke(stroke(pageId)).id
        val deleted = repository.addStroke(stroke(pageId)).id
        db.syncDao().clearOutbox()

        repository.collectErasedAwayStrokes(listOf(collected))
        assertEquals(emptyList(), queuedStrokeIds())
        assertNotNull(deletedAt(collected))

        repository.eraseStrokes(listOf(deleted))
        assertEquals(listOf<String?>(deleted), queuedStrokeIds())
        assertEquals(0L, db.long("SELECT applyingRemote FROM sync_state"), "the suppression leaked")
    }

    /** Undo puts the erase back in the past tense, and the ink it finished off has to come back. */
    @Test
    fun undoingTheEraseRestoresTheStrokeItCollected() = runBlocking<Unit> {
        val pageId = newPage()
        val strokeId = repository.addStroke(stroke(pageId)).id
        val eraseId = eraseAll(pageId, strokeId)
        repository.collectErasedAwayStrokes(listOf(strokeId))
        assertNotNull(deletedAt(strokeId))

        repository.setPartialEraseActive(eraseId, active = false)

        assertNull(deletedAt(strokeId), "undo left the ink tombstoned")
        assertEquals(listOf(strokeId), repository.inkFor(pageId).map { it.id })
    }

    /** The point of using a tombstone: the seven-day purge already knows what to do with one. */
    @Test
    fun theSevenDayPurgeCollectsIt() = runBlocking<Unit> {
        val pageId = newPage()
        val strokeId = repository.addStroke(stroke(pageId)).id
        eraseAll(pageId, strokeId)
        repository.collectErasedAwayStrokes(listOf(strokeId))

        now += NotesRepository.DELETION_RETENTION_MILLIS + 1
        val purged = repository.purgeExpiredDeletions(now)

        assertEquals(1, purged.inkStrokes)
        assertTrue(db.inkStrokeDao().byIds(listOf(strokeId)).isEmpty())
    }

    /** The one thing collection must not cost: a revision that names the stroke still restores it. */
    @Test
    fun aRevisionTakenBeforeTheEraseStillRestoresTheStroke() = runBlocking<Unit> {
        val pageId = newPage()
        repository.saveDoc(pageId, PageDoc.empty())
        val strokeId = repository.addStroke(stroke(pageId)).id
        // Past the coalescing window, so the erase below takes its own checkpoint — and that one
        // holds the page as it was a moment ago, with the stroke still on it.
        now += NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS + 1
        eraseAll(pageId, strokeId)
        repository.collectErasedAwayStrokes(listOf(strokeId))
        assertNotNull(deletedAt(strokeId))
        val revision = repository.revisionHistory(pageId)
            .first { revision -> strokeId in strokeIdsNamedBy(pageId, revision.id) }

        repository.restoreRevision(pageId, revision.id)

        assertNull(deletedAt(strokeId), "a revision naming the stroke restored without it")
        assertEquals(listOf(strokeId), repository.inkFor(pageId).map { it.id })
    }

    private suspend fun newPage(): String {
        val notebookId = repository.createNotebook("Notebook")
        val sectionId = repository.createSection(notebookId, "Section")
        return repository.createPage(sectionId, "Page")
    }

    private fun stroke(pageId: String, seq: Int = 0, points: ByteArray = byteArrayOf(1)) = InkStrokeEntity(
        id = newId(),
        pageId = pageId,
        seq = seq,
        brushFamily = "marker",
        brushVersion = 1,
        sizeDp = 4f,
        colorArgb = 0xFF000000.toInt(),
        epsilon = 0.1f,
        stabilization = 0,
        minX = 0f,
        minY = 0f,
        maxX = 1f,
        maxY = 1f,
        points = points,
        enc = "test/1",
        createdAt = now,
    )

    /** An erase naming [strokeId]; which of its strokes it finishes off is the geometry's call. */
    private suspend fun eraseAll(pageId: String, strokeId: String): String {
        val erase = InkEraseEntity(
            id = newId(),
            pageId = pageId,
            sizeDp = 40f,
            points = byteArrayOf(2),
            enc = "test/1",
            createdAt = now,
        )
        repository.addPartialErase(erase, listOf(strokeId))
        return erase.id
    }

    private suspend fun deletedAt(strokeId: String): Long? =
        db.inkStrokeDao().byIds(listOf(strokeId)).firstOrNull()?.deletedAt

    private suspend fun queuedStrokeIds(): List<String?> =
        db.strings("SELECT entityId FROM sync_outbox WHERE kind = 'inkStroke'")

    /** The strokes a stored revision claims were on the page, read out of its own snapshot. */
    private suspend fun strokeIdsNamedBy(pageId: String, revisionId: String): List<String> {
        val row = db.pageRevisionDao().byId(pageId, revisionId) ?: return emptyList()
        return InkRevisionPayload.unpack(row).strokes.map { it.id }
    }
}
