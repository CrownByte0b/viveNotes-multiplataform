package com.vivenotes.data

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

/** A camera this computer has, by the name its driver gives it. */
data class CameraDevice(val id: String, val name: String)

/** One picture from a camera: what the dialog shows, and the photo it becomes when taken. */
class CameraFrame(val preview: ImageBitmap, private val encode: () -> ByteArray?) {
    /** The frame as an encoded picture for the attachment store; null when it cannot be encoded. */
    fun photo(): ByteArray? = encode()
}

/** Why a camera could not be used. */
class CameraException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** Another program has it. */
        Busy,

        /** It could not be opened, or offers no picture format the app reads. */
        Failed,

        /** It stopped sending pictures — unplugged, most likely. */
        Stopped,
    }
}

/**
 * The Picture menu's "Camera" on a computer.
 *
 * Android lends a file to the system camera app and inserts what it writes there. A desktop has no
 * camera app to lend it to, so the app shows the camera's picture itself and the photo is the frame
 * on screen when the shutter is pressed.
 */
interface PictureCamera {
    /**
     * The cameras this computer has now, best first. Empty is what keeps "Camera" out of the menu,
     * as Android leaves it out on a device without `FEATURE_CAMERA_ANY`.
     */
    suspend fun cameras(): List<CameraDevice>

    /** [device]'s picture, a frame at a time, until the collector stops. Fails with [CameraException]. */
    fun frames(device: CameraDevice): Flow<CameraFrame>
}
