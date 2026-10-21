// Copied from the Android app's `data/sync/AttachmentBlobSync.kt`; only Android-only imports differ.
package com.vivenotes.data.sync

import com.vivenotes.data.Log
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The bytes half of an attachment, as synchronisation needs it.
 *
 * An interface rather than a direct dependency on `AttachmentStore` for the reason [SyncTransport]
 * is one: this is the seam a test hands a temporary directory, so a suite that proves a picture
 * crosses between devices does not write into the real `filesDir` of whatever tablet it runs on.
 * `AttachmentStore` is the only production implementation and owns every decision about *what* gets
 * stored; this is only about where the bytes are and when they arrive.
 */
interface AttachmentBytes {

    /** The content-addressed file for [id], which may not exist yet. */
    fun fileFor(id: String): File

    /** Where a download is written before its digest is proved. Beside [fileFor], so it can rename. */
    fun stagingFor(id: String): File

    /** Moves proved bytes into place under [id] and announces them. False if it could not. */
    fun publish(staged: File, id: String): Boolean

    /** Bumped whenever bytes appear. Collected by anything showing a picture that would not draw. */
    val arrivals: StateFlow<Long>
}

/** Whether the server can be made to hold one digest, and what to do if it cannot. */
sealed interface BlobPresence {

    /**
     * The server holds the bytes. [uploaded] is true only when this call is what put them there,
     * so a run can say how many pictures it actually carried rather than how many it checked.
     */
    data class Present(val uploaded: Boolean) : BlobPresence

    /**
     * This device cannot supply these bytes and no retry will change that — the file is not here, or
     * the server refused them permanently. The change naming the digest has to stop naming it.
     */
    data object Undeliverable : BlobPresence

    /**
     * The account has no room for these [bytes]. Not [Undeliverable]: the picture goes once there is
     * room, so what names it waits in the queue while everything else still syncs.
     */
    data class NoRoom(val bytes: Long) : BlobPresence

    /** The connection failed, or the token did. The whole phase stops on [result]. */
    data class Stopped(val result: SyncRunResult) : BlobPresence
}

/** What one download pass moved, and whether it left anything behind. */
data class BlobDownloads(val downloaded: Int, val workRemains: Boolean)

/**
 * Attachment bytes, both directions.
 *
 * The rule this phase exists to keep is the server's: a live row never points at bytes the server
 * cannot serve. It enforces that by rejecting a `pageContent` whose `blobRefs` name a digest it does
 * not hold, so on this side uploading comes before the change that references it, and a
 * `missing_blob` rejection is the one failure a client can always clear on its own.
 *
 * Two memos, both in memory and deliberately not persisted:
 *
 *  - [serverHolds] is what makes a steady-state run cost nothing. The durable half of the same fact
 *    is `sync_entity_states`: an `attachment` row the server accepted proves the bytes are there.
 *    This set only saves the round trip within a process.
 *  - [deferred] stops a picture the server does not have from being asked for on every later event
 *    for the rest of the process. Forgotten on restart, which is the cheapest retry policy that
 *    cannot spin.
 */
