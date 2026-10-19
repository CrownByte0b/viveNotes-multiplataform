package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vivenotes.data.CameraDevice
import com.vivenotes.data.CameraException
import com.vivenotes.data.CameraFrame
import com.vivenotes.data.PictureCamera
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.DesktopDialogPanel
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.ViewSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object CameraTags {
    const val DIALOG = "camera-dialog"
    const val BACKDROP = "camera-backdrop"
    const val CLOSE = "camera-close"
    const val PREVIEW = "camera-preview"
    const val STARTING = "camera-starting"
    const val MESSAGE = "camera-message"
    const val SHUTTER = "camera-shutter"
    const val SWITCH = "camera-switch"
}

/**
 * The Picture menu's "Camera" on a computer: the camera's picture, and a shutter.
 *
 * Android lends the system camera app a file and inserts what it writes there. A desktop has no
 * camera app to lend it to, so this window stands in for one: the photo is the frame on screen when
 * Take photo is pressed, and it goes onto the page at once, as Android's returns from the camera app.
 *
 * The camera is open only while the window is: leaving composition stops collecting its frames.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CameraDialog(
    camera: PictureCamera,
    onInsert: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Null until the camera has been asked which there are. */
    var devices by remember { mutableStateOf<List<CameraDevice>?>(null) }
    var deviceIndex by remember { mutableIntStateOf(0) }
    /** The frame on screen, and the photo if the shutter is pressed now. */
    var frame by remember { mutableStateOf<CameraFrame?>(null) }
    var failure by remember { mutableStateOf<CameraException.Reason?>(null) }
    var taking by remember { mutableStateOf(false) }
    var photoFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val shutterFocus = remember { FocusRequester() }

    LaunchedEffect(camera) {
        devices = try {
            camera.cameras()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // The keys as this composition saw them, not the states read when the effect runs: read there,
    // the list that arrives a moment later would open the camera once, then restart and open it again.
    val shownDevices = devices
    val shownIndex = deviceIndex
    LaunchedEffect(shownDevices, shownIndex) {
        val available = shownDevices ?: return@LaunchedEffect
        frame = null
        failure = null
        val device = available.getOrNull(shownIndex)
        if (device == null) {
            // The camera went between the menu and the window.
            failure = CameraException.Reason.Failed
            return@LaunchedEffect
        }
        failure = try {
            camera.frames(device).collect { frame = it }
            // A picture that ends on its own is a camera that went away.
            CameraException.Reason.Stopped
        } catch (e: CameraException) {
            e.reason
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraException.Reason.Failed
        }
        frame = null
    }

    val ready = frame != null

    fun takePhoto() {
        val shot = frame ?: return
        if (taking) return
        taking = true
        photoFailed = false
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { runCatching { shot.photo() }.getOrNull() }
            taking = false
            if (bytes == null) {
                photoFailed = true
            } else {
                onInsert(bytes)
                onDismiss()
            }
        }
    }

    DesktopDialogPanel(
        title = Strings.ribbon_picture_camera,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(CameraTags.DIALOG),
        maxWidth = MAX_WIDTH,
        maxHeight = MAX_HEIGHT,
        backdropTag = CameraTags.BACKDROP,
    ) {
        // Keys reach only what has focus: the window takes it, so Escape closes it, and then hands it
        // to the shutter once there is a picture, so Enter or Space takes the photo. Inside the
        // panel, whose content is composed only as it is measured.
        LaunchedEffect(ready) { if (ready) shutterFocus.requestFocus() else closeFocus.requestFocus() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).testTag(CameraTags.CLOSE)) {
                Icon(ViewSymbols.Close, contentDescription = Strings.close)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(Strings.ribbon_picture_camera, style = MaterialTheme.typography.titleMedium)
                devices?.getOrNull(deviceIndex)?.let { device ->
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val shown = frame
            val reason = failure
            when {
                shown != null -> Image(
                    bitmap = shown.preview,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag(CameraTags.PREVIEW),
                )
                reason != null -> Text(
                    text = when (reason) {
                        CameraException.Reason.Busy -> Strings.camera_busy
                        CameraException.Reason.Failed -> Strings.camera_failed
                        CameraException.Reason.Stopped -> Strings.camera_stopped
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp).testTag(CameraTags.MESSAGE),
                )
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.testTag(CameraTags.STARTING),
                ) {
                    LoadingIndicator()
                    Text(
                        text = Strings.camera_starting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (photoFailed) {
            Text(
                text = Strings.camera_photo_failed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp)
                    .testTag(CameraTags.MESSAGE),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if ((devices?.size ?: 0) > 1) {
                OutlinedButton(
                    onClick = { if (!taking) deviceIndex = (deviceIndex + 1) % (devices?.size ?: 1) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(CameraTags.SWITCH),
                ) {
                    Text(Strings.camera_switch)
                }
            }
            // Not disabled while the photo is encoded: a disabled button gives up the focus, and
            // with it the window's Escape. A second press meanwhile is ignored instead.
            Button(
                onClick = ::takePhoto,
                enabled = ready,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.focusRequester(shutterFocus).testTag(CameraTags.SHUTTER),
            ) {
                Icon(DocumentSymbols.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(Strings.camera_take_photo)
            }
        }
    }
}

private val MAX_WIDTH = 760.dp
private val MAX_HEIGHT = 640.dp
