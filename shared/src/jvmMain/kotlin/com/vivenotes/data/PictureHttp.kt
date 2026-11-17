// Ported from viveNotes Android data/PictureHttp.kt; the identity names the desktop
// build, and the readable types are what the desktop attachment importer decodes.
package com.vivenotes.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The network half of the online picture search: one identity, bounded reads, HTTPS only.
 *
 * HTTPS only because the addresses come from third parties' indexes.
 */
internal class PictureHttp(
    private val userAgent: String = userAgent("development"),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val allowHttp: Boolean = false,
) {
    sealed interface Text {
        data class Ok(val body: String) : Text
        data object RateLimited : Text
        data object Failed : Text
    }

    /** A JSON API answer. */
    suspend fun text(address: String): Text = withContext(io) {
        val connection = connect(address) ?: return@withContext Text.Failed
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    Text.Ok(connection.inputStream.use { it.readBytes().decodeToString() })
                HTTP_TOO_MANY_REQUESTS -> Text.RateLimited
                else -> Text.Failed
            }
        } catch (e: IOException) {
            Text.Failed
        } finally {
            connection.disconnect()
        }
    }

    /**
     * One picture, or null — including for a type the attachment importer cannot read,
     * so a caller's fallback gets its turn instead of the import failing silently afterwards.
     */
    suspend fun image(address: String, maxBytes: Int): ByteArray? = withContext(io) {
        val connection = connect(address) ?: return@withContext null
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type !in SUPPORTED_TYPES) return@withContext null
            val limit = if (type == SvgPictureSource.MIME_TYPE) minOf(maxBytes, SvgPictureSource.MAX_BYTES) else maxBytes
            if (connection.contentLengthLong > limit) return@withContext null
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    // Content-Length is optional, so the cap is enforced on what actually arrives.
                    if (out.size() > limit) return@withContext null
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(address: String): HttpURLConnection? {
        // Android's lenient parse, kept so an address one app fetches the other fetches too.
        @Suppress("DEPRECATION")
        val url = runCatching { URL(address) }.getOrNull() ?: return null
        if (url.protocol != "https" && !(allowHttp && url.protocol == "http")) return null
        return openConnection(url).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Both services ask clients to identify themselves — Wikimedia's policy requires a
            // name and a way to make contact — and a default Java agent is what scrapers send.
            setRequestProperty("User-Agent", userAgent)
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val COPY_BUFFER_BYTES = 16 * 1024

        /**
         * Raster formats read by Skia, plus SVG read by the vector attachment importer. TIFF,
         * HEIC, HEIF and AVIF remain unsupported by the desktop decoder.
         */
        internal val SUPPORTED_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/bmp",
            SvgPictureSource.MIME_TYPE,
        )

        /** The desktop app by name and version, with the repository as the contact. */
        fun userAgent(version: String): String =
            "viveNotes-desktop/$version (+https://github.com/CrownByte0b/viveNotes-multiplataform)"
    }
}
