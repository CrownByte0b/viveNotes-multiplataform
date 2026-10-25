package com.vivenotes.ui.drop

import com.vivenotes.data.ImportedPicture
import com.vivenotes.data.PictureFileExtensions
import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog

/**
 * Pictures dropped on the page: stored like a picture chosen with the Picture button, then put on
 * the page where they were dropped by [place] — `WorkspaceState.dropPictures`, all in one undo step.
 * A file that cannot be read or is not a picture after all is left out; the others still go in.
 */
internal class PictureDrop(
    private val library: PictureLibrary,
    private val place: (DropPoint, List<ImportedPicture>) -> Unit,
    private val log: DebugLog = DebugLog(),
) : FileDropHandler {

    override fun accepts(file: DroppedFile): Boolean = file.extension in PictureFileExtensions

    override suspend fun receive(files: List<DroppedFile>, at: DropPoint) {
        val pictures = files.mapNotNull { file ->
            val bytes = file.read(MAX_PICTURE_BYTES)
            if (bytes == null) {
                log.event("drop") { "a dropped .${file.extension} file could not be read" }
                return@mapNotNull null
            }
            library.import(bytes).also { picture ->
                if (picture == null) log.event("drop") { "a dropped .${file.extension} file of ${bytes.size} bytes is not a picture" }
            }
        }
        if (pictures.isNotEmpty()) place(at, pictures)
        log.event("drop") { "inserted ${pictures.size} of ${files.size} dropped pictures" }
    }

    companion object {
        /** Far beyond any photo; a larger file would only be decoded to be shrunk to 2048 px. */
        const val MAX_PICTURE_BYTES = 64L * 1024 * 1024
    }
}
