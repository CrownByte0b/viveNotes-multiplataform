package com.vivenotes.data

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The network half of Search online, against canned answers and pictures in each format it meets. */
class PictureHttpTest {

    @Test
    fun aJsonAnswerIsReadAQuotaIsReportedAndAnythingElseFails() = runBlocking {
        val body = """{"results":[]}"""
        assertEquals(PictureHttp.Text.Ok(body), http(Canned(type = "application/json", body = body.encodeToByteArray())).text(ADDRESS))
        assertEquals(PictureHttp.Text.RateLimited, http(Canned(code = 429)).text(ADDRESS))
        assertEquals(PictureHttp.Text.Failed, http(Canned(code = 503)).text(ADDRESS))
        assertEquals(PictureHttp.Text.Failed, http(Canned(failure = IOException("offline"))).text(ADDRESS))
    }

    @Test
    fun anAddressThatIsNotHttpsIsNeverOpened() = runBlocking {
        val opened = mutableListOf<URL>()
        val http = PictureHttp(openConnection = { url -> opened += url; Canned(url) })
        assertEquals(PictureHttp.Text.Failed, http.text("http://api.openverse.org/v1/images/"))
        assertNull(http.image("ftp://example.org/a.png", MAX))
        assertNull(http.image("not an address", MAX))
        assertTrue(opened.isEmpty(), "opened $opened")
    }

    @Test
    fun everyRequestNamesTheAppAndBoundsItsWaits() = runBlocking {
        val connection = Canned(body = png())
        PictureHttp(userAgent = PictureHttp.userAgent("9.8.7"), openConnection = { connection }).image(ADDRESS, MAX)
        assertEquals(
            "viveNotes-desktop/9.8.7 (+https://github.com/CrownByte0b/viveNotes-multiplataform)",
            connection.getRequestProperty("User-Agent"),
        )
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(20_000, connection.readTimeout)
        assertTrue(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected, "the connection is let go after the answer")
    }

    @Test
    fun aPictureOfATypeSkiaReadsArrivesWhole() = runBlocking {
        val picture = png()
        assertContentEquals(picture, http(Canned(body = picture)).image(ADDRESS, MAX))
        // A parameter after the type is still that type.
        assertContentEquals(picture, http(Canned(type = "image/PNG; charset=binary", body = picture)).image(ADDRESS, MAX))
    }

    @Test
    fun typesSkiaCannotReadAreRefusedSoTheCallersFallbackRuns() = runBlocking {
        listOf("image/svg+xml", "image/tiff", "image/avif", "image/heic", "image/heif", "text/html", null).forEach { type ->
            assertNull(http(Canned(type = type, body = png())).image(ADDRESS, MAX), "accepted $type")
        }
    }

    @Test
    fun anAnnouncedSizeOverTheCapIsRefused() = runBlocking {
        assertNull(http(Canned(body = png(), declaredLength = MAX + 1L)).image(ADDRESS, MAX))
    }

    @Test
    fun aSizeOverTheCapIsRefusedWhileReadingWhenNoneWasAnnounced() = runBlocking {
        val large = ByteArray(64 * 1024) { it.toByte() }
        assertNull(http(Canned(body = large, declaredLength = -1)).image(ADDRESS, 32 * 1024))
        assertContentEquals(large, http(Canned(body = large, declaredLength = -1)).image(ADDRESS, 64 * 1024))
    }

    @Test
    fun aFailedOrEmptyAnswerIsNoPicture() = runBlocking {
        assertNull(http(Canned(code = 404, body = png())).image(ADDRESS, MAX))
        assertNull(http(Canned(failure = IOException("reset"))).image(ADDRESS, MAX))
        assertNull(http(Canned(body = ByteArray(0))).image(ADDRESS, MAX))
    }

    /**
     * The accepted types are what Skia decodes here, checked against real files: a type accepted
     * but undecodable would vanish at import with no fallback tried, and one refused needlessly
     * would show a thumbnail where the original was there to be had.
     */
    @Test
    fun theAcceptedTypesAreExactlyTheOnesSkiaDecodes() {
        val samples = mapOf(
            "image/png" to png(),
            "image/jpeg" to encoded(EncodedImageFormat.JPEG),
            "image/webp" to encoded(EncodedImageFormat.WEBP),
            "image/gif" to fixture("red.gif"),
            "image/bmp" to fixture("red.bmp"),
        )
        assertEquals(PictureHttp.DECODABLE_TYPES, samples.keys)
        samples.forEach { (type, bytes) -> assertTrue(decodes(bytes), "Skia should decode $type") }
        listOf("red.avif", "red.tiff").forEach { name ->
            assertFalse(decodes(fixture(name)), "Skia decodes $name now; accept its type")
        }
    }

    private fun http(connection: Canned) = PictureHttp(openConnection = { connection })

    private fun decodes(bytes: ByteArray): Boolean =
        runCatching { Image.makeFromEncoded(bytes).use { it.width > 0 } }.getOrDefault(false)

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/pictures/$name")) { name }.use { it.readBytes() }

    private companion object {
        const val ADDRESS = "https://upload.wikimedia.org/a.png"
        const val MAX = 1024 * 1024

        fun png(): ByteArray = encoded(EncodedImageFormat.PNG)

        fun encoded(format: EncodedImageFormat): ByteArray = Surface.makeRasterN32Premul(4, 3).use { surface ->
            surface.canvas.clear(Color.RED)
            surface.makeImageSnapshot().use { it.encodeToData(format)!!.bytes }
        }
    }
}

/** One canned HTTP answer, standing in for the network. */
internal class Canned(
    url: URL = URI("https://example.org/").toURL(),
    private val code: Int = 200,
    private val type: String? = "image/png",
    private val body: ByteArray = ByteArray(0),
    private val declaredLength: Long = body.size.toLong(),
    private val failure: IOException? = null,
) : HttpURLConnection(url) {
    var disconnected = false

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int {
        failure?.let { throw it }
        return code
    }

    override fun getContentType(): String? = type

    override fun getContentLengthLong(): Long = declaredLength

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
}
