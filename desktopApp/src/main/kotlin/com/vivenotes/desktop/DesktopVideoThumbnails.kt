package com.vivenotes.desktop

import com.vivenotes.data.VideoThumbnailSource
import com.vivenotes.model.youTubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection

/** The Android app's two thumbnail sizes, cached as derived data outside notebook exports. */
internal class DesktopVideoThumbnails(
    private val directory: File,
    private val fetch: (String) -> ByteArray? = ::download,
) : VideoThumbnailSource {
    private val memory = object : LinkedHashMap<String, ByteArray>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 24
    }
    private val failedAt = mutableMapOf<String, Long>()

    override suspend fun load(videoId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (youTubeVideoId("https://youtu.be/$videoId") != videoId) return@withContext null
        synchronized(memory) { memory[videoId] }?.let { return@withContext it }
        val now = System.currentTimeMillis()
        if (synchronized(memory) { failedAt[videoId]?.let { now - it < RETRY_AFTER_MS } == true })
            return@withContext null
        val file = File(directory, videoId)
        val bytes = if (file.isFile) file.readBytes().takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
            else null
        val result = bytes ?: listOf("maxresdefault.jpg", "mqdefault.jpg")
            .firstNotNullOfOrNull { name -> runCatching { fetch("https://i.ytimg.com/vi/$videoId/$name") }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES } }
            ?.also { fetched ->
                directory.mkdirs()
                val staging = File(directory, "$videoId.part")
                staging.writeBytes(fetched)
                if (!staging.renameTo(file)) staging.delete()
            }
        synchronized(memory) {
            if (result == null) failedAt[videoId] = now
            else {
                failedAt.remove(videoId)
                memory[videoId] = result
            }
        }
        result
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val RETRY_AFTER_MS = 5 * 60 * 1000L

        fun download(address: String): ByteArray? {
            val connection = java.net.URI.create(address).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode !in 200..299 ||
                    connection.contentLengthLong > MAX_BYTES ||
                    !connection.contentType.orEmpty().startsWith("image/")) return null
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > MAX_BYTES) return null
                    }
                    output.toByteArray()
                }
                return bytes.takeIf { it.isNotEmpty() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
