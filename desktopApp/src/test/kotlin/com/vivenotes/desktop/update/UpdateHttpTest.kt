package com.vivenotes.desktop.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed and the download over real HTTP, against a local server answering like GitHub: the API's
 * release object, then each release download as a redirect to a separate storage path.
 */
class UpdateHttpTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val requests = ConcurrentHashMap<String, HttpExchange>()
    private val installer = Random(7).nextBytes(300_000)
    private val installerName = "ViveNotes-0.2.0-x64.msi"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theFeedAsksGitHubAsItRequiresAndReadsTheRelease() {
        serveRelease()

        val release = assertNotNull(feed().latest())

        assertEquals("0.2.0", release.version.toString())
        assertEquals(URI("$base/releases/download/v0.2.0/$installerName"), release.installer.url)
        val request = requests.getValue("/repos/latest").requestHeaders
        assertEquals("ViveNotes/0.1.0 (Windows)", request.getFirst("User-Agent"))
        assertEquals("application/vnd.github+json", request.getFirst("Accept"))
        assertEquals("2022-11-28", request.getFirst("X-GitHub-Api-Version"))
    }

    @Test
    fun aRepositoryWithoutReleasesHasNothingToOfferAndOtherAnswersAreFailures() {
        respond("/repos/latest", 404, """{"message":"Not Found"}""".toByteArray())
        assertNull(feed().latest())

        respond("/repos/latest", 403, """{"message":"API rate limit exceeded"}""".toByteArray())
        val failure = assertFailsWith<IOException> { feed().latest() }
        assertEquals("Server returned HTTP 403", failure.message)
    }

    @Test
    fun theInstallerFollowsTheRedirectAndIsKeptOnlyOnceVerified() = runBlocking {
        val release = assertNotNull(serveRelease().let { feed().latest() })
        val directory = temp.newFolder("updates")
        val progress = mutableListOf<Long>()

        val msi = VerifiedUpdateDownloader("ViveNotes/0.1.0 (Windows)").download(release, directory) { progress += it }

        assertEquals(java.io.File(directory, installerName), msi)
        assertContentEquals(installer, msi.readBytes())
        assertEquals(listOf(installerName), directory.list()!!.toList())
        assertEquals(installer.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(requests.containsKey("/storage/$installerName"), "GitHub's redirect to its storage was followed")
        assertEquals("ViveNotes/0.1.0 (Windows)", requests.getValue("/storage/$installerName").requestHeaders.getFirst("User-Agent"))
    }

    @Test
    fun aTamperedInstallerIsDeletedAndReported() = runBlocking {
        val release = assertNotNull(serveRelease(served = installer.copyOf().also { it[1234] = (it[1234] + 1).toByte() })
            .let { feed().latest() })
        val directory = temp.newFolder("updates")

        val failure = assertFailsWith<IOException> {
            VerifiedUpdateDownloader("test").download(release, directory) {}
        }

        assertEquals("$installerName failed verification", failure.message)
        assertEquals(emptyList(), directory.list()!!.toList())
    }

    @Test
    fun anInstallerLongerOrShorterThanTheReleaseListsIsRefused() = runBlocking {
        val directory = temp.newFolder("updates")
        val longer = assertNotNull(serveRelease(served = installer + byteArrayOf(1)).let { feed().latest() })
        assertEquals("$installerName is larger than expected",
            assertFailsWith<IOException> { VerifiedUpdateDownloader("test").download(longer, directory) {} }.message)

        val shorter = assertNotNull(serveRelease(served = installer.copyOf(installer.size - 1)).let { feed().latest() })
        assertEquals("$installerName is incomplete",
            assertFailsWith<IOException> { VerifiedUpdateDownloader("test").download(shorter, directory) {} }.message)
        assertEquals(emptyList(), directory.list()!!.toList())
    }

    @Test
    fun aChecksumForAnotherFileIsNotTrusted() = runBlocking {
        val release = assertNotNull(serveRelease(checksum = "${sha256(installer)}  ViveNotes-0.1.0-x64.msi\r\n").let { feed().latest() })
        val directory = temp.newFolder("updates")

        val failure = assertFailsWith<IOException> { VerifiedUpdateDownloader("test").download(release, directory) {} }

        assertEquals("The checksum for $installerName could not be read.", failure.message)
        assertTrue(!requests.containsKey("/storage/$installerName"), "Nothing is downloaded without a checksum")
    }

    @Test
    fun aRedirectThatEndsSomewhereUntrustedIsRefused() = runBlocking {
        val release = Release(ReleaseVersion.parse("0.2.0")!!, "",
            ReleaseAsset(installerName, URI("https://github.com/x/$installerName"), 3),
            ReleaseAsset("$installerName.sha256", URI("https://github.com/x/$installerName.sha256"), 92))
        // What HttpURLConnection reports after following a redirect to plain HTTP elsewhere.
        val downloader = VerifiedUpdateDownloader("test") { _ -> FakeConnection(URL("http://example.com/checksum"), "x") }

        val failure = assertFailsWith<IOException> { downloader.download(release, temp.newFolder("updates")) {} }

        assertEquals("$installerName.sha256 failed verification", failure.message)
    }

    @Test
    fun checksumFilesInTheFormsToolsWriteAreRead() {
        val digest = "c7634c943aecf4822419592af2c258113c4412c5d80ba8f5fd58b33dc657f114"
        assertEquals(digest, parseChecksum("$digest  ViveNotes-0.2.0-x64.msi\r\n", "ViveNotes-0.2.0-x64.msi"))
        assertEquals(digest, parseChecksum("${digest.uppercase()} *ViveNotes-0.2.0-x64.msi", "ViveNotes-0.2.0-x64.msi"))
        assertEquals(digest, parseChecksum("$digest\n", "ViveNotes-0.2.0-x64.msi"))
        assertEquals(digest, parseChecksum("${"0".repeat(64)}  other.msi\n$digest  ViveNotes-0.2.0-x64.msi", "ViveNotes-0.2.0-x64.msi"))
        assertNull(parseChecksum("${digest.take(63)}  ViveNotes-0.2.0-x64.msi", "ViveNotes-0.2.0-x64.msi"))
        assertNull(parseChecksum("<html>Not found</html>", "ViveNotes-0.2.0-x64.msi"))
        assertNull(parseChecksum("", "ViveNotes-0.2.0-x64.msi"))
    }

    private fun feed() = GitHubReleaseFeed(URI("$base/repos/latest"), "ViveNotes/0.1.0 (Windows)")

    /** The fixture release with its asset addresses on this server, as GitHub would list them. */
    private fun serveRelease(
        served: ByteArray = installer,
        checksum: String = "${sha256(installer)}  $installerName\r\n",
    ) {
        val fixture = checkNotNull(javaClass.getResource("/update/github-release-latest.json")).readText()
            .replace("https://github.com/CrownByte0b/viveNotes-multiplataform/releases/download", "$base/releases/download")
            .replace("\"size\": 229876426", "\"size\": ${installer.size}")
            .replace("\"size\": 92", "\"size\": ${checksum.length}")
        respond("/repos/latest", 200, fixture.toByteArray())
        for ((name, bytes) in listOf(installerName to served, "$installerName.sha256" to checksum.toByteArray())) {
            server.removeContextQuietly("/releases/download/v0.2.0/$name")
            server.createContext("/releases/download/v0.2.0/$name") { exchange ->
                requests[exchange.requestURI.path] = exchange
                exchange.responseHeaders.add("Location", "$base/storage/$name")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            respond("/storage/$name", 200, bytes)
        }
    }

    private fun respond(path: String, status: Int, body: ByteArray) {
        server.removeContextQuietly(path)
        server.createContext(path) { exchange ->
            requests[exchange.requestURI.path] = exchange
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun HttpServer.removeContextQuietly(path: String) = runCatching { removeContext(path) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeConnection(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
