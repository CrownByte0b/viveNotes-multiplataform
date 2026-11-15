package com.vivenotes.desktop.update

import com.vivenotes.desktop.update.zsync.ZsyncControl
import com.vivenotes.desktop.update.zsync.ZsyncDownload
import com.vivenotes.desktop.update.zsync.ZsyncUnavailable
import com.vivenotes.diagnostics.DebugLog
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

/**
 * How far a download has come: [bytes] of the [total] it needs, plus [reused] bytes taken from a local
 * copy. A [total] of zero while nothing is reused means the local copy is still being compared.
 */
internal data class DownloadProgress(val bytes: Long, val total: Long, val reused: Long = 0)

/** Puts a release's installer at [target], verified, or throws and leaves nothing there. */
internal fun interface UpdateDownloader {
    /** [seed] is a local file the new installer likely shares most of its bytes with, if there is one. */
    suspend fun download(release: Release, target: File, seed: File?, onProgress: (DownloadProgress) -> Unit): File
}

/**
 * Reads the release's published SHA-256 first, then builds the installer in a temporary file beside
 * [target] and gives it its real name only once its size and digest match. With a [seed] and the
 * release's zsync control file, only the blocks the seed lacks are downloaded; otherwise, or when
 * that cannot work, the whole installer is. Everything comes from the same release over HTTPS; the
 * installers themselves are not code-signed yet.
 */
internal class VerifiedUpdateDownloader(
    private val userAgent: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val log: DebugLog = DebugLog(),
) : UpdateDownloader {
    override suspend fun download(release: Release, target: File, seed: File?, onProgress: (DownloadProgress) -> Unit): File {
        val installer = release.installer
        val checksum = String(fetch(release.checksum) { input -> input.readNBytes(MAX_CHECKSUM_BYTES) }, Charsets.US_ASCII)
        val expected = parseChecksum(checksum, installer.name)
            ?: throw IOException(Strings.update_checksum_unreadable(installer.name))
        val directory = target.absoluteFile.parentFile
        directory.mkdirs()
        // Its own name each time: a cancelled download may still be finishing its last read.
        val partial = File.createTempFile("${target.name}-", ".part", directory)
        try {
            val zsync = release.zsync
            val delta = zsync != null && seed != null && seed.isFile && try {
                downloadChanges(release, zsync, seed, partial, expected, onProgress)
                true
            } catch (unavailable: ZsyncUnavailable) {
                log.failure("update", "download only the changes to ${installer.name}", unavailable)
                false
            }
            if (!delta) downloadWhole(installer, partial, expected, onProgress)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadChanges(release: Release, zsync: ReleaseAsset, seed: File, output: File, sha256: String,
                                        onProgress: (DownloadProgress) -> Unit) {
        val bytes = try {
            fetch(zsync) { input -> readLimited(input, ZsyncControl.MAX_BYTES, zsync.name) }
        } catch (error: IOException) {
            // The checksum came through a moment ago, so this is about the control file, not the connection.
            throw ZsyncUnavailable("${zsync.name} could not be read", error)
        }
        val control = ZsyncControl.parse(bytes)
        if (control.fileName != release.installer.name || control.length != release.installer.bytes) {
            throw ZsyncUnavailable("${zsync.name} describes ${control.fileName}, ${control.length} bytes")
        }
        val started = System.nanoTime()
        ZsyncDownload(userAgent, ::isTrustedDownload, openConnection).download(control, release.installer.url, seed,
            output, sha256) { onProgress(DownloadProgress(it.fetched, it.toFetch, it.reused)) }
        log.event("update") {
            "${release.installer.name} built from ${seed.name} in ${(System.nanoTime() - started) / 1_000_000} ms"
        }
    }

    private suspend fun downloadWhole(installer: ReleaseAsset, partial: File, expected: String,
                                      onProgress: (DownloadProgress) -> Unit) {
        onProgress(DownloadProgress(0, installer.bytes))
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
                    onProgress(DownloadProgress(written, installer.bytes))
                }
                output.fd.sync()
            }
        }
        if (written != installer.bytes) throw IOException(Strings.value_is_incomplete(installer.name))
        if (digest.digest().toHex() != expected) throw IOException(Strings.value_failed_verification(installer.name))
    }

    private fun readLimited(input: java.io.InputStream, limit: Int, name: String): ByteArray {
        val bytes = input.readNBytes(limit + 1)
        if (bytes.size > limit) throw IOException(Strings.value_is_larger_than_expected(name))
        return bytes
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
