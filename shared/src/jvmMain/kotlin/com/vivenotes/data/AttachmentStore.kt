package com.vivenotes.data

import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Where imported pictures live: Android's `data/AttachmentStore.kt`, on desktop.
 *
 * Bytes go to `<data directory>/attachments/<sha256>`, and what is known about them to the
 * `attachments` table. Every import is decoded, shrunk to [MAX_DIMENSION] on its longer side, and
 * re-encoded as lossy WebP at [QUALITY] — Android's memory budget written as a length, applied once
 * at the door, and its format, which keeps transparency. The id is the hash of the stored bytes, so
 * the same picture inserted twice is one file with two references.
 *
 * Skia does the decoding, as it does for everything Compose draws on desktop; it also reads the
 * WebP files Android stores.
 */
class AttachmentStore(
    private val directory: File,
    private val db: NotesDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val attachments = db.attachmentDao()

    /** The file for [id], or null for anything that is not a SHA-256 name and could leave the directory. */
    fun fileFor(id: String): File? = id.takeIf { it.matches(ATTACHMENT_ID) }?.let { File(directory, it) }

    /**
     * Stores [bytes] as a picture and claims a reference to it. Null when they are not a picture
     * Skia can read: a placeholder pointing at nothing would be worse than nothing on the page.
     */
    suspend fun import(bytes: ByteArray): ImportedPicture? = withContext(io) {
        val encoded = reencode(bytes) ?: return@withContext null
        val id = encoded.bytes.sha256()
        val file = File(directory, id)
        // Content-addressed: a file already there is already these bytes, and rewriting it could
        // only tear it for something reading it. Written beside and moved into place, so a crash
        // cannot leave a truncated file under the name its hash promises.
        if (!file.exists()) {
            directory.mkdirs()
            val staging = File(directory, "$id.part")
            staging.writeBytes(encoded.bytes)
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        }
        db.withTransaction {
            attachments.insert(
                AttachmentEntity(
                    id = id,
                    mimeType = MIME_TYPE,
                    pixelWidth = encoded.width,
                    pixelHeight = encoded.height,
                    byteCount = encoded.bytes.size.toLong(),
                    refCount = 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            attachments.retain(id)
        }
        ImportedPicture(id, encoded.width, encoded.height)
    }

    /** The stored bytes of [id], or null when there is no such file. */
    suspend fun bytes(id: String): ByteArray? = withContext(io) {
        fileFor(id)?.takeIf { it.isFile }?.readBytes()
    }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    private fun reencode(bytes: ByteArray): Encoded? = runCatching {
        Image.makeFromEncoded(bytes).use { source ->
            val longest = maxOf(source.width, source.height)
            val scale = if (longest > MAX_DIMENSION) MAX_DIMENSION.toFloat() / longest else 1f
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(Color.TRANSPARENT)
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                surface.makeImageSnapshot().use { image ->
                    image.encodeToData(EncodedImageFormat.WEBP, QUALITY)?.use { data ->
                        Encoded(data.bytes, width, height)
                    }
                }
            }
        }
    }.getOrNull()

    companion object {
        /** Beside `notes.db`, as Android's is beside its database in `filesDir`. */
        const val DIRECTORY = "attachments"

        /** Android's `MAX_DIMENSION`: the longest side an imported picture is kept at. */
        const val MAX_DIMENSION = 2048

        /** Android's `MIME_TYPE` and `QUALITY`. */
        const val MIME_TYPE = "image/webp"
        const val QUALITY = 88

        private val ATTACHMENT_ID = Regex("[0-9a-f]{64}")

        internal fun ByteArray.sha256(): String =
            MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    }
}
