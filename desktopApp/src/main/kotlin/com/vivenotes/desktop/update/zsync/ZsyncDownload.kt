package com.vivenotes.desktop.update.zsync

import com.vivenotes.localization.Strings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** How far a zsync download has come: [fetched] of the [toFetch] bytes it needs, and the [reused] ones it had. */
internal data class ZsyncProgress(val fetched: Long, val toFetch: Long, val reused: Long)

/** A byte range `[start, end)` of the target. */
internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/**
 * Builds [control]'s target in [output] from what [seed] already holds plus HTTP range requests to
 * [url] for the rest, then checks the whole file against the control file's SHA-1 and [sha256].
 * Throws [ZsyncUnavailable] when the server does not serve ranges or the result does not verify, so
 * the caller can download the file whole instead; network failures throw as they are.
 */
internal class ZsyncDownload(
    private val userAgent: String,
    /** Only these addresses may serve parts; a redirect anywhere else is refused. */
    private val trusted: (URI) -> Boolean,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val parallel: Int = PARALLEL_REQUESTS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(
        control: ZsyncControl,
        url: URI,
        seed: File,
        output: File,
        sha256: String,
        onProgress: (ZsyncProgress) -> Unit,
    ) = withContext(io) {
        onProgress(ZsyncProgress(0, 0, 0))
        val context = currentCoroutineContext()
        val sources = scanSeed(control, seed) { context.ensureActive() }
        val reused = reusedBytes(control, sources)
        val ranges = missingRanges(control, sources)
        val toFetch = ranges.sumOf(ByteRange::length)
        onProgress(ZsyncProgress(0, toFetch, reused))
        RandomAccessFile(output, "rw").use { it.setLength(0); it.setLength(control.length) }
        FileChannel.open(output.toPath(), StandardOpenOption.WRITE).use { channel ->
            copyFromSeed(control, sources, seed, channel)
            var fetched = 0L
            val lock = Any()
            fetchRanges(ranges, url, control.length, channel) { count ->
                synchronized(lock) {
                    fetched += count
                    onProgress(ZsyncProgress(fetched, toFetch, reused))
                }
            }
            channel.force(true)
        }
        verify(output, control, sha256)
    }

    private fun copyFromSeed(control: ZsyncControl, sources: LongArray, seed: File, channel: FileChannel) {
        FileChannel.open(seed.toPath(), StandardOpenOption.READ).use { input ->
            val seedLength = input.size()
            var block = 0
            while (block < sources.size) {
                val from = sources[block]
                if (from < 0) { block++; continue }
                // Consecutive blocks from consecutive places in the seed go over in one copy.
                var end = block + 1
                while (end < sources.size && sources[end] == from + (end - block).toLong() * control.blockSize) end++
                val bytes = control.blockStart(end - 1) + control.blockLength(end - 1) - control.blockStart(block)
                // Past the seed's end the scan read zeros, which the new file already holds.
                val available = minOf(bytes, seedLength - from)
                var copied = 0L
                while (copied < available) {
                    val count = input.transferTo(from + copied, available - copied, channel.position(control.blockStart(block) + copied))
                    if (count <= 0) throw IOException("The seed ended early")
                    copied += count
                }
                block = end
            }
        }
    }

    private suspend fun fetchRanges(ranges: List<ByteRange>, url: URI, length: Long, channel: FileChannel,
                                    onBytes: (Long) -> Unit) = coroutineScope {
        if (ranges.isEmpty()) return@coroutineScope
        val fetcher = RangeFetcher(url, length)
        // The first alone, so the others start from the storage address its redirect found.
        fetcher.fetch(ranges.first(), channel, onBytes)
        val next = AtomicInteger(1)
        repeat(minOf(parallel, ranges.size - 1)) {
            launch(io) {
                while (true) {
                    val range = ranges.getOrNull(next.getAndIncrement()) ?: break
                    fetcher.fetch(range, channel, onBytes)
                }
            }
        }
    }

    private suspend fun verify(output: File, control: ZsyncControl, sha256: String) {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha2 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        output.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                sha1.update(buffer, 0, count)
                sha2.update(buffer, 0, count)
                total += count
            }
        }
        if (total != control.length || sha1.digest().toHex() != control.sha1) {
            throw ZsyncUnavailable("The assembled ${control.fileName} does not match its zsync control file")
        }
        if (sha2.digest().toHex() != sha256) {
            throw ZsyncUnavailable("The assembled ${control.fileName} does not match the release's SHA-256")
        }
    }

    /**
     * Range requests to one asset. GitHub answers its release address with a redirect to signed
     * storage; the first request follows it and the rest go straight there until that address stops
     * answering (its signature lasts minutes), when the release address is asked again.
     */
    private inner class RangeFetcher(private val url: URI, private val length: Long) {
        private val name = url.path.substringAfterLast('/')
        @Volatile private var storage: URL? = null

        suspend fun fetch(range: ByteRange, channel: FileChannel, onBytes: (Long) -> Unit) {
            val direct = storage
            if (direct != null && attempt(direct, release = false, range, channel, onBytes)) return
            storage = null
            attempt(url.toURL(), release = true, range, channel, onBytes)
        }

        /** False when the storage address refused the request, which the [release] address may not. */
        private suspend fun attempt(address: URL, release: Boolean, range: ByteRange, channel: FileChannel,
                                    onBytes: (Long) -> Unit): Boolean {
            val connection = openConnection(address).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=${range.start}-${range.end - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", userAgent)
            }
            try {
                val response = connection.responseCode
                // Where the redirects ended, not only where they started, must be a trusted address.
                if (!trusted(connection.url.toURI())) throw IOException(Strings.value_failed_verification(name))
                when (response) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    HttpURLConnection.HTTP_OK -> throw ZsyncUnavailable("The server does not send parts of $name")
                    416 -> throw ZsyncUnavailable("The server has no bytes ${range.start}-${range.end - 1} of $name")
                    in 400..499 -> if (release) throw IOException(Strings.server_returned_http_value(response))
                        else { connection.disconnect(); return false }
                    else -> throw IOException(Strings.server_returned_http_value(response))
                }
                val expected = "bytes ${range.start}-${range.end - 1}/$length"
                val answered = connection.getHeaderField("Content-Range")?.trim()
                if (answered != expected) throw ZsyncUnavailable("Asked $name for $expected, the server sent $answered")
                storage = connection.url
                // Read to the end and closed, not disconnected, so the next request can reuse the connection.
                connection.inputStream.use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var position = range.start
                    while (position < range.end) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), range.end - position).toInt())
                        if (count < 0) throw IOException(Strings.value_is_incomplete(name))
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        var at = position
                        while (bytes.hasRemaining()) at += channel.write(bytes, at)
                        position += count
                        onBytes(count.toLong())
                    }
                }
                return true
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
    }

    companion object {
        /**
         * Missing blocks this close together are fetched in one request, with the known ones between.
         * Measured from a day-old AppImage to the next build (4.4 MB missing): no merging took 240
         * requests, 64 KiB 45 requests and 6.5 MB, 256 KiB 10 requests but 10.9 MB.
         */
        const val MERGE_GAP_BYTES = 64 * 1024L
        const val PARALLEL_REQUESTS = 4
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

/** The byte ranges of [control]'s target that [sources] cannot provide, close ones merged. */
internal fun missingRanges(control: ZsyncControl, sources: LongArray, mergeGap: Long = ZsyncDownload.MERGE_GAP_BYTES): List<ByteRange> {
    val ranges = mutableListOf<ByteRange>()
    var block = 0
    while (block < sources.size) {
        if (sources[block] != BlockSource.MISSING) { block++; continue }
        var end = block + 1
        while (end < sources.size && sources[end] == BlockSource.MISSING) end++
        val range = ByteRange(control.blockStart(block), control.blockStart(end - 1) + control.blockLength(end - 1))
        val last = ranges.lastOrNull()
        if (last != null && range.start - last.end <= mergeGap) ranges[ranges.lastIndex] = ByteRange(last.start, range.end)
        else ranges += range
        block = end
    }
    return ranges
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
