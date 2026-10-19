package com.vivenotes.ai

import com.vivenotes.localization.Strings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Puts one pinned [ModelArtifact] at a destination, or throws and leaves nothing there. */
internal fun interface ArtifactDownloader {
    suspend fun download(artifact: ModelArtifact, destination: File, onBytes: (Long) -> Unit)
}

/**
 * Streams one pinned artifact to disk and exposes it only after size and SHA-256 verification.
 * Android's `ai/VerifiedArtifactDownloader.kt`.
 */
internal class VerifiedArtifactDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : ArtifactDownloader {
    override suspend fun download(
        artifact: ModelArtifact,
        destination: File,
        onBytes: (Long) -> Unit,
    ) {
        val connection = openConnection(URI(artifact.url).toURL()).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val response = connection.responseCode
            require(response in 200..299) { Strings.server_returned_http_value(response) }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        require(written <= artifact.bytes) {
                            Strings.value_is_larger_than_expected(artifact.fileName)
                        }
                        onBytes(written)
                    }
                    output.fd.sync()
                }
            }
            require(written == artifact.bytes) { Strings.value_is_incomplete(artifact.fileName) }
            require(digest.digest().hex() == artifact.sha256) {
                Strings.value_failed_verification(artifact.fileName)
            }
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

internal fun ByteArray.hex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
