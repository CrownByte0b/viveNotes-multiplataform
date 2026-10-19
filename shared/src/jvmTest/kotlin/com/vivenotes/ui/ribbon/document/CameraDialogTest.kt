package com.vivenotes.ui.ribbon.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vivenotes.data.CameraDevice
import com.vivenotes.data.CameraException
import com.vivenotes.data.CameraFrame
import com.vivenotes.data.PictureCamera
import com.vivenotes.ui.theme.ViveNotesTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.test.Test

/** The camera window: its picture, its shutter, and what it says when the camera will not play. */
@OptIn(ExperimentalTestApi::class)
class CameraDialogTest {

    @Test
    fun thePictureShowsAndTheShutterInsertsThePhoto() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val camera = FakeCamera()
        var inserted: ByteArray? = null
        var dismissed = false
        setDialog(camera, onInsert = { inserted = it }, onDismiss = { dismissed = true })

        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).performClick()
        waitUntil { dismissed }

        assertArrayEquals(FakeCamera.photoOf(FakeCamera.INTEGRATED), inserted)
        // Once. Regression: the stream's effect read the camera list from state rather than from its
        // key, so it opened the camera, restarted when the key caught up, and opened it again — on a
        // real camera, a second open racing the first one's release.
        assertEquals(listOf(FakeCamera.INTEGRATED), camera.opened)
    }

    @Test
    fun theShutterWaitsForAPicture() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera(stream = { flow { awaitCancellation() } }))

        waitUntil { onAllNodesWithTag(CameraTags.STARTING).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).assertIsNotEnabled()
    }

    /** From a fresh window with nothing clicked: Escape closes it, and Enter takes the photo. */
    @Test
    fun theKeyboardWorksFromTheStart() = runDesktopComposeUiTest(width = 1200, height = 800) {
        var inserted: ByteArray? = null
        setDialog(FakeCamera(), onInsert = { inserted = it })

        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onNodeWithTag(CameraTags.SHUTTER).assertIsFocused()
        onRoot().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitUntil { inserted != null }
    }

    @Test
    fun escapeClosesTheWindowAndTheCameraStops() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val camera = FakeCamera()
        var open by mutableStateOf(true)
        setContent {
            ViveNotesTheme(darkTheme = true) {
                if (open) CameraDialog(camera, onInsert = {}, onDismiss = { open = false })
            }
        }
        waitUntil { camera.streaming == 1 }

        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }

        waitUntil { !open }
        waitUntil { camera.streaming == 0 }
    }

    @Test
    fun aCameraAnotherAppHasSaysSo() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera(stream = { flow { throw CameraException(CameraException.Reason.Busy) } }))

        waitUntil { onAllNodes(hasText("being used by another app", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).assertIsNotEnabled()
    }

    @Test
    fun aCameraThatStopsSendingSaysSo() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera(stream = { emptyFlow() }))

        waitUntil { onAllNodes(hasText("stopped sending pictures", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(CameraTags.PREVIEW).assertCountEquals(0)
    }

    @Test
    fun aCameraGoneBetweenTheMenuAndTheWindowSaysItCouldNotBeOpened() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera(devices = emptyList()))

        waitUntil { onAllNodes(hasText("couldn’t be opened", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anUnexpectedFailureIsShownNotThrown() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera(stream = { flow { throw IllegalStateException("driver") } }))

        waitUntil { onAllNodes(hasText("couldn’t be opened", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aPhotoThatCannotBeMadeKeepsTheWindowOpen() = runDesktopComposeUiTest(width = 1200, height = 800) {
        var dismissed = false
        val camera = FakeCamera(stream = {
            flow {
                emit(CameraFrame(ImageBitmap(32, 24)) { null })
                awaitCancellation()
            }
        })
        setDialog(camera, onDismiss = { dismissed = true })

        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).performClick()

        waitUntil { onAllNodes(hasText("photo couldn’t be taken", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!dismissed)
    }

    @Test
    fun switchCameraMovesToTheNextOneAndBackAround() = runDesktopComposeUiTest(width = 1200, height = 800) {
        val usb = CameraDevice("/dev/video2", "USB Camera")
        val camera = FakeCamera(devices = listOf(FakeCamera.INTEGRATED, usb))
        var inserted: ByteArray? = null
        setDialog(camera, onInsert = { inserted = it })
        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SWITCH).assertIsDisplayed()

        onNodeWithTag(CameraTags.SWITCH).performClick()
        waitUntil { camera.opened.size == 2 && onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CameraTags.SHUTTER).performClick()
        waitUntil { inserted != null }

        assertArrayEquals(FakeCamera.photoOf(usb), inserted)
        assertEquals(listOf(FakeCamera.INTEGRATED, usb), camera.opened)
        // Only the camera on screen streams.
        assertEquals(1, camera.streaming)

        onNodeWithTag(CameraTags.SWITCH).performClick()
        waitUntil { camera.opened.size == 3 }
        assertEquals(FakeCamera.INTEGRATED, camera.opened.last())
    }

    @Test
    fun oneCameraNeedsNoSwitch() = runDesktopComposeUiTest(width = 1200, height = 800) {
        setDialog(FakeCamera())
        waitUntil { onAllNodesWithTag(CameraTags.PREVIEW).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(CameraTags.SWITCH).assertCountEquals(0)
    }

    private fun ComposeUiTest.setDialog(
        camera: PictureCamera,
        onInsert: (ByteArray) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            ViveNotesTheme(darkTheme = true) {
                CameraDialog(camera, onInsert, onDismiss)
            }
        }
    }
}
