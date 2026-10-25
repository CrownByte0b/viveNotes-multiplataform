// Ported from viveNotes Android ui/editor/Ribbon.kt's PictureButton.
package com.vivenotes.ui.ribbon.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vivenotes.data.OnlineImages
import com.vivenotes.data.PictureCamera
import com.vivenotes.localization.Strings
import com.vivenotes.ui.components.ScaledDropdownMenu
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.ribbon.RibbonIcon
import kotlinx.coroutines.launch

/** Where the Picture button's menu fetches a picture from. */
enum class PictureSource { Device, Online, Camera }

/**
 * The Picture button: a menu of where the picture comes from, rather than the file dialog at once.
 *
 * [sources] is asked each time the menu opens, because a camera can be plugged in or out between
 * two menus. Android asks once whether the device has a camera at all; a computer's comes and goes.
 */
@Composable
internal fun PictureButton(
    enabled: Boolean,
    sources: suspend () -> List<PictureSource>,
    onPick: (PictureSource) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var offered by remember { mutableStateOf(listOf(PictureSource.Device)) }
    val scope = rememberCoroutineScope()
    Box {
        RibbonIcon(DocumentSymbols.Image, Strings.picture, selected = open, enabled = enabled,
            tag = DocumentRibbonTags.Picture, dropdown = true) {
            scope.launch {
                offered = sources()
                open = true
            }
        }
        ScaledDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            offered.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label) },
                    leadingIcon = { Icon(source.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        onPick(source)
                    },
                    modifier = Modifier.testTag(source.tag),
                )
            }
        }
    }
}

private val PictureSource.label: String get() = when (this) {
    PictureSource.Device -> Strings.ribbon_picture_device
    PictureSource.Online -> Strings.ribbon_picture_online
    PictureSource.Camera -> Strings.ribbon_picture_camera
}

private val PictureSource.icon: ImageVector get() = when (this) {
    PictureSource.Device -> DocumentSymbols.PhotoLibrary
    PictureSource.Online -> DocumentSymbols.ImageSearch
    PictureSource.Camera -> DocumentSymbols.PhotoCamera
}

private val PictureSource.tag: String get() = when (this) {
    PictureSource.Device -> DocumentRibbonTags.PictureDevice
    PictureSource.Online -> DocumentRibbonTags.PictureOnline
    PictureSource.Camera -> DocumentRibbonTags.PictureCamera
}

/**
 * A window the Picture menu opens over the workspace. Each ends, when it ends well, with the bytes of
 * one picture for [onInsert]; the page it lands on is decided then, not when the window opened.
 */
internal sealed interface PictureDialog {
    val onInsert: (ByteArray) -> Unit

    class Online(val images: OnlineImages, override val onInsert: (ByteArray) -> Unit) : PictureDialog

    class Camera(val camera: PictureCamera, override val onInsert: (ByteArray) -> Unit) : PictureDialog
}

/** Shows [dialog] over the workspace window, as the link editor is shown. */
@Composable
internal fun PictureDialogHost(dialog: PictureDialog, onDismiss: () -> Unit) {
    when (dialog) {
        is PictureDialog.Online -> OnlineImageDialog(dialog.images, dialog.onInsert, onDismiss)
        is PictureDialog.Camera -> CameraDialog(dialog.camera, dialog.onInsert, onDismiss)
    }
}
