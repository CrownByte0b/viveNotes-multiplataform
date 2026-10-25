package com.vivenotes.desktop.update

import com.vivenotes.localization.Strings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Puts a release's installer in a directory, verified, or throws and leaves nothing there. */
internal fun interface UpdateDownloader {
    suspend fun download(release: Release, directory: File, onBytes: (Long) -> Unit): File
}

/**
 * Reads the release's published SHA-256 first, then streams the installer to a temporary file and
 * gives it its real name only once its size and digest match. Both come from the same release over
 * HTTPS; the installer itself is not code-signed yet.
 */
internal class VerifiedUpdateDownloader(
    private val userAgent: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateDownloader {
    override suspend fun download(release: Release, directory: File, onBytes: (Long) -> Unit): File {
        val installer = release.installer
        val checksum = String(fetch(release.checksum) { input -> input.readNBytes(MAX_CHECKSUM_BYTES) }, Charsets.US_ASCII)
        val expected = parseChecksum(checksum, installer.name)
            ?: throw IOException(Strings.update_checksum_unreadable(installer.name))
        directory.mkdirs()
        // Its own name each time: a cancelled download may still be finishing its last read.
        val partial = File.createTempFile("${installer.name}-", ".part", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            fetch(installer) { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > installer.bytes) throw IOException(Strings.value_is_larger_than_expected(installer.name))
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onBytes(written)
                    }
                    output.fd.sync()
                }
            }
            if (written != installer.bytes) throw IOException(Strings.value_is_incomplete(installer.name))
            if (digest.digest().toHex() != expected) throw IOException(Strings.value_failed_verification(installer.name))
            val target = File(directory, installer.name)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            partial.delete()
        }
    }

    private suspend fun <T> fetch(asset: ReleaseAsset, read: suspend (java.io.InputStream) -> T): T {
        val connection = openConnection(asset.url.toURL()).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub answers a release download with a redirect to its storage host, also HTTPS.
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) throw IOException(Strings.server_returned_http_value(response))
            // Where the redirects ended, not only where they started, must be a trusted address.
            if (!isTrustedDownload(connection.url.toURI())) throw IOException(Strings.value_failed_verification(asset.name))
            return connection.inputStream.use { read(it) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_CHECKSUM_BYTES = 4 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

/**
 * The lowercase SHA-256 a `sha256sum`-style file gives [fileName]: `<64 hex>  <name>`, the form
 * `build.ps1` writes, optionally with `*` before the name. A single bare digest also counts.
 */
internal fun parseChecksum(text: String, fileName: String): String? {
    val entries = text.lineSequence().map(String::trim).filter(String::isNotEmpty).mapNotNull { line ->
        val parts = line.split(Regex("""\s+"""), limit = 2)
        val digest = parts[0].lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: return@mapNotNull null
        digest to parts.getOrNull(1)?.removePrefix("*")?.trim()
    }.toList()
    return entries.firstOrNull { it.second == fileName }?.first
        ?: entries.singleOrNull()?.takeIf { it.second == null }?.first
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
