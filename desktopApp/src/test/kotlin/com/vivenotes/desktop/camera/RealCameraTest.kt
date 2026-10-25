package com.vivenotes.desktop.camera

import com.vivenotes.data.CameraException
import com.vivenotes.data.NotesLibrary
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The camera against a real one: skipped unless `VIVE_CAMERA_DEVICE` names its node, as in
 * `VIVE_CAMERA_DEVICE=/dev/video0 ./gradlew :desktopApp:test --tests '*RealCameraTest*'`.
 *
 * On a machine without one, the kernel's virtual `vivid` camera (`modprobe vivid`) will do.
 */
class RealCameraTest {

    private val path: String? = System.getenv("VIVE_CAMERA_DEVICE")
    private val log = DebugLog(enabled = System.getenv("VIVE_CAMERA_LOG") != null)

    @Before
    fun needsACamera() {
        assumeTrue("set VIVE_CAMERA_DEVICE=/dev/videoN to run against a camera", path != null)
    }

    private suspend fun device() = LinuxCamera(log = log).cameras().first { it.id == path }

    @Test
    fun theCameraIsListedByTheNameItsDriverGives() = runBlocking {
        val device = device()
        assertTrue(device.name.isNotBlank() && device.name != device.id, "named ${device.name}")
    }

    @Test
    fun framesArriveAndTheLastBecomesAPhotoTheStoreKeeps() = runBlocking {
        val frames = withTimeout(20_000) { LinuxCamera(log = log).frames(device()).take(30).toList() }
        val last = frames.last()
        assertTrue(last.preview.width >= 160 && last.preview.height >= 120, "preview ${last.preview.width}x${last.preview.height}")
        val photo = assertNotNull(last.photo())
        val (width, height) = Image.makeFromEncoded(photo).use { it.width to it.height }
        assertTrue(width >= last.preview.width && height >= last.preview.height, "photo ${width}x$height")

        val directory = Files.createTempDirectory("real-camera").toFile()
        try {
            NotesLibrary.open(directory).use { library ->
                val stored = assertNotNull(library.attachments.import(photo))
                assertEquals(width to height, stored.pixelWidth to stored.pixelHeight)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aSecondStreamOfTheSameCameraIsToldItIsBusy() = runBlocking {
        val device = device()
        coroutineScope {
            val first = launch { LinuxCamera(log = log).frames(device).collect {} }
            // Long enough for the first to be streaming.
            delay(1_500)
            val failure = assertFailsWith<CameraException> {
                withTimeout(20_000) { LinuxCamera(log = log).frames(device).first() }
            }
            assertEquals(CameraException.Reason.Busy, failure.reason)
            first.cancelAndJoin()
        }
    }

    @Test
    fun theCameraOpensAgainTheMomentAWindowLetsItGo() = runBlocking {
        val device = device()
        repeat(3) {
            withTimeout(20_000) { LinuxCamera(log = log).frames(device).take(2).toList() }
        }
    }

    /**
     * Also skipped unless `VIVE_CAMERA_UNPLUG` holds a shell command that takes the camera away while
     * it streams — for `vivid`, `echo vivid.0 > /sys/bus/platform/drivers/vivid/unbind` as root.
     * Run it on its own: the camera stays gone for whatever runs after it.
     */
    @Test
    fun aCameraTakenAwayMidStreamSaysItStopped() = runBlocking {
        val unplug = System.getenv("VIVE_CAMERA_UNPLUG")
        assumeTrue("set VIVE_CAMERA_UNPLUG to a command that removes the camera", unplug != null)
        val device = device()
        var frames = 0
        val failure = assertFailsWith<CameraException> {
            withTimeout(30_000) {
                LinuxCamera(log = log).frames(device).collect {
                    if (++frames == 10) ProcessBuilder("sh", "-c", unplug).inheritIO().start().waitFor()
                }
            }
        }
        assertEquals(CameraException.Reason.Stopped, failure.reason)
    }
}
