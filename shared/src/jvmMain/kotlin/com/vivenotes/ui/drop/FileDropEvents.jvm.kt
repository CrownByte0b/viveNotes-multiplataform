package com.vivenotes.ui.drop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.InvalidDnDOperationException
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

internal actual fun DragAndDropEvent.carriesFiles(): Boolean = transferable()?.carriesFiles() == true

internal actual fun DragAndDropEvent.droppedFiles(): List<DroppedFile> =
    transferable()?.files().orEmpty().map { droppedFile(it) }

/**
 * The AWT event's point, which is in the drop target's — Compose's root container's — own units,
 * scaled to pixels the way Compose scales it for the root: by the screen's scale.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual val DragAndDropEvent.rootPosition: Offset get() {
    val event = nativeEvent as? DropTargetEvent ?: return Offset.Unspecified
    val location = when (event) {
        is DropTargetDropEvent -> event.location
        is DropTargetDragEvent -> event.location
        else -> return Offset.Unspecified
    }
    val scale = event.dropTargetContext.component?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    return Offset(location.x * scale, location.y * scale)
}

/** AWT's data for this event; none once the drag has gone, when AWT refuses to hand it over. */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.transferable(): Transferable? = try {
    awtTransferable
} catch (e: InvalidDnDOperationException) {
    null
}

/** RFC 2483's list of addresses, which file managers drag; AWT usually also offers it as a file list. */
private val UriListFlavor = DataFlavor("text/uri-list;class=" + String::class.java.name)

internal fun Transferable.carriesFiles(): Boolean =
    isDataFlavorSupported(DataFlavor.javaFileListFlavor) || isDataFlavorSupported(UriListFlavor)

/**
 * The files this transferable holds, in its order: AWT's file list where it gives one, else the
 * `file:` addresses of its URI list. Folders and anything that is not a file on this computer are
 * left out. Only a drop hands over its data; during the drag this is empty.
 */
internal fun Transferable.files(): List<File> {
    val listed = read(DataFlavor.javaFileListFlavor) { (it as? List<*>)?.filterIsInstance<File>() }
        ?: read(UriListFlavor) { (it as? String)?.let(::uriListFiles) }
        ?: emptyList()
    return listed.filter { it.isFile }
}

private fun <T> Transferable.read(flavor: DataFlavor, convert: (Any?) -> T?): T? {
    if (!isDataFlavorSupported(flavor)) return null
    return try {
        convert(getTransferData(flavor))
    } catch (e: UnsupportedFlavorException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: InvalidDnDOperationException) {
        null
    }
}

/** The local files named by a `text/uri-list`: one address a line, `#` lines being comments. */
internal fun uriListFiles(text: String): List<File> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        try {
            val uri = URI(line)
            val local = uri.scheme.equals("file", ignoreCase = true) && (uri.host.isNullOrEmpty() || uri.host == "localhost")
            // Rebuilt without the host, which File refuses, so Windows drive paths come out right too.
            if (local && !uri.path.isNullOrEmpty()) File(URI("file", null, uri.path, null)) else null
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    .toList()

/** [file] as a [DroppedFile], read on [io]. */
internal fun droppedFile(file: File, io: CoroutineDispatcher = Dispatchers.IO): DroppedFile =
    DroppedFile(file.name) { maxBytes -> withContext(io) { readAtMost(file, maxBytes) } }

/** [file]'s bytes, or null when it cannot be read or is longer than [maxBytes]. */
internal fun readAtMost(file: File, maxBytes: Long): ByteArray? = try {
    file.inputStream().use { input ->
        // One byte more than allowed tells a file of exactly maxBytes from a longer one.
        val bytes = input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE - 8L).toInt())
        bytes.takeIf { it.size <= maxBytes }
    }
} catch (e: IOException) {
    null
}
