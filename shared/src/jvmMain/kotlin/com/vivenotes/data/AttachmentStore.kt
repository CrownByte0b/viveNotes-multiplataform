package com.vivenotes.data

import com.vivenotes.data.db.AttachmentEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.sync.AttachmentBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.ConcurrentHashMap

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

    private val _arrivals = MutableStateFlow(0L)

    /**
     * Android's `arrivals`: bumped whenever bytes appear that were not here before — today, a picture
     * another device drew on a page this one is already showing.
     *
     * The page caches a failed decode exactly like a successful one, deliberately, so a broken
     * picture is not re-read on every recomposition. A pulled document makes that unsafe: the frame
     * lands seconds before the bytes it points at, and without this the reader sees a broken plate
     * until they navigate away and back.
     */
    val arrivals: StateFlow<Long> = _arrivals.asStateFlow()

    /** The file for [id], or null for anything that is not a SHA-256 name and could leave the directory. */
    fun fileFor(id: String): File? = id.takeIf { it.matches(ATTACHMENT_ID) }?.let { File(directory, it) }

    /**
     * The bytes half of this store as sync needs it — on Android `AttachmentStore` implements
     * [AttachmentBytes] itself. A view here so that [fileFor] keeps answering null for a name that is
     * not a digest, where sync's contract has no null: such a name is refused with an exception.
     */
    val syncBytes: AttachmentBytes = object : AttachmentBytes {
        override fun fileFor(id: String): File =
            this@AttachmentStore.fileFor(id) ?: throw IllegalArgumentException("Not an attachment id")

        /**
         * Where a download is written before its digest is proved — beside the file it will become,
         * so that publishing it is a rename within one filesystem rather than a copy.
         *
         * A distinct suffix from [import]'s `.part`: an import and a download of the same picture can
         * legitimately be in flight at once, and two writers sharing a staging name would tear each
         * other's bytes.
         */
        override fun stagingFor(id: String): File = File(fileFor(id).path + ".sync")

        /**
         * Moves proved bytes into place under [id] and announces them.
         *
         * The caller has already checked that the bytes hash to [id] — that is what makes this a
         * rename rather than a write. A file already sitting there is *already these bytes* by the
         * same argument, so the staging copy is simply dropped: rewriting it could only produce the
         * same content, and would tear the file for anything decoding it at that moment.
         */
        override fun publish(staged: File, id: String): Boolean {
            val target = fileFor(id)
            if (target.exists()) {
                staged.delete()
                return true
            }
            if (!staged.renameTo(target)) {
                staged.delete()
                return false
            }
            _arrivals.value += 1
            return true
        }

        override val arrivals: StateFlow<Long> get() = this@AttachmentStore.arrivals
    }

    /**
     * Pictures something in memory can still put back on a page, by whoever holds them.
     *
     * The editor's undo and redo history is not persisted — it dies with the process — so it cannot
     * be found in the database, and a picture deleted from a page is still one press of Undo away.
     * The editor registers a function answering "which pictures could I restore right now", and
     * [AttachmentSweep] asks it before letting any go. A process exit takes both the history and
     * the registration with it, which is exactly when the protection stops being needed.
     */
    private val holders = ConcurrentHashMap<Any, () -> Set<String>>()

    /** Registers [owner]'s answer. Called on the main thread, and asked on it. */
    fun hold(owner: Any, pictures: () -> Set<String>) {
        holders[owner] = pictures
    }

    fun releaseHold(owner: Any) {
        holders.remove(owner)
    }

    /**
     * Every picture a registered holder can restore.
     *
     * Asked on the main thread, because the editor's history is main-thread state and reading it
     * from the sweep's thread would race the edit that changes it.
     */
    suspend fun heldPictures(): Set<String> = withContext(Dispatchers.Main.immediate) {
        holders.values.flatMapTo(hashSetOf()) { it() }
    }

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
