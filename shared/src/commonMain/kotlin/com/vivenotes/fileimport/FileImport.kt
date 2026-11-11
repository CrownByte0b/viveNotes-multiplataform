// Ported from viveNotes Android fileimport/FileImporter.kt: the types the import window shares with
// the importer. The importer itself is JVM code (`jvmMain/.../fileimport/FileImporter.kt`).
package com.vivenotes.fileimport

import androidx.compose.ui.graphics.ImageBitmap
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem

/** How the chosen pages land on the canvas. */
enum class FileImportMode { Objects, Pictures }

class ImportedPageSize(val widthDp: Float, val heightDp: Float)

/** Why a file could not be opened; the window turns each into its own sentence. */
enum class ImportFailure {
    /** Not something this reads at all, whatever its name says. */
    NotWord,

    /** Encrypted, in any of the formats that can be. */
    PasswordProtected,

    /** Past what this importer will read from an untrusted file. */
    TooLarge,

    /** The right kind of file, broken inside. */
    Unreadable,
}

class ImportException(val failure: ImportFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/** A document open in the importer: its name and the size of each page. */
class ImportedFile internal constructor(
    val name: String,
    val pages: List<ImportedPageSize>,
    internal val token: Any,
)

/**
 * The File tab's Import ▸ File, as the shared window sees it: Android's `FileImporter`, whose
 * platform half — reading the formats, drawing pages, the native file chooser — is the JVM's.
 *
 * An abstract class rather than an interface only so that [objects] can stay `internal`, as
 * [CanvasPasteItem] is on Android.
 */
abstract class FileImportService {
    /** The platform's file chooser: the chosen file's path, or null when the user cancels. */
    abstract suspend fun chooseSource(): String?

    /** Reads the document at [source]; throws an [ImportException] saying why it cannot. */
    abstract suspend fun open(source: String): ImportedFile

    /** Page [index] as a picture [widthPx] wide, or null when [file] is no longer the open document. */
    abstract suspend fun preview(file: ImportedFile, index: Int, widthPx: Int): ImageBitmap?

    /** Page [index] as a picture to keep, as sharp as the attachment store keeps pictures. */
    abstract suspend fun pagePicture(file: ImportedFile, index: Int): ImageBitmap?

    /**
     * Lets go of a [pagePicture] once it is stored, as Android recycles the bitmap: a page's pixels
     * are native memory, which a long import would otherwise hold until the collector runs.
     */
    open fun recycle(picture: ImageBitmap) = Unit

    /** The chosen pages as canvas objects, or an empty list when [file] is no longer open. */
    internal abstract suspend fun objects(file: ImportedFile, pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem>

    /** Closes the document. Called when the window closes. */
    abstract fun release()

    companion object {
        /** What the file chooser offers: Android's MIME types, as the file names that carry them. */
        val FILE_EXTENSIONS: List<String> = listOf(
            "docx", "dotx", "docm", "dotm", "doc", "dot", "odt", "ott", "pptx", "ppsx", "potx", "pptm", "ppsm", "potm", "pdf",
        )
    }
}
