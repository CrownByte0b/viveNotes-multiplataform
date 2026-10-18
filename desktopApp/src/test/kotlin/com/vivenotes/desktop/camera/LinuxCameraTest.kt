package com.vivenotes.desktop.camera

import com.vivenotes.data.CameraDevice
import com.vivenotes.data.CameraException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The camera as the Picture menu and window ask for it, on nodes that are not cameras. Real capture
 * needs a camera — the native half was checked against the kernel's virtual `vivid` camera; see the
 * repository's `memory/document-tab.md`.
 */
class LinuxCameraTest {

    private val devices: File = Files.createTempDirectory("dev").toFile()

    /** Video4Linux2 is Linux's: elsewhere the desktop has no camera backend to test. */
    @Before
    fun linuxOnly() {
        assumeTrue("Video4Linux2 is Linux's", V4l2.supported)
    }

    @AfterTest
    fun tearDown() {
        devices.deleteRecursively()
    }

    @Test
    fun aComputerWithoutVideoNodesHasNoCamera() = runBlocking {
        assertEquals(emptyList(), LinuxCamera(devices).cameras())
    }

    @Test
    fun aNodeThatIsNotACameraIsLeftOut() = runBlocking {
        File(devices, "video0").writeText("not a device")
        File(devices, "videoX").writeText("not a video node name")
        assertEquals(emptyList(), LinuxCamera(devices).cameras())
    }

    @Test
    fun aCameraThatCannotBeOpenedSaysSoInsteadOfThrowingSomethingElse() = runBlocking {
        val gone = CameraDevice(File(devices, "video9").path, "Gone")
        val failure = assertFailsWith<CameraException> { LinuxCamera(devices).frames(gone).first() }
        assertEquals(CameraException.Reason.Failed, failure.reason)
    }

    @Test
    fun aFileThatOpensButIsNoCameraFailsTheSameWay() = runBlocking {
        val notACamera = File(devices, "video0").apply { writeText("not a device") }
        val failure = assertFailsWith<CameraException> {
            LinuxCamera(devices).frames(CameraDevice(notACamera.path, "Fake")).first()
        }
        assertEquals(CameraException.Reason.Failed, failure.reason)
    }
}

/** Which systems get a camera at all: Linux on the ABIs the binding knows, nothing elsewhere yet. */
class DesktopCameraTest {
    @Test
    fun onlyLinuxHasACameraBackend() {
        assertEquals(V4l2.supported, desktopCamera() != null)
        val os = System.getProperty("os.name")
        if (os.startsWith("Linux") && System.getProperty("os.arch") == "amd64") assertTrue(V4l2.supported)
        if (os.startsWith("Windows")) assertEquals(null, desktopCamera())
    }
}
