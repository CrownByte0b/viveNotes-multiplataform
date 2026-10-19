package com.vivenotes.desktop.camera

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a camera frame becomes: the picture the window shows, and the photo the page gets. */
class CameraFramesTest {

    @Test
    fun yuyvLimitedRangeBecomesTheColoursItMeans() {
        // Two pixels per four bytes. White, black, then BT.601's red and blue at full chroma.
        assertRgb(255, 255, 255, Yuyv.toRgba(yuyv(235 to (128 to 128)), 2, 1, 4))
        assertRgb(0, 0, 0, Yuyv.toRgba(yuyv(16 to (128 to 128)), 2, 1, 4))
        assertRgb(255, 0, 0, Yuyv.toRgba(yuyv(81 to (90 to 240)), 2, 1, 4), tolerance = 2)
        assertRgb(0, 0, 255, Yuyv.toRgba(yuyv(41 to (240 to 110)), 2, 1, 4), tolerance = 2)
    }

    @Test
    fun eachPixelOfAPairKeepsItsOwnBrightness() {
        val rgba = Yuyv.toRgba(byteArrayOf(16, 128.toByte(), 235.toByte(), 128.toByte()), 2, 1, 4)
        assertEquals(0, rgba[0].toInt() and 0xFF)
        assertEquals(255, rgba[4].toInt() and 0xFF)
    }

    @Test
    fun rowsFollowTheLineLengthTheDriverReportsNotTheWidth() {
        // A 2 × 2 frame whose lines are padded to eight bytes: the second line starts at byte 8.
        val frame = byteArrayOf(16, 128.toByte(), 16, 128.toByte(), 0, 0, 0, 0,
            235.toByte(), 128.toByte(), 235.toByte(), 128.toByte(), 0, 0, 0, 0)
        val rgba = Yuyv.toRgba(frame, 2, 2, 8)
        assertEquals(0, rgba[0].toInt() and 0xFF)
        assertEquals(255, rgba[8].toInt() and 0xFF)
    }

    @Test
    fun aShortFrameIsBlackWhereBytesAreMissingRatherThanReadPast() {
        val rgba = Yuyv.toRgba(yuyv(235 to (128 to 128)), 4, 2, 8)
        assertEquals(4 * 2 * 4, rgba.size)
        assertEquals(255, rgba[0].toInt() and 0xFF)
        assertEquals(0, rgba[rgba.size - 4].toInt() and 0xFF)
        assertEquals(255, rgba[rgba.size - 1].toInt() and 0xFF, "still opaque")
    }

    @Test
    fun aPreviewFitsItsBoxWithoutEverBeingEnlarged() {
        assertEquals(1280 to 720, fitWithin(1920, 1080, 1280, 720))
        assertEquals(960 to 720, fitWithin(1280, 960, 1280, 720))
        assertEquals(640 to 480, fitWithin(640, 480, 1280, 720))
        assertEquals(320 to 240, fitWithin(320, 240, 1280, 720))
    }

    @Test
    fun theRingReusesItsBitmapsInTurnAndRenewsThemForANewSize() {
        val ring = PreviewRing(slots = 3)
        val first = List(4) { ring.draw(4, 3) { it.clear(Color.RED) }.asSkiaBitmap() }
        assertEquals(3, first.toSet().size)
        assertSame(first[0], first[3])
        val resized = ring.draw(8, 6) { it.clear(Color.RED) }.asSkiaBitmap()
        assertTrue(resized !in first)
        assertEquals(8 to 6, resized.width to resized.height)
    }

    @Test
    fun aBitmapDrawnAgainShowsTheNewFrame() {
        val ring = PreviewRing(slots = 1)
        val red = ring.draw(2, 2) { it.clear(Color.RED) }
        val blue = ring.draw(2, 2) { it.clear(Color.BLUE) }
        assertNotSame(red, blue, "each frame is a new bitmap to Compose, so it redraws")
        val pixel = blue.toPixelMap()[0, 0]
        assertTrue(pixel.blue > 0.9f && pixel.red < 0.1f, "$pixel")
    }

    @Test
    fun aJpegFrameIsShownAtItsOwnShapeAndIsItselfThePhoto() {
        val jpeg = jpeg(320, 180)
        val frame = RawFrame(PixelFormat.Mjpeg, 640, 480, 0, jpeg)
        val shown = assertNotNull(preview(frame, PreviewRing(), 160, 160))
        assertEquals(160 to 90, shown.width to shown.height)
        assertSame(jpeg, photo(frame))
    }

    @Test
    fun aBrokenJpegFrameIsNeitherShownNorAPhoto() {
        val frame = RawFrame(PixelFormat.Mjpeg, 640, 480, 0, byteArrayOf(-1, -40, -1, 0, 1, 2))
        assertNull(preview(frame, PreviewRing(), 160, 160))
        assertNull(photo(frame))
    }

    @Test
    fun aRawFrameIsShownSmallAndEncodedWhole() {
        val width = 64
        val height = 48
        val raw = ByteArray(width * height * 2) { if (it % 2 == 0) 235.toByte() else 128.toByte() }
        val frame = RawFrame(PixelFormat.Yuyv, width, height, width * 2, raw)

        val shown = assertNotNull(preview(frame, PreviewRing(), 32, 32))
        assertEquals(32 to 24, shown.width to shown.height)
        val photo = assertNotNull(photo(frame))
        Image.makeFromEncoded(photo).use { decoded ->
            assertEquals(width to height, decoded.width to decoded.height)
        }
    }

    private fun yuyv(pair: Pair<Int, Pair<Int, Int>>): ByteArray {
        val (y, chroma) = pair
        val (u, v) = chroma
        return byteArrayOf(y.toByte(), u.toByte(), y.toByte(), v.toByte())
    }

    private fun assertRgb(r: Int, g: Int, b: Int, rgba: ByteArray, tolerance: Int = 0) {
        val actual = Triple(rgba[0].toInt() and 0xFF, rgba[1].toInt() and 0xFF, rgba[2].toInt() and 0xFF)
        assertTrue(abs(actual.first - r) <= tolerance && abs(actual.second - g) <= tolerance &&
            abs(actual.third - b) <= tolerance, "expected ($r, $g, $b), got $actual")
        assertEquals(255, rgba[3].toInt() and 0xFF)
    }

    private fun jpeg(width: Int, height: Int): ByteArray = Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(Color.GREEN)
        surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.JPEG)!!.bytes }
    }
}
