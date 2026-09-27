package com.vivenotes.ink

import com.vivenotes.data.db.InkMoveEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `ink/androidx1` point blob reader, held to what AndroidX Ink itself does.
 *
 * Everything under `resources/ink/androidx-ink-1.1.0-alpha06/` was produced by that library — the
 * Android app's version — running natively on Linux: `*.bin` are blobs it encoded and `*.txt` what
 * its decoder read back from them; `edges.txt` and `verdicts.txt` are uncompressed
 * `CodedStrokeInputBatch` messages, crafted or mutated, each with the library's verdict. The harness
 * compared this reader with the library on 12,000 encoded strokes and 312,000 crafted, mutated and
 * gzip-damaged messages without a difference; `verdicts.txt` is a sample of them.
 */
class StrokeInputBatchCodecTest {

    @Test
    fun blobsTheLibraryWroteDecodeToWhatTheLibraryReadBack() {
        GOLDEN.forEach { name ->
            val decoded = StrokeInputBatchCodec.decode(resource("$name.bin").readBytes())
            val expected = resource("$name.txt").readText().trim().lines()
            val header = expected.first().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            val inputs = expected.drop(1).map { line -> line.split(' ') }
            assertEquals(inputs.size, decoded.size, name)
            if (decoded.size > 0) {
                assertEquals(header.getValue("tool").removePrefix("InputToolType.").lowercase(),
                    decoded.toolType.name.lowercase(), name)
            }
            assertEquals(header.getValue("sul").toFloat(), decoded.strokeUnitLengthCm, name)
            assertEquals(header.getValue("hasP").toBoolean(), decoded.pressure != null, name)
            assertEquals(header.getValue("hasT").toBoolean(), decoded.tilt != null, name)
            assertEquals(header.getValue("hasO").toBoolean(), decoded.orientation != null, name)
            inputs.forEachIndexed { i, (x, y, millis, pressure, tilt, orientation) ->
                assertEquals(x.toFloat(), decoded.x[i], "$name x[$i]")
                assertEquals(y.toFloat(), decoded.y[i], "$name y[$i]")
                assertTrue(abs(millis.toLong() - decoded.elapsedSeconds[i] * 1000.0) <= 1.0, "$name t[$i]")
                decoded.pressure?.let { assertEquals(pressure.toFloat(), it[i], "$name pressure[$i]") }
                decoded.tilt?.let { assertEquals(tilt.toFloat(), it[i], "$name tilt[$i]") }
                decoded.orientation?.let { assertEquals(orientation.toFloat(), it[i], "$name orientation[$i]") }
            }
        }
    }

    @Test
    fun boundaryCasesGetTheLibrarysVerdict() {
        val cases = verdicts("edges.txt")
        assertEquals(35, cases.size)
        cases.forEach { (accepted, proto, name) ->
            assertEquals(accepted, InkCodec.hasValidInputData(gzip(proto)), name)
        }
    }

    @Test
    fun craftedAndDamagedMessagesGetTheLibrarysVerdict() {
        val cases = verdicts("verdicts.txt")
        assertTrue(cases.count { it.first } > 50 && cases.count { !it.first } > 50, "the sample covers both verdicts")
        cases.forEachIndexed { line, (accepted, proto, _) ->
            assertEquals(accepted, InkCodec.hasValidInputData(gzip(proto)), "verdicts.txt line ${line + 1}")
        }
    }

    @Test
    fun anInputRepeatingThePreviousPositionAndTimeIsDroppedNotRefused() {
        val decoded = StrokeInputBatchCodec.decode(gzip(verdicts("edges.txt").single { it.third == "duplicate input dropped" }.second))

        assertContentEquals(floatArrayOf(1f, 3f), decoded.x)
    }

    @Test
    fun whatIsNotGzipOrExpandsTooFarIsRefused() {
        assertFalse(InkCodec.hasValidInputData(byteArrayOf(1, 2, 3)))
        assertFalse(InkCodec.hasValidInputData(ByteArray(0)))
        // Zeros compress to almost nothing; Android would run out of memory on the equivalent.
        val bomb = gzip(ByteArray(StrokeInputBatchCodec.MAX_DECOMPRESSED_BYTES + 1))
        assertTrue(bomb.size < 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { StrokeInputBatchCodec.decode(bomb) }
    }

    @Test
    fun aMovePathIsReadExactlyAsAndroidWritesIt() {
        val path = listOf(InkPoint(5f, 5f), InkPoint(35f, 5f), InkPoint(35f, 45f))
        assertEquals(path, InkCodec.decodeMove(move(path.size, path.flatMap { listOf(it.x, it.y) })))
    }

    @Test
    fun aMovePathThatIsShortDamagedOrOtherwiseEncodedIsNotRead() {
        assertNull(InkCodec.decodeMove(move(2, listOf(0f, 0f, 1f, 1f))), "fewer than three points")
        assertNull(InkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, 1f, 2f))), "count and data disagree")
        assertNull(InkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, Float.NaN, 2f, 2f))), "non-finite point")
        assertNull(InkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, 1f, 2f, 2f)).copy(enc = InkCodec.ENCODING)))
    }

    private fun move(count: Int, values: List<Float>) = InkMoveEntity(
        id = "move",
        pageId = "page",
        dxDp = 1f,
        dyDp = 2f,
        points = ByteBuffer.allocate(4 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
            .apply { values.forEach(::putFloat) }.array(),
        enc = InkCodec.MOVE_ENCODING,
        createdAt = 1L,
    )

    private fun resource(name: String) = checkNotNull(javaClass.getResource("$FIXTURES/$name")) { name }

    /** Lines of `accept|reject <hex message> [name]`. */
    private fun verdicts(name: String): List<Triple<Boolean, ByteArray, String>> =
        resource(name).readText().lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(' ', limit = 3)
            Triple(parts[0] == "accept", parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                parts.getOrElse(2) { "" })
        }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private operator fun <T> List<T>.component6(): T = this[5]

    private companion object {
        const val FIXTURES = "/ink/androidx-ink-1.1.0-alpha06"
        val GOLDEN = listOf("two-point-unknown", "stylus-pressure-tilt-orientation", "touch-single", "mouse-long", "empty")
    }
}
