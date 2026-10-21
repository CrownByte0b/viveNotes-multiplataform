package com.vivenotes.data.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.data.db.SyncStateEntity
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.Outline
import com.vivenotes.model.PageDoc
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AttachmentDiscoveryConcurrencyTest {
    @Test
    fun aFullScanRetainsCapturedCandidatesWhoseInsertHasNotCommittedYet() = runBlocking<Unit> {
        fixture { db, directory ->
            val content = "candidate captured before its WAL commit".toByteArray()
            val digest = sha(content)
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            val client = PictureTransport(digest, content)
            val blobs = AttachmentBlobSync(db, client, bytes)
            blobs.bindAccount(account())
            val inserted = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.useWriterConnection { connection -> connection.immediateTransaction {
                    db.attachmentDao().insert(attachment(digest, content))
                    blobs.noteAttachments(listOf(digest))
                    inserted.complete(Unit)
                    commit.await()
                } }
            }
            try {
                withTimeout(5_000) { inserted.await() }
                assertNull(db.attachmentDao().byId(digest), "A separate WAL reader still sees the pre-commit state")
                assertTrue(withTimeout(5_000) { blobs.hasMissingBytes() },
                    "A full scan must retain its consumed additions even when its SQL snapshot lacks them")
                assertEquals(0, client.downloads.get())
                commit.complete(Unit)
                withTimeout(5_000) { writer.await() }
                assertEquals(1, blobs.downloadMissing(account()).downloaded)
                assertContentEquals(content, bytes.fileFor(digest).readBytes())
            } finally { commit.complete(Unit); writer.cancelAndJoin() }
        }
    }

    @Test
    fun aReconcileRequestedDuringPresenceIoCannotBeOverwrittenByTheOlderScan() = runBlocking<Unit> {
        fixture { db, directory ->
            val existing = "existing file".toByteArray()
            val added = "new row for the requested repair scan".toByteArray()
            val existingId = sha(existing)
            val addedId = sha(added)
            db.attachmentDao().insert(attachment(existingId, existing))
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            bytes.write(existingId, existing)
            val presenceEntered = CountDownLatch(1)
            val presenceRelease = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            val observedBytes = object : AttachmentBytes by bytes {
                override fun fileFor(id: String): File {
                    if (id == existingId && pauseOnce.compareAndSet(true, false)) {
                        presenceEntered.countDown()
                        check(presenceRelease.await(10, TimeUnit.SECONDS)) { "Presence scan was not released" }
                    }
                    return bytes.fileFor(id)
                }
            }
            val client = PictureTransport(addedId, added)
            val blobs = AttachmentBlobSync(db, client, observedBytes)
            blobs.bindAccount(account())
            val scan = async { blobs.hasMissingBytes() }
            try {
                await(presenceEntered)
                db.attachmentDao().insert(attachment(addedId, added))
                // An import/repair request can add rows without publishing an incremental candidate.
                blobs.reconcileFiles()
                presenceRelease.countDown()
                assertTrue(withTimeout(5_000) { scan.await() }, "The newer full-scan request must win")
                assertEquals(1, blobs.downloadMissing(account()).downloaded)
                assertContentEquals(added, bytes.fileFor(addedId).readBytes())
            } finally { presenceRelease.countDown(); scan.cancelAndJoin() }
        }
    }

    @Test
    fun anOlderPresenceScanCannotRequeueAPictureDeferredByAnInFlight404() = runBlocking<Unit> {
        fixture { db, directory ->
            val content = "picture the server no longer has".toByteArray()
            val digest = sha(content)
            db.attachmentDao().insert(attachment(digest, content))
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            val presenceEntered = CountDownLatch(1)
            val presenceRelease = CountDownLatch(1)
            val pausePresence = AtomicBoolean(false)
            val observedBytes = object : AttachmentBytes by bytes {
                override fun fileFor(id: String): File {
                    if (id == digest && pausePresence.compareAndSet(true, false)) {
                        presenceEntered.countDown()
                        check(presenceRelease.await(10, TimeUnit.SECONDS)) { "Presence scan was not released" }
                    }
                    return bytes.fileFor(id)
                }
            }
            val enteredDownload = CompletableDeferred<Unit>()
            val releaseDownload = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val client = object : SyncTransport by PictureTransport(digest, content) {
                override suspend fun downloadBlob(serverBaseUrl: String, token: String, digest: String,
                    target: File): ServerResult<Boolean> {
                    requests.incrementAndGet()
                    enteredDownload.complete(Unit)
                    releaseDownload.await()
                    return ServerResult.Success(false)
                }
            }
            val blobs = AttachmentBlobSync(db, client, observedBytes)
            blobs.bindAccount(account())
            val download = async { blobs.downloadMissing(account()) }
            try {
                withTimeout(5_000) { enteredDownload.await() }
                pausePresence.set(true)
                blobs.reconcileFiles()
                val scan = async { blobs.hasMissingBytes() }
                try {
                    await(presenceEntered)
                    releaseDownload.complete(Unit)
                    assertEquals(BlobDownloads(0, workRemains = false), withTimeout(5_000) { download.await() })
                    // The GET has removed this ID from missing and added it to deferred while the
                    // older scan still owns its pre-404 presence work.
                    presenceRelease.countDown()
                    assertFalse(withTimeout(5_000) { scan.await() }, "An older absent-file observation cannot undo the 404 deferral")
                    assertFalse(blobs.hasMissingBytes())
                    assertEquals(BlobDownloads(0, workRemains = false), blobs.downloadMissing(account()))
                    assertEquals(1, requests.get(), "A deferred digest is not requested again in this process")
                    assertFalse(bytes.fileFor(digest).exists())
                    assertTrue(directory.walkTopDown().none { it.extension == "part" })
                } finally { presenceRelease.countDown(); scan.cancelAndJoin() }
            } finally { releaseDownload.complete(Unit); presenceRelease.countDown(); download.cancelAndJoin() }
        }
    }

    @Test
    fun aCloudRestorePublishesItsPictureCandidateOnlyAfterThePictureTransactionCommits() = runBlocking<Unit> {
        val driver = AttachmentCommitDriver()
        fixture(driver) { db, directory ->
            seedCloudIndex(db)
            val content = "picture restored after commit".toByteArray()
            val digest = sha(content)
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            val client = PictureTransport(digest, content)
            val blobs = AttachmentBlobSync(db, client, bytes)
            blobs.bindAccount(account())
            assertFalse(blobs.hasMissingBytes())
            val hierarchy = HierarchySync(db, client, RemoteInkSignal(), blobs)
            val commitEntered = CountDownLatch(1)
            val commitRelease = CountDownLatch(1)
            driver.beforeCommit.set {
                commitEntered.countDown()
                check(commitRelease.await(10, TimeUnit.SECONDS)) { "Picture commit was not released" }
            }
            val restoring = async { hierarchy.restoreFromCloud(account(), "n") }
            try {
                await(commitEntered)
                assertNull(db.attachmentDao().byId(digest))
                blobs.reconcileFiles()
                assertFalse(withTimeout(5_000) { blobs.hasMissingBytes() }, "Uncommitted restore metadata is not a download candidate")
                assertEquals(0, client.downloads.get())
                commitRelease.countDown()
                assertEquals(CloudArchiveResult.BroughtBack, withTimeout(5_000) { restoring.await() })
                assertEquals(1, client.downloads.get())
                assertContentEquals(content, bytes.fileFor(digest).readBytes())
                assertFalse(blobs.hasMissingBytes())
            } finally { commitRelease.countDown(); restoring.cancelAndJoin() }
        }
    }

    @Test
    fun aRolledBackCloudPictureTransactionDoesNotLeaveAGhostDownloadCandidate() = runBlocking<Unit> {
        val driver = AttachmentCommitDriver()
        fixture(driver) { db, directory ->
            seedCloudIndex(db)
            val content = "rolled back picture".toByteArray()
            val digest = sha(content)
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            val client = PictureTransport(digest, content)
            val blobs = AttachmentBlobSync(db, client, bytes)
            blobs.bindAccount(account())
            assertFalse(blobs.hasMissingBytes())
            val hierarchy = HierarchySync(db, client, RemoteInkSignal(), blobs)
            driver.beforeApplyingRemoteOff.set { throw IOException("injected picture transaction failure") }

            assertIs<CloudArchiveResult.Failed>(hierarchy.restoreFromCloud(account(), "n"))

            assertNull(db.attachmentDao().byId(digest))
            assertFalse(db.syncDao().state()!!.applyingRemote)
            assertFalse(blobs.hasMissingBytes(), "A rollback must not publish a candidate for its removed row")
            assertEquals(0, blobs.downloadMissing(account()).downloaded)
            assertEquals(0, client.downloads.get())
            assertFalse(bytes.fileFor(digest).exists())
            assertEquals(CloudArchiveResult.BroughtBack, hierarchy.restoreFromCloud(account(), "n"))
            assertContentEquals(content, bytes.fileFor(digest).readBytes())
        }
    }

    @Test
    fun cancellationAfterSqliteCommitStillMakesTheDurablePictureDiscoverable() = runBlocking<Unit> {
        val driver = AttachmentCommitDriver()
        fixture(driver) { db, directory ->
            seedCloudIndex(db)
            val content = "committed before cancellation delivery".toByteArray()
            val digest = sha(content)
            val bytes = TemporaryAttachmentBytes(File(directory, "pictures"))
            val client = PictureTransport(digest, content)
            val blobs = AttachmentBlobSync(db, client, bytes)
            blobs.bindAccount(account())
            assertFalse(blobs.hasMissingBytes())
            val hierarchy = HierarchySync(db, client, RemoteInkSignal(), blobs)
            val committed = CountDownLatch(1)
            val returnToRoom = CountDownLatch(1)
            driver.afterCommit.set {
                committed.countDown()
                check(returnToRoom.await(10, TimeUnit.SECONDS)) { "Committed writer was not released" }
            }
            val restoring = async { hierarchy.restoreFromCloud(account(), "n") }
            try {
                await(committed)
                assertEquals(digest, db.attachmentDao().byId(digest)!!.id, "SQLite already committed this row")
                restoring.cancel()
                returnToRoom.countDown()
                withTimeout(5_000) { restoring.join() }
                assertTrue(restoring.isCancelled)
                assertFalse(db.syncDao().state()!!.applyingRemote)
                assertTrue(blobs.hasMissingBytes(), "Cancellation must invalidate a cache whose postcommit publication was skipped")
                assertEquals(1, blobs.downloadMissing(account()).downloaded)
                assertContentEquals(content, bytes.fileFor(digest).readBytes())
            } finally { returnToRoom.countDown(); restoring.cancelAndJoin() }
        }
    }

    private suspend fun fixture(driver: SQLiteDriver = BundledSQLiteDriver(),
        block: suspend (NotesDatabase, File) -> Unit) {
        val directory = Files.createTempDirectory("attachment-discovery").toFile()
        val db = Room.databaseBuilder<NotesDatabase>(name = File(directory, "notes.db").absolutePath)
            .setDriver(driver).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO).addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).build()
        try { block(db, directory) }
        finally { db.close(); assertTrue(directory.deleteRecursively()) }
    }

    private suspend fun seedCloudIndex(db: NotesDatabase) {
        db.useWriterConnection { connection -> connection.immediateTransaction {
            db.notebookDao().insert(NotebookEntity("n", "Cloud notebook", 1, 0, createdAt = 1L,
                updatedAt = 1L, closedAt = 1L, cloudOnlyAt = 1L))
            db.sectionDao().insert(SectionEntity("s", "n", "Section", 1, 0, 1L, 1L))
            db.pageDao().insert(PageEntity("p", "s", "Page", 0, createdAt = 1L, updatedAt = 1L))
            db.syncDao().putState(SyncStateEntity(accountId = account().accountId))
        } }
    }

    private class AttachmentCommitDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver by delegate {
        val beforeCommit = AtomicReference<(() -> Unit)?>()
        val afterCommit = AtomicReference<(() -> Unit)?>()
        val beforeApplyingRemoteOff = AtomicReference<(() -> Unit)?>()
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                private var attachmentInserted = false
                override fun prepare(sql: String): SQLiteStatement {
                    val normalized = sql.trim().uppercase()
                    val commit = normalized.startsWith("END TRANSACTION") || normalized.startsWith("COMMIT")
                    val rollback = normalized.startsWith("ROLLBACK")
                    val insert = normalized.startsWith("INSERT") && normalized.contains("ATTACHMENTS")
                    val applyingRemote = normalized.startsWith("UPDATE SYNC_STATE SET APPLYINGREMOTE")
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        private var applyingRemoteValue: Long? = null
                        override fun bindLong(index: Int, value: Long) {
                            if (applyingRemote && index == 1) applyingRemoteValue = value
                            statement.bindLong(index, value)
                        }
                        override fun step(): Boolean {
                            if (attachmentInserted && applyingRemote && applyingRemoteValue == 0L) {
                                beforeApplyingRemoteOff.getAndSet(null)?.invoke()
                            }
                            val pictureCommit = commit && attachmentInserted
                            if (pictureCommit) beforeCommit.getAndSet(null)?.invoke()
                            val result = statement.step()
                            if (insert) attachmentInserted = true
                            if (commit || rollback) attachmentInserted = false
                            if (pictureCommit) afterCommit.getAndSet(null)?.invoke()
                            return result
                        }
                    }
                }
            }
        }
    }

    private class PictureTransport(val digest: String, private val content: ByteArray) : SyncTransport {
        val downloads = AtomicInteger()
        override suspend fun getCursor(serverBaseUrl: String, token: String) = ServerResult.Success(2L)
        override suspend fun pullChanges(serverBaseUrl: String, token: String, since: Long, limit: Int): ServerResult<PullChangesPage> =
            ServerResult.Success(PullChangesPage(listOf(documentChange(digest), attachmentChange(digest, content.size)), 2L, false))
        override suspend fun pushChanges(serverBaseUrl: String, token: String, batchId: String, changes: List<JsonObject>): ServerResult<PushChangesReply> =
            error("Unexpected metadata push")
        override suspend fun revokeDevice(serverBaseUrl: String, token: String, deviceId: String) = ServerResult.Success(Unit)
        override suspend fun hasBlob(serverBaseUrl: String, token: String, digest: String) = ServerResult.Success(false)
        override suspend fun uploadBlob(serverBaseUrl: String, token: String, digest: String, file: File): ServerResult<Boolean> =
            error("Unexpected picture upload")
        override suspend fun downloadBlob(serverBaseUrl: String, token: String, digest: String, target: File): ServerResult<Boolean> {
            check(digest == this.digest)
            downloads.incrementAndGet()
            target.writeBytes(content)
            return ServerResult.Success(true)
        }
    }

    private companion object {
        fun account() = SyncAccount("https://sync.example.test", "account", "device", "test-token", "Test")
        fun attachment(id: String, bytes: ByteArray) = AttachmentEntity(id, "image/webp", 8, 8, bytes.size.toLong(), 0, 1L)
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "The expected SQLite/IO phase was not reached")
        }
        fun documentChange(digest: String): JsonObject {
            val body = JsonDocumentCodec.encodeToString(PageDoc(outlines = listOf(Outline.Image("image", attachmentId = digest, height = 80f))))
                .toByteArray()
            return buildJsonObject {
                put("kind", "pageContent"); put("id", "p"); put("pageId", "p")
                put("version", 1L); put("seq", 1L); put("updatedAt", 1L); put("deletedAt", JsonNull)
                put("doc", Base64.getEncoder().encodeToString(body))
                put("docSha256", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)))
                put("format", JsonDocumentCodec.id); put("enc", "none/1")
            }
        }
        fun attachmentChange(digest: String, size: Int) = buildJsonObject {
            put("kind", "attachment"); put("id", digest); put("version", 1L); put("seq", 2L)
            put("updatedAt", 1L); put("deletedAt", JsonNull); put("createdAt", 1L)
            put("mimeType", "image/webp"); put("pixelWidth", 8); put("pixelHeight", 8); put("byteCount", size)
        }
    }
}
