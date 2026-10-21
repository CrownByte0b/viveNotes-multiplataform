package com.vivenotes.data.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentConcurrencyTest {
    private class ByteServer : AutoCloseable {
        val contents = ConcurrentHashMap<String, ByteArray>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val gate = CountDownLatch(1)
        @Volatile var entered: CountDownLatch? = null
        @Volatile var block = false
        @Volatile var delayMillis = 0L
        @Volatile var badDigest: String? = null
        @Volatile var absent: String? = null
        @Volatile var fail = false
        private val executor = Executors.newFixedThreadPool(8)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@ByteServer.executor
            createContext("/v1/blobs/") { exchange ->
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                var counted = true
                try {
                    val digest = exchange.requestURI.path.substringAfterLast('/')
                    calls.computeIfAbsent("${exchange.requestMethod} $digest") { AtomicInteger() }.incrementAndGet()
                    entered?.countDown()
                    if (block) gate.await(10, TimeUnit.SECONDS)
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    // Count gated/delayed requests; response close can overlap the next HTTP call.
                    active.decrementAndGet()
                    counted = false
                    when {
                        fail -> send(exchange, 503, "offline".toByteArray())
                        exchange.requestMethod == "HEAD" -> exchange.sendResponseHeaders(if (contents.containsKey(digest)) 204 else 404, -1)
                        exchange.requestMethod == "PUT" -> {
                            val bytes = exchange.requestBody.readBytes()
                            if (sha(bytes) != digest) send(exchange, 400, byteArrayOf()) else {
                                contents[digest] = bytes
                                exchange.sendResponseHeaders(201, -1)
                            }
                        }
                        digest == absent || !contents.containsKey(digest) -> exchange.sendResponseHeaders(404, -1)
                        else -> send(exchange, 200, if (digest == badDigest) "wrong bytes".toByteArray() else contents.getValue(digest))
                    }
                } finally { if (counted) active.decrementAndGet(); exchange.close() }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun account(id: String = "account") = SyncAccount(url, id, "device", "vive_test", "Test")
        override fun close() { gate.countDown(); server.stop(0); executor.shutdownNow() }
        private fun send(exchange: HttpExchange, status: Int, bytes: ByteArray) {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun distinctUploadsOverlapWithinTwoPermitsAndDuplicateDigestsUploadOnce() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bounded-uploads").toFile()
        val db = NotesDatabase.inMemory()
        try { ByteServer().use { server ->
            val bytes = TemporaryAttachmentBytes(root)
            val digests = (0 until 8).map { index -> "upload-$index".toByteArray().let { content ->
                sha(content).also { bytes.write(it, content) }
            } }
            val sync = AttachmentBlobSync(db, SyncServerClient(), bytes)
            server.entered = CountDownLatch(2); server.block = true
            val running = async { sync.ensureUploadedTogether(server.account(), digests + digests) }
            withContext(Dispatchers.IO) { assertTrue(server.entered!!.await(10, TimeUnit.SECONDS)) }
            assertEquals(2, server.active.get())
            assertEquals(2, server.peak.get())
            server.gate.countDown()
            val results = running.await()
            assertEquals(8, results.size)
            assertTrue(results.all { it.second == BlobPresence.Present(uploaded = true) })
            digests.forEach { digest ->
                assertEquals(1, server.calls.getValue("HEAD $digest").get())
                assertEquals(1, server.calls.getValue("PUT $digest").get())
            }
            assertTrue(server.peak.get() <= 2)
            val same = digests.first()
            coroutineScope { List(8) { async { sync.ensureUploaded(server.account(), same) } }.awaitAll() }
            assertEquals(1, server.calls.getValue("PUT $same").get())
        } } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun downloadsBoundRequestsVerifyDigestsDefer404AndCleanAllStages() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bounded-downloads").toFile()
        val db = NotesDatabase.inMemory()
        try { ByteServer().use { server ->
            val store = AttachmentStore(root, db)
            val digests = seedPictures(db, server, 8)
            server.absent = digests[0]; server.badDigest = digests[1]
            server.entered = CountDownLatch(2); server.block = true
            val sync = AttachmentBlobSync(db, SyncServerClient(), store.syncBytes)
            val running = async { sync.downloadMissing(server.account()) }
            withContext(Dispatchers.IO) { assertTrue(server.entered!!.await(10, TimeUnit.SECONDS)) }
            assertEquals(2, server.peak.get())
            assertEquals(2, root.walkTopDown().count { it.extension == "part" },
                "Both leased stages must exist while responses are paused")
            server.gate.countDown()
            val result = running.await()
            assertEquals(6, result.downloaded)
            assertFalse(result.workRemains)
            assertFalse(sync.hasMissingBytes())
            assertFalse(store.fileFor(digests[0])!!.exists())
            assertFalse(store.fileFor(digests[1])!!.exists())
            digests.drop(2).forEach { assertTrue(store.fileFor(it)!!.isFile) }
            assertEquals(6L, store.arrivals.value)
            assertTrue(root.walkTopDown().none { it.extension == "part" })
            assertTrue(server.peak.get() <= 2)
        } } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun cancellationDisconnectsBlockedHttpAndKeepsFilesPendingForTheNextAccount() = runBlocking<Unit> {
        val root = Files.createTempDirectory("cancelled-downloads").toFile()
        val db = NotesDatabase.inMemory()
        try { ByteServer().use { server ->
            val store = AttachmentStore(root, db)
            val digests = seedPictures(db, server, 4)
            server.entered = CountDownLatch(2); server.block = true
            val sync = AttachmentBlobSync(db, SyncServerClient(), store.syncBytes)
            val running = async { sync.downloadMissing(server.account("first")) }
            withContext(Dispatchers.IO) { assertTrue(server.entered!!.await(10, TimeUnit.SECONDS)) }
            withTimeout(5_000) { running.cancelAndJoin() }
            assertTrue(running.isCancelled)
            assertEquals(0L, store.arrivals.value)
            assertTrue(digests.none { store.fileFor(it)!!.exists() })
            assertTrue(root.walkTopDown().none { it.extension == "part" })
            server.block = false; server.gate.countDown()
            assertEquals(4, sync.downloadMissing(server.account("second")).downloaded)
        } } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun firstTransportFailureStopsThePassAndRetryKeepsEveryUnfinishedDigest() = runBlocking<Unit> {
        val root = Files.createTempDirectory("failed-downloads").toFile()
        val db = NotesDatabase.inMemory()
        try { ByteServer().use { server ->
            val store = AttachmentStore(root, db)
            seedPictures(db, server, 8)
            val sync = AttachmentBlobSync(db, SyncServerClient(), store.syncBytes)
            server.fail = true
            val failed = sync.downloadMissing(server.account())
            assertTrue(failed.workRemains)
            assertEquals(0, failed.downloaded)
            assertTrue(server.calls.values.sumOf { it.get() } <= 2, server.calls.toString())
            assertTrue(root.walkTopDown().none { it.extension == "part" })
            server.fail = false
            assertEquals(8, sync.downloadMissing(server.account()).downloaded)
        } } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    @Test
    fun loopbackTransferEvidenceComparesOneAndTwoWorkersWithoutTimingAssertions() = runBlocking<Unit> {
        val root = Files.createTempDirectory("blob-throughput").toFile()
        val db = NotesDatabase.inMemory()
        try { ByteServer().use { server ->
            seedPictures(db, server, 12)
            server.delayMillis = 20
            for (workers in listOf(1, 2)) {
                val store = AttachmentStore(File(root, "workers-$workers"), db)
                val sync = AttachmentBlobSync(db, SyncServerClient(), store.syncBytes, parallelism = workers)
                server.peak.set(0)
                val began = System.nanoTime()
                val result = sync.downloadMissing(server.account())
                val elapsed = System.nanoTime() - began
                assertEquals(12, result.downloaded)
                assertTrue(server.peak.get() <= workers)
                println("Blob loopback evidence workers=$workers pictures=12 bytesPerPicture=1024 " +
                    "serverDelayMs=20 peakRequests=${server.peak.get()} elapsedNanos=$elapsed")
            }
        } } finally { db.close(); assertTrue(root.deleteRecursively()) }
    }

    private suspend fun seedPictures(db: NotesDatabase, server: ByteServer, count: Int): List<String> =
        (0 until count).map { index ->
            val bytes = ByteArray(1_024) { (it * 31 + index).toByte() }
            val digest = sha(bytes)
            server.contents[digest] = bytes
            db.attachmentDao().insert(AttachmentEntity(digest, "image/webp", 1, 1, bytes.size.toLong(), 0, 1L))
            digest
        }

    private companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
