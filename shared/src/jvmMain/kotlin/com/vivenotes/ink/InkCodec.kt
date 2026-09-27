// The storage half of the Android app's `ink/InkCodec.kt`: its encoding ids and the two readers the
// `.vive` importer calls. Drawing, brushes and encoding wait for the desktop ink design.
package com.vivenotes.ink

import com.vivenotes.data.db.InkMoveEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A point in page dp, as Android's `ink/PageStroke.kt` has it. */
data class InkPoint(val x: Float, val y: Float)

/**
 * How ink rows are stored, and the checks an imported row has to pass.
 *
 * Android writes the point blobs with AndroidX Ink, which has no build for every desktop, so the
 * blob is read by [StrokeInputBatchCodec] under the same rules instead. A row the desktop accepts is
 * therefore one Android accepts, which is what lets a notebook travel back.
 */
object InkCodec {

    /** Who wrote a stroke or erase point blob: AndroidX Ink's gzip-compressed input batch. */
    const val ENCODING = "ink/androidx1"

    /** Little-endian count followed by x/y float pairs, all in page dp. */
    const val MOVE_ENCODING = "ink/lasso-f32le1"

    /** Structural validation for an imported point blob, as Android's `hasValidInputData`. */
    fun hasValidInputData(points: ByteArray): Boolean =
        runCatching { StrokeInputBatchCodec.decode(points) }.isSuccess

    /** A lasso move's path, or null when the row is not one this encoding can read. */
    fun decodeMove(entity: InkMoveEntity): List<InkPoint>? {
        if (entity.enc != MOVE_ENCODING) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(entity.points).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            require(count >= 3 && buffer.remaining() == count * 2 * Float.SIZE_BYTES)
            List(count) {
                InkPoint(buffer.float, buffer.float).also { point ->
                    require(point.x.isFinite() && point.y.isFinite())
                }
            }
        }.getOrNull()
    }
}
