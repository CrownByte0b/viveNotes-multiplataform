package com.vivenotes.desktop.camera

import com.vivenotes.data.CameraDevice
import com.vivenotes.data.CameraException
import com.vivenotes.data.CameraFrame
import com.vivenotes.data.PictureCamera
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The camera on desktop, or null where there is no way to drive one: on Linux the webcams Video4Linux2
 * offers. Windows has no backend yet, so its Picture menu leaves Camera out, as Android's does on a
 * device without one.
 */
internal fun desktopCamera(log: DebugLog = DebugLog()): PictureCamera? =
    if (V4l2.supported) LinuxCamera(log = log) else null

/**
 * Webcams through Video4Linux2, read directly: no camera app, portal or library between, and no
 * permission to ask for — the desktop session's user already owns its camera's device node.
 *
 * A node counts when it captures, streams and sends JPEG or YUYV frames. That leaves out a camera's
 * metadata node, infrared face-unlock sensors, and the raw sensors of cameras that need libcamera.
 */
internal class LinuxCamera(
    private val devices: File = File("/dev"),
    private val log: DebugLog = DebugLog(),
) : PictureCamera {

    override suspend fun cameras(): List<CameraDevice> = withContext(Dispatchers.IO) {
        val found = devices.listFiles { file -> VIDEO_NODE.matches(file.name) }.orEmpty()
            .sortedBy { it.name.removePrefix("video").toInt() }
            .mapNotNull { node ->
                runCatching { V4l2Device.open(node.path).use { it.describe() } }.getOrNull()
            }
            // One camera, one entry: a camera with two capturing nodes is still one camera.
            .distinctBy { it.busInfo.ifEmpty { it.path } }
        log.event("camera") { "found ${found.size} camera(s)" }
        found.map { CameraDevice(it.path, it.name.ifBlank { it.path }) }
    }

    /**
     * Streams [device] on an IO thread. Every failure arrives as a [CameraException], so the window
     * can say what happened; frames the window has not drawn yet are dropped for newer ones.
     */
    override fun frames(device: CameraDevice): Flow<CameraFrame> = flow {
        try {
            var attempt = 0
            while (true) {
                try {
                    V4l2Device.open(device.id).use { camera -> stream(camera) }
                } catch (e: CameraException) {
                    // The window's last stream of this camera may still be letting it go: one poll
                    // interval passes before a cancelled stream notices. Another program's hold lasts.
                    if (e.reason != CameraException.Reason.Busy || ++attempt > BUSY_RETRIES) throw e
                    delay(POLL_MILLIS.toLong())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CameraException) {
            log.event("camera") { "stopped: ${e.reason}" }
            throw e
        } catch (e: Exception) {
            log.failure("camera", "stream", e)
            throw CameraException(CameraException.Reason.Failed, e)
        }
    }.flowOn(Dispatchers.IO).conflate()

    private suspend fun FlowCollector<CameraFrame>.stream(camera: V4l2Device) {
        val modes = camera.describe()?.formats?.map(camera::mode)
            ?: throw CameraException(CameraException.Reason.Failed)
        val (format, size) = chooseMode(modes) ?: throw CameraException(CameraException.Reason.Failed)
        val stream = camera.setFormat(format, size)
        camera.startStreaming()
        log.event("camera") { "streaming ${stream.format} ${stream.width}x${stream.height}" }
        val ring = PreviewRing()
        var lastFrame = System.nanoTime()
        while (true) {
            currentCoroutineContext().ensureActive()
            val raw = camera.next(stream, POLL_MILLIS)
            val now = System.nanoTime()
            if (raw == null) {
                if (now - lastFrame > STALL_NANOS) throw CameraException(CameraException.Reason.Stopped)
                continue
            }
            val shown = preview(raw, ring, PREVIEW_WIDTH, PREVIEW_HEIGHT) ?: continue
            lastFrame = now
            emit(CameraFrame(shown) { photo(raw) })
        }
    }

    private companion object {
        val VIDEO_NODE = Regex("""video\d+""")

        /** How long one wait for a frame lasts, and so how soon closing the window stops the camera. */
        const val POLL_MILLIS = 100

        /** Tries past a busy camera before saying so: a second and a half, several poll intervals. */
        const val BUSY_RETRIES = 15

        /** No frame for this long is a camera that has gone, not a slow one. */
        const val STALL_NANOS = 5_000_000_000L

        /** The preview's size: more than the window shows, at the window's largest. */
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
    }
}
