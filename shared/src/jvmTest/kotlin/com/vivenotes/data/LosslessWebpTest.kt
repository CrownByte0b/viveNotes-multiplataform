package com.vivenotes.data

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The encoder against a decoder it did not write: Skia's, which is libwebp's, as Android's is. Every
 * opaque pixel has to come back exactly; a transparent one is only compared opaque-side, since
 * reading it back goes through premultiplied alpha.
 */
class LosslessWebpTest {
    private fun decode(bytes: ByteArray, width: Int, height: Int): IntArray = Image.makeFromEncoded(bytes).use { image ->
        assertEquals(width to height, image.width to image.height)
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = Bitmap().apply { allocPixels(info) }
        assertTrue(image.readPixels(bitmap, 0, 0))
        val raw = requireNotNull(bitmap.readPixels(info, width * 4, 0, 0))
        IntArray(width * height) { index ->
            val at = index * 4
            ((raw[at + 3].toInt() and 0xFF) shl 24) or ((raw[at + 2].toInt() and 0xFF) shl 16) or
                ((raw[at + 1].toInt() and 0xFF) shl 8) or (raw[at].toInt() and 0xFF)
        }
    }

    private fun assertRoundTrip(width: Int, height: Int, pixels: IntArray): ByteArray {
        val bytes = LosslessWebp.encode(width, height, pixels)
        assertEquals("RIFF", bytes.decodeToString(0, 4))
        assertEquals("WEBP", bytes.decodeToString(8, 12))
        assertEquals("VP8L", bytes.decodeToString(12, 16))
        assertEquals(bytes.size - 8, littleEndian(bytes, 4), "the RIFF size")
        val back = decode(bytes, width, height)
        val first = pixels.indices.firstOrNull { pixels[it] != back[it] }
        assertEquals(null, first, first?.let { "pixel $it: ${pixels[it].toUInt().toString(16)} came back ${back[it].toUInt().toString(16)}" })
        return bytes
    }

    private fun littleEndian(bytes: ByteArray, at: Int): Int =
        (0 until 4).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

    @Test
    fun blankPaperIsAFewBytes() {
        val bytes = assertRoundTrip(300, 200, IntArray(300 * 200) { WHITE })
        assertTrue(bytes.size < 100, "${bytes.size} bytes")
    }

    @Test
    fun aSinglePixelAndASingleColumnRoundTrip() {
        assertRoundTrip(1, 1, intArrayOf(0xFF123456.toInt()))
        assertRoundTrip(1, 500, IntArray(500) { if (it % 3 == 0) 0xFF00FF00.toInt() else WHITE })
    }

    /** A drawn page: runs of paper, lines of grey ink, a coloured figure — and much smaller than PNG would be. */
    @Test
    fun aPageOfTextRoundTripsExactlyAndSmall() {
        val width = 1584
        val height = 2048
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                y in 1500..1800 && x in 300..900 -> 0xFF4472C4.toInt()
                (y / 30) % 2 == 0 && y % 30 < 18 && (x / 7) % 3 == 0 && x in 150..1400 -> 0xFF000000.toInt() or ((x + y) % 120 * 0x010101)
                else -> WHITE
            }
        }
        val bytes = assertRoundTrip(width, height, pixels)
        assertTrue(bytes.size < 200_000, "${bytes.size} bytes for a page")
    }

    /** Nothing repeats in noise: every pixel a literal, every code long, and still exact. */
    @Test
    fun noiseRoundTripsExactly() {
        val random = Random(7)
        assertRoundTrip(320, 240, IntArray(320 * 240) { random.nextInt() or OPAQUE })
    }

    @Test
    fun aGradientAndAFewGreysRoundTripExactly() {
        assertRoundTrip(513, 77, IntArray(513 * 77) { OPAQUE or ((it % 256) shl 16) or (((it / 7) % 256) shl 8) or ((it / 513) % 256) })
        val random = Random(3)
        assertRoundTrip(400, 300, IntArray(400 * 300) { OPAQUE or (random.nextInt(4) * 85 * 0x010101) })
    }

    /** Two colours only: the prefix codes the format writes in their short form. */
    @Test
    fun twoColoursRoundTripExactly() {
        val random = Random(11)
        assertRoundTrip(100, 100, IntArray(100 * 100) { if (random.nextBoolean()) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
    }

    /** Alpha is kept: a fully transparent or opaque pixel exactly, and the flag says it is used. */
    @Test
    fun transparencyIsKept() {
        val pixels = IntArray(64 * 64) { if ((it / 64 + it % 64) % 2 == 0) 0 else 0xFF336699.toInt() }
        val bytes = assertRoundTrip(64, 64, pixels)
        // Byte 20 is the signature; the alpha flag is bit 28 of the 32 bits after it.
        assertEquals(1, (littleEndian(bytes, 21) ushr 28) and 1)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val OPAQUE = 0xFF000000.toInt()
    }
}
