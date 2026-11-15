package com.vivenotes.desktop.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vivenotes.desktop.update.zsync.ZsyncFixtures
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An update that downloads only what changed, over real HTTP against a local server answering like
 * GitHub: each release download redirects to signed storage, which serves byte ranges with 206.
 */
class ZsyncHttpTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newFixedThreadPool(4)
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val name = INSTALLER_NAME
    private val installer = ZsyncFixtures.xorshift(11, 3_000_000)
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private val redirects = AtomicInteger()
    private val log = Collections.synchronizedList(mutableListOf<String>())

    /** What storage sends for a range request; the whole-file download always gets [installer]. */
    private var partsFrom = installer
    private var honoursRanges = true
    /** How many requests a signed storage address answers before it expires, as GitHub's do after minutes. */
    private var signatureUses = Int.MAX_VALUE

    @AfterTest
    fun stop() = server.stop(0)

    /** The old version: three changed bytes and a few inserted ones, a megabyte or so apart. */
    private fun seed(): File = File(temp.root, "old.msi").apply {
        val bytes = installer.copyOf()
        for (at in listOf(500_000, 1_500_000)) bytes[at] = (bytes[at] + 1).toByte()
        writeBytes(bytes.copyOfRange(0, 2_500_001) + byteArrayOf(9, 9, 9) + bytes.copyOfRange(2_500_001, bytes.size))
    }

    @Test
    fun onlyTheChangedBlocksAreDownloadedAndTheResultIsVerified() = runBlocking {
        val release = serve()
        val progress = mutableListOf<DownloadProgress>()

        val file = downloader().download(release, File(temp.newFolder("updates"), name), seed()) { progress += it }

        assertContentEquals(installer, file.readBytes())
        assertEquals(listOf(name), file.parentFile.list()!!.toList())
        assertEquals(DownloadProgress(0, 0), progress.first(), "comparing first")
        assertEquals(3 * 2048L, progress.last().total, "the two changed blocks and the one with the inserted bytes")
        assertEquals(progress.last().total, progress.last().bytes)
        assertEquals(installer.size - 3 * 2048L, progress.last().reused)
        assertEquals(3, ranges.size, "one request for each changed place: $ranges")
        assertTrue(ranges.none { it == null }, "The whole installer was never downloaded: $ranges")
        assertEquals(1, redirects.get(), "The storage address is reused while it answers")
        assertTrue(log.any { it.contains("ViveNotes-x64.msi built from old.msi") }, log.toString())
    }

    @Test
    fun withoutASeedOrAControlFileTheWholeInstallerIsDownloaded() = runBlocking {
        val release = serve()
        for ((label, seed, zsync) in listOf(Triple("no seed", null, release.zsync), Triple("no control file", seed(), null))) {
            ranges.clear()
            val file = downloader().download(release.copy(zsync = zsync), File(temp.newFolder(), name), seed) {}
            assertContentEquals(installer, file.readBytes(), label)
            assertEquals(listOf<String?>(null), ranges.toList(), label)
        }
    }

    @Test
    fun aServerThatIgnoresRangesGetsTheWholeDownloadInstead() = runBlocking {
        honoursRanges = false
        val release = serve()

        val file = downloader().download(release, File(temp.newFolder("updates"), name), seed()) {}

        assertContentEquals(installer, file.readBytes())
        assertTrue(log.any { it.contains("download only the changes to $name failed") && it.contains("does not send parts") },
            log.toString())
        assertEquals(listOf(name), file.parentFile.list()!!.toList())
    }

    @Test
    fun partsThatDoNotVerifyAreThrownAwayForTheWholeDownload() = runBlocking {
        partsFrom = installer.copyOf().also { it[500_001] = (it[500_001] + 1).toByte() }
        val release = serve()

        val file = downloader().download(release, File(temp.newFolder("updates"), name), seed()) {}

        assertContentEquals(installer, file.readBytes())
        assertTrue(log.any { it.contains("does not match its zsync control file") }, log.toString())
        assertTrue(ranges.contains(null), "The fallback downloaded the whole installer")
    }

    @Test
    fun aControlFileForAnotherFileIsNotUsed() = runBlocking {
        val release = serve(control = ZsyncFixtures.zsyncmake(installer, 2048, 1, 4, 9, fileName = "ViveNotes-1.0.0-x64.msi"))

        val file = downloader().download(release, File(temp.newFolder("updates"), name), seed()) {}

        assertContentEquals(installer, file.readBytes())
        assertEquals(listOf<String?>(null), ranges.toList())
    }

    @Test
    fun anExpiredStorageAddressIsAskedForAgainThroughTheRelease() = runBlocking {
        signatureUses = 1
        val release = serve()

        val file = downloader().download(release, File(temp.newFolder("updates"), name), seed()) {}

        assertContentEquals(installer, file.readBytes())
        assertEquals(3, ranges.size, ranges.toString())
        assertTrue(redirects.get() >= 3, "each part had to go through the release address again: ${redirects.get()}")
    }

    private fun downloader() = VerifiedUpdateDownloader("ViveNotes/0.1.0 (Windows)", log = DebugLog(enabled = true) { log += it })

    /** The release's three files on this server, as GitHub serves release assets. */
    private fun serve(control: ByteArray = ZsyncFixtures.zsyncmake(installer, 2048, 1, 4, 9, fileName = name)): Release {
        val checksum = "${sha256(installer)}  $name\r\n".toByteArray()
        val signatures = AtomicInteger()
        val uses = mutableMapOf<Int, Int>()
        for ((file, bytes) in listOf(name to installer, "$name.sha256" to checksum, "$name.zsync" to control)) {
            server.createContext("/releases/download/v0.2.0/$file") { exchange ->
                if (file == name) redirects.incrementAndGet()
                exchange.responseHeaders.add("Location", "$base/storage/$file?sig=${signatures.incrementAndGet()}")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/storage/$file") { exchange ->
                val signature = exchange.requestURI.query.removePrefix("sig=").toInt()
                val expired = synchronized(uses) {
                    val count = (uses[signature] ?: 0) + 1
                    uses[signature] = count
                    count > signatureUses
                }
                if (expired) {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                    return@createContext
                }
                val range = exchange.requestHeaders.getFirst("Range")
                if (file == name) ranges += range
                respond(exchange, bytes, range?.takeIf { honoursRanges })
            }
        }
        fun asset(file: String, size: Int) = ReleaseAsset(file, URI("$base/releases/download/v0.2.0/$file"), size.toLong())
        return Release(ReleaseVersion.parse("0.2.0")!!, "", asset(name, installer.size), asset("$name.sha256", checksum.size),
            asset("$name.zsync", control.size))
    }

    private fun respond(exchange: HttpExchange, whole: ByteArray, range: String?) {
        if (range == null) {
            exchange.sendResponseHeaders(200, whole.size.toLong())
            exchange.responseBody.use { it.write(whole) }
            return
        }
        val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${whole.size}")
        exchange.sendResponseHeaders(206, (end - start + 1).toLong())
        exchange.responseBody.use { it.write(partsFrom, start, end - start + 1) }
    }

    private fun sha256(bytes: ByteArray) = ZsyncFixtures.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
