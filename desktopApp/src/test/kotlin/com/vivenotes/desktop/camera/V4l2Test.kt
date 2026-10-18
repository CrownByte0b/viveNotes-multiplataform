package com.vivenotes.desktop.camera

import com.vivenotes.data.CameraException
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Video4Linux2 interface as the binding speaks it. The expected numbers were printed by a C
 * program compiled against `/usr/include/linux/videodev2.h` on x86-64 (2026-10-18): the kernel's
 * own ioctl numbers, which carry each struct's size, so a layout off by a field is refused.
 */
class V4l2Test {

    @Test
    fun ioctlNumbersAreTheKernelsOwn() {
        assertEquals(0x80685600L, V4l2.VIDIOC_QUERYCAP)
        assertEquals(0xc0405602L, V4l2.VIDIOC_ENUM_FMT)
        assertEquals(0xc0d05605L, V4l2.VIDIOC_S_FMT)
        assertEquals(0xc0145608L, V4l2.VIDIOC_REQBUFS)
        assertEquals(0xc0585609L, V4l2.VIDIOC_QUERYBUF)
        assertEquals(0xc058560fL, V4l2.VIDIOC_QBUF)
        assertEquals(0xc0585611L, V4l2.VIDIOC_DQBUF)
        assertEquals(0x40045612L, V4l2.VIDIOC_STREAMON)
        assertEquals(0x40045613L, V4l2.VIDIOC_STREAMOFF)
        assertEquals(0xc02c564aL, V4l2.VIDIOC_ENUM_FRAMESIZES)
    }

    @Test
    fun formatCodesAndFlagsAreTheKernelsOwn() {
        assertEquals(0x56595559, V4l2.PIX_FMT_YUYV)
        assertEquals(0x47504a4d, V4l2.PIX_FMT_MJPEG)
        assertEquals(0x4745504a, V4l2.PIX_FMT_JPEG)
        assertEquals(0x1, V4l2.CAP_VIDEO_CAPTURE)
        assertEquals(0x4000000, V4l2.CAP_STREAMING)
        assertEquals(0x80000000.toInt(), V4l2.CAP_DEVICE_CAPS)
        assertEquals(0x40, V4l2.BUF_FLAG_ERROR)
        assertEquals(PixelFormat.Yuyv, PixelFormat.of(0x56595559))
        assertNull(PixelFormat.of(0x59455247), "GREY, an infrared sensor's format, is not one the app reads")
    }

    @Test
    fun jpegFramesAreChosenUpTo1080p() {
        val mode = chooseMode(listOf(
            CameraMode(PixelFormat.Yuyv, listOf(FrameSize(640, 480), FrameSize(1280, 720))),
            CameraMode(PixelFormat.Mjpeg, listOf(FrameSize(640, 480), FrameSize(1920, 1080), FrameSize(2592, 1944))),
        ))
        assertEquals(PixelFormat.Mjpeg to FrameSize(1920, 1080), mode)
    }

    @Test
    fun rawFramesAreKeptTo720pWhereTheCableAllowsItsRate() {
        val mode = chooseMode(listOf(
            CameraMode(PixelFormat.Yuyv, listOf(FrameSize(640, 480), FrameSize(1280, 720), FrameSize(1920, 1080))),
        ))
        assertEquals(PixelFormat.Yuyv to FrameSize(1280, 720), mode)
    }

    @Test
    fun aCameraWhoseEverySizeIsOverTheCapGetsItsSmallest() {
        val mode = chooseMode(listOf(CameraMode(PixelFormat.Mjpeg, listOf(FrameSize(3264, 2448), FrameSize(2592, 1944)))))
        assertEquals(PixelFormat.Mjpeg to FrameSize(2592, 1944), mode)
    }

    @Test
    fun aStepwiseCameraIsAskedForTheCapWithinItsLargest() {
        val mode = chooseMode(listOf(CameraMode(PixelFormat.Yuyv, emptyList(), largest = FrameSize(1600, 1200))))
        assertEquals(PixelFormat.Yuyv to FrameSize(1280, 720), mode)
    }

    @Test
    fun noFormatTheAppReadsIsNoMode() {
        assertNull(chooseMode(emptyList()))
        assertNull(chooseMode(listOf(CameraMode(PixelFormat.Yuyv, emptyList()))))
    }

    /** The real C calls, on what is not a camera: the binding links, and reads errno back. */
    @Test
    fun aNodeThatIsNotACameraIsNotDescribedAsOne() {
        assumeTrue("Video4Linux2 is Linux's", V4l2.supported)
        V4l2Device.open("/dev/null").use { assertNull(it.describe()) }
        val file = Files.createTempFile("video", "0").toFile()
        try {
            V4l2Device.open(file.path).use { assertNull(it.describe()) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun aMissingNodeFailsToOpenAsACameraFailure() {
        assumeTrue("Video4Linux2 is Linux's", V4l2.supported)
        val failure = assertFailsWith<CameraException> { V4l2Device.open(File("/dev/video-none").path) }
        assertEquals(CameraException.Reason.Failed, failure.reason)
    }

    @Test
    fun onlyAnotherProgramHoldingTheCameraReadsAsBusy() {
        assertEquals(CameraException.Reason.Busy, V4l2Device.failure(16).reason)
        assertEquals(CameraException.Reason.Failed, V4l2Device.failure(22).reason)
    }
}