class AttachmentBlobSync(
    db: NotesDatabase,
    private val client: SyncTransport,
    private val bytes: AttachmentBytes,
    private val parallelism: Int = 2,
) {
    init { require(parallelism in 1..4) }

    private val attachments = db.attachmentDao()
    private val sync = db.syncDao()

    private val serverHolds = ConcurrentHashMap.newKeySet<String>()
    private val deferred = ConcurrentHashMap.newKeySet<String>()
    private val candidates = ConcurrentHashMap.newKeySet<String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var filesIndexed = false
    private val scanMutex = Mutex()
    private class DigestLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val digestLocks = HashMap<String, DigestLock>()
    private val transfers = Semaphore(parallelism)
    private val accountLock = Any()
    private val downloadStages = AttachmentDownloadStages()
    private var owner: String? = null
    private var generation = 0L
    private var fileGeneration = 0L
    private val downloadTickets = HashMap<String, Any>()

    /** Explicit catch-up/repair scans once; streamed ink events never enumerate picture files. */
    fun reconcileFiles() { invalidateFileIndex(); downloadStages.reconcile() }

    internal fun invalidateFileIndex() = synchronized(accountLock) {
        fileGeneration++
        filesIndexed = false
    }

    fun noteAttachments(ids: Collection<String>) { candidates += ids }

    private fun accountGeneration(account: SyncAccount): Long = synchronized(accountLock) {
        val next = account.serverUrl + "\u0000" + account.accountId
        if (owner != next) {
            owner = next
            generation++
            serverHolds.clear(); deferred.clear(); missing.clear(); downloadTickets.clear()
            filesIndexed = false
        }
        generation
    }

    internal fun bindAccount(account: SyncAccount): Long = accountGeneration(account)

    internal fun invalidateAccount() = synchronized(accountLock) {
        owner = null
        generation++
        serverHolds.clear(); deferred.clear(); missing.clear(); downloadTickets.clear()
        filesIndexed = false
    }

    private fun checkGeneration(expected: Long) = synchronized(accountLock) {
        if (expected != generation) throw CancellationException("Attachment account changed")
    }

    private fun markServerHeld(expected: Long, digest: String) = synchronized(accountLock) {
        checkGeneration(expected)
        serverHolds += digest
    }

    private suspend fun <T> withDigest(id: String, block: suspend () -> T): T {
        val held = synchronized(digestLocks) {
            digestLocks.getOrPut(id) { DigestLock() }.also { it.users++ }
        }
        try { return held.mutex.withLock { block() } }
        finally { synchronized(digestLocks) {
            held.users--
            if (held.users == 0) digestLocks.remove(id)
        } }
    }

    /** Only two coroutine workers exist regardless of the number of pending digests. */
    private suspend fun <T> bounded(digests: List<String>, operation: suspend (String) -> T): List<T> = coroutineScope {
        val next = AtomicInteger()
        val results = arrayOfNulls<Any>(digests.size)
        List(minOf(parallelism, digests.size)) { async {
            while (true) {
                currentCoroutineContext().ensureActive()
                val index = next.getAndIncrement()
                if (index >= digests.size) break
                results[index] = operation(digests[index])
            }
        } }.awaitAll()
        @Suppress("UNCHECKED_CAST")
        results.map { it as T }
    }

    private class UploadStopped(val digest: String, val result: SyncRunResult) : RuntimeException()
    private class DownloadStopped : RuntimeException()

    suspend fun ensureUploadedTogether(account: SyncAccount, digests: List<String>, force: Boolean = false,
        noRoomFrom: Long = Long.MAX_VALUE): List<Pair<String, BlobPresence>> {
        return uploadForGeneration(account, digests, force, noRoomFrom, accountGeneration(account))
    }

    internal suspend fun uploadForGeneration(account: SyncAccount, digests: List<String>, force: Boolean,
        noRoomFrom: Long, expected: Long): List<Pair<String, BlobPresence>> {
        val roomLimit = AtomicLong(noRoomFrom)
        checkGeneration(expected)
        return try {
            bounded(digests.distinct()) { digest ->
                val result = ensureUploadedForGeneration(account, digest, force, roomLimit.get(), expected)
                if (result is BlobPresence.NoRoom) roomLimit.updateAndGet { minOf(it, result.bytes) }
                if (result is BlobPresence.Stopped) throw UploadStopped(digest, result.result)
                digest to result
            }
        } catch (stopped: UploadStopped) {
            listOf(stopped.digest to BlobPresence.Stopped(stopped.result))
        }
    }

    /**
     * Makes the server hold [digest], or says why it never will.
     *
     * [force] is set by the `missing_blob` repair path, where the server has just said in as many
     * words that it does not have these bytes: every memo is wrong at that moment, and the `HEAD`
     * that would normally save an upload can only repeat what the rejection already said. That is
     * also the path that repairs a server whose blob volume was lost while its database survived —
     * §13.12 designed the 404 for exactly this.
     *
     * [noRoomFrom] is the smallest picture the account has already refused for room. One at least
     * that large cannot fit either, and is answered [BlobPresence.NoRoom] without being sent: the
     * server reads the whole body before it refuses.
     */
    suspend fun ensureUploaded(
        account: SyncAccount,
        digest: String,
        force: Boolean = false,
        noRoomFrom: Long = Long.MAX_VALUE,
    ): BlobPresence {
        val expected = accountGeneration(account)
        return ensureUploadedForGeneration(account, digest, force, noRoomFrom, expected)
    }

    private suspend fun ensureUploadedForGeneration(account: SyncAccount, digest: String, force: Boolean,
        noRoomFrom: Long, expected: Long): BlobPresence {
        return withDigest(digest) { transfers.withPermit {
            checkGeneration(expected)
            val result = ensureUploadedLocked(account, digest, force, noRoomFrom, expected)
            checkGeneration(expected)
            result
        } }
    }

    private suspend fun ensureUploadedLocked(account: SyncAccount, digest: String, force: Boolean,
        noRoomFrom: Long, expected: Long): BlobPresence {
        if (!isBlobDigest(digest)) {
            // Not a content address, so it can never name a blob. Only a local row written by
            // something other than AttachmentStore could produce one.
            Log.e(TAG, "Attachment id \"$digest\" is not a SHA-256 and cannot be uploaded")
            return BlobPresence.Undeliverable
        }
        if (!force && knownPresent(digest)) return BlobPresence.Present(uploaded = false)

        val file = bytes.fileFor(digest)
        if (!file.isFile) {
            // The picture is broken on this device already. Nothing here can put it on the server,
            // and holding the change back would stop every other change with it.
            Log.w(TAG, "No local bytes for attachment $digest; the change naming it cannot carry it")
            return BlobPresence.Undeliverable
        }

        if (!force) {
            when (val present = client.hasBlob(account.serverUrl, account.token, digest)) {
                is ServerResult.Success -> if (present.value) {
                    markServerHeld(expected, digest)
                    return BlobPresence.Present(uploaded = false)
                }
                ServerResult.Unauthorized -> return BlobPresence.Stopped(SyncRunResult.Revoked)
                is ServerResult.Failed -> return BlobPresence.Stopped(present.asSyncResult())
            }
        }

        val size = file.length()
        if (size >= noRoomFrom) return BlobPresence.NoRoom(size)

        return when (val upload = client.uploadBlob(account.serverUrl, account.token, digest, file)) {
            is ServerResult.Success -> {
                markServerHeld(expected, digest)
                // False when the server answered 204: another device had already uploaded the same
                // photograph, and one file is what both of them now reference.
                BlobPresence.Present(uploaded = upload.value)
            }
            ServerResult.Unauthorized -> BlobPresence.Stopped(SyncRunResult.Revoked)
            is ServerResult.Failed -> when {
                // A full account is not a picture that can never be delivered: it goes once there
                // is room. Undeliverable would drop it from every page pushed this run and from the
                // queue for good, so the picture would never reach the server even after space was
                // freed.
                upload.reason == ConnectFailure.StorageFull -> BlobPresence.NoRoom(size)
                upload.retryable -> BlobPresence.Stopped(upload.asSyncResult())
                else -> {
                    // A picture the server will not take however many times it is offered: over the
                    // per-attachment cap, or bytes it refuses to hash to their own name.
                    Log.e(TAG, "Server permanently refused attachment $digest: ${upload.reason}")
                    BlobPresence.Undeliverable
                }
            }
        }
    }

    /**
     * Fetches the bytes for every picture this device knows about and does not have.
     *
     * The `attachments` table is the queue: a row whose file is absent is a pending download, so
     * there is no second table to keep in step with the filesystem and nothing stranded when a
     * process dies mid-transfer.
     *
     * The first transport failure ends the pass rather than trying the rest — they are all going to
     * the same server, so a tablet that has lost its wifi with two hundred pictures outstanding
     * would otherwise spend two hundred connect timeouts on one wakeup. A 404 or a mismatched digest
     * is about that picture and only defers it.
     */
    suspend fun downloadMissing(account: SyncAccount): BlobDownloads {
        return downloadMissingForGeneration(account, accountGeneration(account))
    }

    internal suspend fun downloadMissingForGeneration(account: SyncAccount, expected: Long): BlobDownloads {
        checkGeneration(expected)
        val downloaded = AtomicInteger()
        val pending = missingBytes(expected)
        return try {
            bounded(pending) { id ->
                val copied = withDigest(id) { transfers.withPermit {
                    checkGeneration(expected)
                    if (id !in missing || bytes.fileFor(id).isFile) {
                        missing -= id
                        false
                    } else downloadOne(account, id, expected)
                } }
                if (copied) downloaded.incrementAndGet()
            }
            BlobDownloads(downloaded.get(), workRemains = false)
        } catch (_: DownloadStopped) {
            BlobDownloads(downloaded.get(), workRemains = true)
        }
    }

    private suspend fun downloadOne(account: SyncAccount, id: String, expected: Long): Boolean {
        val ticket = synchronized(accountLock) {
            checkGeneration(expected)
            if (id !in missing) return false
            Any().also { downloadTickets[id] = it }
        }
        val owned = try { downloadStages.create(bytes.stagingFor(id), id) } catch (failure: IOException) {
            synchronized(accountLock) { if (downloadTickets[id] === ticket) downloadTickets.remove(id) }
            Log.w(TAG, "Attachment download staging is unavailable", failure)
            throw DownloadStopped()
        }
        val staged = owned.file
        try {
            val result = client.downloadBlob(account.serverUrl, account.token, id, staged)
            currentCoroutineContext().ensureActive()
            checkGeneration(expected)
            if (synchronized(accountLock) { downloadTickets[id] !== ticket }) return false
            return when (result) {
                is ServerResult.Success -> {
                    if (!result.value) {
                        Log.w(TAG, "Server has no bytes for attachment $id")
                        synchronized(accountLock) {
                            checkGeneration(expected)
                            if (downloadTickets[id] === ticket) { deferred += id; missing -= id }
                        }
                        false
                    } else {
                        val published = synchronized(accountLock) {
                            checkGeneration(expected)
                            if (downloadTickets[id] !== ticket) return false
                            val copied = bytes.publish(staged, id)
                            if (copied) missing -= id else { deferred += id; missing -= id }
                            copied
                        }
                        if (!published) {
                            Log.e(TAG, "Downloaded attachment $id could not be published")
                        }
                        published
                    }
                }
                ServerResult.Unauthorized -> throw DownloadStopped()
                is ServerResult.Failed -> {
                    if (result.retryable) throw DownloadStopped()
                    Log.e(TAG, "Attachment $id could not be downloaded: ${result.reason}")
                    synchronized(accountLock) {
                        checkGeneration(expected)
                        if (downloadTickets[id] === ticket) { deferred += id; missing -= id }
                    }
                    false
                }
            }
        } finally {
            owned.close()
            synchronized(accountLock) { if (downloadTickets[id] === ticket) downloadTickets.remove(id) }
        }
    }

    /** Whether anything at all is waiting to be fetched, without asking the network. */
    suspend fun hasMissingBytes(): Boolean = missingBytes().isNotEmpty()

    /**
     * Deletes the local copies of pictures nothing on this device reaches any more.
     *
     * The caller has already removed their `attachments` rows in the same operation, and that
     * ordering is not cosmetic: [missingBytes] answers "what is pending" by looking for a row whose
     * file is absent, so a file deleted while its row survived would be re-downloaded on the next
     * tick.
     *
     * [serverHolds] is deliberately not cleared: it records what the server has, and this device
     * dropping its copy does not change that.
     */
    fun discard(ids: Collection<String>): Int = synchronized(accountLock) { ids.count { id ->
        if (!isBlobDigest(id)) return@count false
        fileGeneration++
        filesIndexed = false
        downloadTickets.remove(id)
        deferred -= id
        missing -= id
        candidates -= id
        bytes.stagingFor(id).delete()
        bytes.fileFor(id).delete()
    } }

    /**
     * The pictures this device knows about and does not have, minus the ones it has stopped asking
     * for. An id that cannot be a digest is skipped rather than requested: it could not name a blob,
     * and it must never reach a URL or a file name.
     */
    private suspend fun missingBytes(expected: Long = synchronized(accountLock) { generation }): List<String> = scanMutex.withLock {
        while (true) {
            val stamp = synchronized(accountLock) { checkGeneration(expected); fileGeneration }
            val additions = candidates.toList()
            candidates.removeAll(additions.toSet())
            val inspect = if (filesIndexed) additions else (attachments.allIds() + additions).distinct()
            val presence = withContext(Dispatchers.IO) {
                inspect.filter { it !in deferred && isBlobDigest(it) }.associateWith { bytes.fileFor(it).exists() }
            }
            val current = synchronized(accountLock) {
                checkGeneration(expected)
                if (stamp != fileGeneration) null else {
                    for ((id, exists) in presence) {
                        if (exists || id in deferred) missing -= id else missing += id
                    }
                    filesIndexed = true
                    missing.toList().sorted()
                }
            }
            if (current != null) return@withLock current
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    /**
     * Whether the server is already known to hold [digest], without asking it.
     *
     * The durable half is the entity state: the server accepts an `attachment` row only when it has
     * the bytes, and it never frees a blob while a live row names it, so a state row for the digest
     * — pushed here or pulled from another device — is proof that a `HEAD` would answer 204.
     */
    private suspend fun knownPresent(digest: String): Boolean =
        digest in serverHolds || sync.entityState(ATTACHMENT_KIND, digest) != null

    private fun ServerResult.Failed.asSyncResult(): SyncRunResult = when {
        reason == ConnectFailure.MembershipRequired ->
            SyncRunResult.Failed(PermanentSyncFailure.MembershipRequired)
        reason == ConnectFailure.StorageFull -> SyncRunResult.Failed(PermanentSyncFailure.StorageFull)
        retryable -> SyncRunResult.Retryable(reason)
        else -> SyncRunResult.Failed(PermanentSyncFailure.InvalidServerResponse)
    }

    private companion object {
        const val TAG = "AttachmentBlobSync"
        const val ATTACHMENT_KIND = "attachment"
    }
}
