package com.vivenotes.data

import androidx.room.useWriterConnection
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageRevisionEntity
import com.vivenotes.model.Block
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import com.vivenotes.model.Run
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckpointPreparationTest {
    private class Fixture(val db: NotesDatabase, val repository: NotesRepository, val page: String, val now: AtomicLong) {
        suspend fun currentRevision(createdAt: Long = now.get() - NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS): PageRevisionEntity =
            DocumentRevisionPayload.pack(db.pageContentDao().byId(page)!!, createdAt,
                InkRevisionPayload.pack(InkSnapshot.from(repository.inkFor(page), emptyList(), emptyList())))
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val root = Files.createTempDirectory("checkpoint-preparation").toFile()
        val db = NotesDatabase.create(File(root, "notes.db"))
        val now = AtomicLong(1_000_000L)
        val repository = NotesRepository(db, clock = now::get)
        try {
            val page = repository.createPage(repository.createSection(repository.createNotebook("Notebook"), "Section"))
            repository.saveDoc(page, doc("before"))
            db.inkStrokeDao().insert(stroke("base", page))
            db.pageRevisionDao().deleteForPages(listOf(page))
            now.addAndGet(NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS)
            Fixture(db, repository, page, now).block()
        } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun pausedCompressionLetsTheWriterProgressAndRetriesAnAtomicDocumentAndInkChange() = runBlocking<Unit> {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = {
                if (compressions.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            }
            val save = async { repository.saveDoc(page, doc("final!")) }
            try {
                withTimeout(5_000) { entered.await() }
                val before = db.pageContentDao().byId(page)!!
                val middle = before.copy(docJson = JsonDocumentCodec.encodeToString(doc("middle")))
                assertEquals(before.docJson.length, middle.docJson.length)
                assertEquals(before.updatedAt, middle.updatedAt)
                withTimeout(5_000) { db.withTransaction {
                    db.pageContentDao().upsert(middle)
                    db.inkStrokeDao().setColor("base", 0xff123456.toInt(), true)
                    db.inkStrokeDao().setGroup("base", "changed-group")
                    db.inkStrokeDao().insert(stroke("second", page, 1))
                    db.inkEraseDao().insert(InkEraseEntity("erase", page, EraserMode.Normal,
                        3f, byteArrayOf(1), "opaque/test", now.get()))
                    db.inkMoveDao().insert(InkMoveEntity("move", page, 1f, 2f,
                        points = byteArrayOf(1), enc = "opaque/test", createdAt = now.get()))
                    db.pageDao().rename(page, "Writer progressed", now.get())
                } }
                assertEquals("Writer progressed", db.pageDao().byId(page)!!.title)
            } finally { release.complete(Unit) }
            save.await()
            assertEquals(2, compressions.get())
            val row = db.pageRevisionDao().byId(page, repository.revisionHistory(page).single().id)!!
            assertEquals(doc("middle"), DocumentRevisionPayload.unpack(row))
            val snapshot = InkRevisionPayload.unpack(row)
            assertEquals(listOf("base", "second"), snapshot.strokes.map { it.id })
            assertEquals(InkSnapshot.StrokeState("base", 0xff123456.toInt(), true, "changed-group"), snapshot.strokes[0])
            assertEquals(listOf("erase"), snapshot.eraseIds)
            assertEquals(listOf("move"), snapshot.moveIds)
            assertEquals(doc("final!"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
        }
    }

    @Test
    fun revisionPayloadChangedAfterValidationIsRecheckedWithoutRecompressingThePage() = runBlocking<Unit> {
        fixture {
            val original = currentRevision()
            db.pageRevisionDao().insert(original)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val preparations = AtomicInteger()
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = { compressions.incrementAndGet() }
            repository.afterCheckpointPreparation = {
                if (preparations.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            }
            val save = async { repository.saveDoc(page, doc("final!")) }
            try {
                withTimeout(5_000) { entered.await() }
                db.withTransaction {
                    db.pageRevisionDao().deleteByIds(listOf(original.id))
                    db.pageRevisionDao().insert(original.copy(payload = byteArrayOf(1, 2, 3)))
                }
            } finally { release.complete(Unit) }
            save.await()
            assertEquals(1, compressions.get(), "Changing history must reuse unchanged page compression")
            assertEquals(2, repository.revisionHistory(page).size)
            val newest = repository.revisionHistory(page).first()
            assertEquals(doc("before"), assertIs<PageRevisionLoad.Loaded>(repository.loadRevision(page, newest.id)).doc)
            assertIs<PageRevisionLoad.Unreadable>(repository.loadRevision(page, original.id))
        }
    }

    @Test
    fun concurrentHistoryDeletionAndTrimPreserveTheSafetyCopyAndTheFortyVersionLimit() = runBlocking<Unit> {
        fixture {
            val original = currentRevision()
            db.pageRevisionDao().insert(original)
            val body = db.pageContentDao().byId(page)!!
            val ink = InkRevisionPayload.pack(InkRevisionPayload.unpack(original))
            val replacements = (0 until 40).map { index ->
                DocumentRevisionPayload.pack(body.copy(docJson = JsonDocumentCodec.encodeToString(doc("history-$index"))),
                    now.get() - NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS - 1_000L - index, ink)
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val preparations = AtomicInteger()
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = { compressions.incrementAndGet() }
            repository.afterCheckpointPreparation = {
                if (preparations.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            }
            val save = async { repository.saveDoc(page, doc("final!")) }
            try {
                withTimeout(5_000) { entered.await() }
                db.withTransaction {
                    db.pageRevisionDao().deleteForPages(listOf(page))
                    replacements.forEach { db.pageRevisionDao().insert(it) }
                }
            } finally { release.complete(Unit) }
            save.await()
            assertEquals(1, compressions.get())
            val history = repository.revisionHistory(page)
            assertEquals(40, history.size)
            assertNull(db.pageRevisionDao().byId(page, original.id))
            assertEquals(doc("before"), assertIs<PageRevisionLoad.Loaded>(repository.loadRevision(page, history.first().id)).doc)
            history.forEach { assertIs<PageRevisionLoad.Loaded>(repository.loadRevision(page, it.id)) }
        }
    }

    @Test
    fun forcedRestoreCheckpointsTheLatestAtomicStateEvenWhenItsLoadedTargetWasTrimmed() = runBlocking<Unit> {
        fixture {
            val body = db.pageContentDao().byId(page)!!
            val targetInk = InkSnapshot(listOf(InkSnapshot.StrokeState("base", 0xff778899.toInt(), false, "target-group")),
                emptyList(), emptyList())
            val target = DocumentRevisionPayload.pack(body.copy(docJson = JsonDocumentCodec.encodeToString(doc("target"))),
                now.get() - NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS, InkRevisionPayload.pack(targetInk))
            db.pageRevisionDao().insert(target)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = {
                if (compressions.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            }
            val restore = async { repository.restoreRevision(page, target.id) }
            try {
                withTimeout(5_000) { entered.await() }
                db.withTransaction {
                    db.pageRevisionDao().deleteByIds(listOf(target.id))
                    db.pageContentDao().upsert(body.copy(docJson = JsonDocumentCodec.encodeToString(doc("middle"))))
                    db.inkStrokeDao().setColor("base", 0xff123456.toInt(), true)
                }
            } finally { release.complete(Unit) }
            assertIs<PageRevisionLoad.Loaded>(restore.await())
            assertEquals(2, compressions.get())
            assertEquals(doc("target"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
            val stroke = repository.inkFor(page).single()
            assertEquals(0xff778899.toInt(), stroke.colorArgb)
            assertEquals("target-group", stroke.groupId)
            assertTrue(stroke.points.contentEquals(byteArrayOf(1, 2, 3)))
            val safety = db.pageRevisionDao().byId(page, repository.revisionHistory(page).single().id)!!
            assertEquals(doc("middle"), DocumentRevisionPayload.unpack(safety))
            assertEquals(0xff123456.toInt(), InkRevisionPayload.unpack(safety).strokes.single().colorArgb)
        }
    }

    @Test
    fun batchIntermediateSnapshotsPrepareAfterRollbackAndNeverExposePartialInk() = runBlocking<Unit> {
        fixture {
            db.pageRevisionDao().insert(currentRevision())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = {
                if (compressions.incrementAndGet() == 2) { entered.complete(Unit); release.await() }
            }
            val edit = InkEdit.Batch(listOf(InkEdit.AddStroke(stroke("second", page)), InkEdit.EraseStrokes(setOf("base"))))
            val save = async { repository.applyInkEdit(page, edit) }
            try {
                withTimeout(5_000) { entered.await() }
                assertEquals(listOf("base"), repository.inkFor(page).map { it.id })
                withTimeout(5_000) { repository.renamePage(page, "Unblocked") }
                assertEquals("Unblocked", db.pageDao().byId(page)!!.title)
            } finally { release.complete(Unit) }
            save.await()
            assertEquals(2, compressions.get())
            assertEquals(listOf("second"), repository.inkFor(page).map { it.id })
            val latest = db.pageRevisionDao().byId(page, repository.revisionHistory(page).first().id)!!
            assertEquals(listOf("base", "second"), InkRevisionPayload.unpack(latest).strokes.map { it.id })
        }
    }

    @Test
    fun pagePurgedDuringCompressionCannotBeRecreatedOrLeaveAnOrphanRevision() = runBlocking<Unit> {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repository.beforeCheckpointCompression = { entered.complete(Unit); release.await() }
            val save = async { runCatching { repository.saveDoc(page, doc("final!")) } }
            try {
                withTimeout(5_000) { entered.await() }
                withTimeout(5_000) { db.useWriterConnection { writer ->
                    writer.usePrepared("DELETE FROM pages WHERE id = ?") { it.bindText(1, page); it.step() }
                } }
            } finally { release.complete(Unit) }
            assertNotNull(save.await().exceptionOrNull())
            assertNull(db.pageDao().byId(page))
            assertNull(db.pageContentDao().byId(page))
            assertTrue(repository.revisionHistory(page).isEmpty())
            assertTrue(repository.inkFor(page).isEmpty())
        }
    }

    @Test
    fun cancellationAndCompressionFailureLeaveTheOldStateIntactAndAllowRetry() = runBlocking<Unit> {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repository.beforeCheckpointCompression = { entered.complete(Unit); release.await() }
            val save = async { repository.saveDoc(page, doc("final!")) }
            try {
                withTimeout(5_000) { entered.await() }
                withTimeout(5_000) { save.cancelAndJoin() }
                assertTrue(save.isCancelled)
            } finally { release.complete(Unit) }
            assertEquals(doc("before"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
            assertTrue(repository.revisionHistory(page).isEmpty())
            repository.beforeCheckpointCompression = { throw IOException("compression failed") }
            assertFailsWith<IOException> { repository.saveDoc(page, doc("final!")) }
            assertEquals(doc("before"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
            assertTrue(repository.revisionHistory(page).isEmpty())
            repository.beforeCheckpointCompression = {}
            repository.saveDoc(page, doc("final!"))
            assertEquals(doc("final!"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
        }
    }

    @Test
    fun persistentConflictsHaveABoundedRetryLimitAndNeverCommitTheStaleMutation() = runBlocking<Unit> {
        fixture {
            val compressions = AtomicInteger()
            repository.beforeCheckpointCompression = {
                val generation = compressions.incrementAndGet()
                val body = db.pageContentDao().byId(page)!!
                db.pageContentDao().upsert(body.copy(docJson = JsonDocumentCodec.encodeToString(doc("race-$generation"))))
            }
            withTimeout(10_000) {
                assertFailsWith<java.util.ConcurrentModificationException> { repository.saveDoc(page, doc("final!")) }
            }
            assertTrue(compressions.get() in 2..16)
            assertEquals(doc("race-${compressions.get()}"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
            assertTrue(repository.revisionHistory(page).isEmpty())
            assertEquals(listOf("base"), repository.inkFor(page).map { it.id })
            repository.beforeCheckpointCompression = {}
            repository.saveDoc(page, doc("final!"))
            assertEquals(doc("final!"), assertIs<PageLoad.Loaded>(repository.loadDoc(page)).doc)
        }
    }

    private companion object {
        fun doc(text: String) = PageDoc(outlines = listOf(Outline.Text("text",
            blocks = listOf(Block("block", runs = listOf(Run(text)))))))

        fun stroke(id: String, page: String, seq: Int = 0) = InkStrokeEntity(id, page, seq, "marker", 1,
            3f, 0xff000000.toInt(), false, 0.1f, 0, 0f, 0f, 10f, 10f,
            byteArrayOf(1, 2, 3), "opaque/test", 1_000_000L)
    }
}
