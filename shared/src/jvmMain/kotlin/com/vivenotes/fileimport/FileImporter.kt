// Ported from viveNotes Android fileimport/FileImporter.kt. A file path from the desktop's file
// chooser replaces the content Uri, Skia bitmaps android.graphics.Bitmap, and the shared types are in
// commonMain's FileImport.kt.
package com.vivenotes.fileimport

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.fileimport.doc.CompoundFile
import com.vivenotes.fileimport.doc.DocReader
import com.vivenotes.fileimport.docx.DocxPackage
import com.vivenotes.fileimport.docx.DocxPages
import com.vivenotes.fileimport.odt.OdtPackage
import com.vivenotes.fileimport.odt.OdtReader
import com.vivenotes.fileimport.pdf.PdfPages
import com.vivenotes.fileimport.pptx.PptxPages
import com.vivenotes.model.Mark
import com.vivenotes.richtext.CanvasPasteItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.floor

/**
 * A document open in the importer, whatever its format: the pages it has, a way to draw one, and
 * what they hold as canvas objects. Used from one coroutine at a time.
 */
internal interface ImportedDocument : Closeable {
    val pages: List<ImportedPageSize>

    fun render(index: Int, widthPx: Int): Bitmap?

    suspend fun items(pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem>
}

/**
 * Brings other apps' documents in — the file half of the File tab's Import.
 *
 * Shaped like `PdfExporter` for the same reason: the dialog needs its three steps separately — [open]
 * says how many pages there are, [preview] draws one, and the insert asks for [pagePicture] or
 * [objects]. A document is held open between them, so one job at a time, and [release] when the window
 * closes lets go of its pictures and its staged copy.
 *
 * The rest of the app only sees pages, so another format is another thing this opens.
 *
 * [cacheDirectory] is where a picked file is staged; [chooseFile] is the platform's file chooser.
 */
class FileImporter(
    private val cacheDirectory: File,
    private val chooseFile: suspend () -> String? = { null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: DebugLog = DebugLog(),
) : FileImportService() {
    private val mutex = Mutex()

    private var open: OpenDocument? = null

    /** Set when the window closes mid-render, so the job in flight shuts the document on its way out. */
    @Volatile
    private var releaseRequested = false

    private class OpenDocument(val pages: ImportedDocument, val staged: File?, val token: Any = Any())

    private val stagingDirectory: File
        get() = File(cacheDirectory, "file-import").apply { mkdirs() }

    init {
        // PDFBox remembers the system's fonts in a cache file, by default in the user's home.
        if (System.getProperty(PDFBOX_FONT_CACHE) == null) {
            System.setProperty(PDFBOX_FONT_CACHE, File(cacheDirectory, "pdfbox").apply { mkdirs() }.path)
        }
        // Equations are drawn through RaTeX's Compose renderer, as the PDF export draws them, and a
        // document can be read before any window has registered Compose's graphics.
        @OptIn(InternalComposeUiApi::class)
        registerSkikoComposeImplementation()
    }

    override suspend fun chooseSource(): String? = chooseFile()

    /**
     * Reads the document at [source].
     *
     * Copied in first, as Android copies a Storage Access Framework stream: nothing after this line
     * depends on the file staying where it was, a portal document is read through once, and the
     * user's own file is not held open — Windows would refuse to save it in Word meanwhile.
     */
    override suspend fun open(source: String): ImportedFile = withContext(dispatcher) {
        mutex.withLock {
            closeOpen()
            // Whatever a killed process left here is nobody's; only one document is ever open.
            stagingDirectory.listFiles()?.forEach(File::delete)
            val staged = File(stagingDirectory, UUID.randomUUID().toString())
            try {
                val file = File(source)
                if (!file.isFile) throw ImportException(ImportFailure.Unreadable)
                file.inputStream().use { copyBounded(it, staged) }
                openStaged(staged, file.name).also { abandonedWhileOpening() }
            } catch (failure: Throwable) {
                staged.delete()
                throw translated(failure)
            }
        }
    }

    private suspend fun abandonedWhileOpening() {
        if (releaseRequested || !currentCoroutineContext().isActive) {
            closeOpen()
            throw CancellationException("The import window closed while the file was being read")
        }
    }

    /** The test seam: opens a file already on disk, which the importer does not own. */
    internal suspend fun openFile(file: File, name: String): ImportedFile = withContext(dispatcher) {
        mutex.withLock {
            closeOpen()
            try {
                remember(read(file), null, name)
            } catch (failure: Throwable) {
                throw translated(failure)
            }
        }
    }

    private suspend fun openStaged(staged: File, name: String): ImportedFile =
        remember(read(staged), staged, name)

    /** By what the file is rather than what it is called, which is a provider's to get wrong. */
    private suspend fun read(file: File): ImportedDocument = when {
        // Exact signatures first: a PDF's marker may sit anywhere in its opening kilobyte.
        CompoundFile.isCompoundFile(file) -> readCompound(CompoundFile.open(file))
        PdfPages.isPdf(file) -> PdfPages.open(file)
        OdtPackage.isOdt(file) -> OdtPackage.open(file).closedOnFailure { odt ->
            val read = OdtReader(odt).read()
            DocxPages.of(read.document, { odt.bytes(it) }, read.fontFamilies, odt)
        }
        DocxPackage.isPresentation(file) ->
            DocxPackage.open(file, DocxPackage.PRESENTATION_CONTENT_TYPES).closedOnFailure { PptxPages.open(it) }
        else -> DocxPackage.open(file).closedOnFailure { DocxPages.open(it) }
    }

    private inline fun <C : Closeable, T> C.closedOnFailure(read: (C) -> T): T = try {
        read(this)
    } catch (failure: Throwable) {
        runCatching { close() }
        throw failure
    }

    /** One container, three things it can be: an old Word file, an encrypted new one, or neither. */
    private suspend fun readCompound(compound: CompoundFile): ImportedDocument = when {
        compound.has(CompoundFile.ENCRYPTED_PACKAGE) -> throw ImportException(ImportFailure.PasswordProtected)
        compound.has(CompoundFile.WORD_DOCUMENT) -> {
            val read = DocReader(compound).read()
            DocxPages.of(read.document, read.pictures::get, read.fontFamilies, Closeable {})
        }
        else -> throw ImportException(ImportFailure.NotWord)
    }

    private fun remember(pages: ImportedDocument, staged: File?, name: String): ImportedFile {
        val document = OpenDocument(pages, staged)
        open = document
        log.event("import") { "opened ${pages::class.simpleName}: ${pages.pages.size} pages" }
        return ImportedFile(
            name = name,
            pages = pages.pages,
            token = document.token,
        )
    }

    /** The file's [ImportFailure], or [ImportFailure.Unreadable] for the many ways bad input can blow up. */
    private fun translated(failure: Throwable): Throwable {
        val result = when (failure) {
            is ImportException, is CancellationException -> failure
            is OutOfMemoryError -> ImportException(ImportFailure.TooLarge, failure)
            is StackOverflowError, is Exception -> ImportException(ImportFailure.Unreadable, failure)
            else -> failure
        }
        if (result is ImportException) log.event("import") { "import refused: ${result.failure} (${failure::class.simpleName})" }
        return result
    }

    private fun copyBounded(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > DocxPackage.MAX_FILE_BYTES) throw ImportException(ImportFailure.TooLarge)
                output.write(buffer, 0, read)
            }
        }
    }

    /** Page [index] as a bitmap [widthPx] wide, or null when [file] is no longer the open document. */
    override suspend fun preview(file: ImportedFile, index: Int, widthPx: Int): ImageBitmap? = job {
        pagesOf(file)?.render(index, widthPx)?.asComposeImageBitmap()
    }

    /** The test seam: [preview]'s bitmap itself, for reading its pixels. */
    internal suspend fun previewBitmap(file: ImportedFile, index: Int, widthPx: Int): Bitmap? = job {
        pagesOf(file)?.render(index, widthPx)
    }

    private suspend fun <T> job(action: suspend () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try {
                action()
            } catch (failure: Throwable) {
                throw translated(failure)
            } finally {
                if (releaseRequested) closeOpen()
            }
        }
    }

    /**
     * Page [index] as a picture to keep: as sharp as the attachment store will store, sized so its
     * longest side is exactly that, since anything sharper is only thrown away again on the way in.
     */
    override suspend fun pagePicture(file: ImportedFile, index: Int): ImageBitmap? = job {
        file.pages.getOrNull(index)?.let { page ->
            // Rounded down, where Android rounds: rounding up made a Letter page 1583 × 2049, a pixel
            // past what the store keeps and what a `.vive` file may hold.
            val width = floor(PICTURE_LONGEST_PX * page.widthDp / maxOf(page.widthDp, page.heightDp)).toInt()
            pagesOf(file)?.render(index, width.coerceAtLeast(1))?.asComposeImageBitmap()
        }
    }

    override fun recycle(picture: ImageBitmap) {
        picture.asSkiaBitmap().close()
    }

    /** The chosen pages as canvas objects, or an empty list when [file] is no longer open. */
    override suspend fun objects(file: ImportedFile, pages: List<Int>, marks: Set<Mark>): List<CanvasPasteItem> = job {
        pagesOf(file)?.items(pages, marks).orEmpty().also { items ->
            log.event("import") { "read ${pages.size} pages as ${items.size} objects" }
        }
    }

    /** Hands [action] the open document's pages, for the layout tests. */
    internal suspend fun <T> withPages(file: ImportedFile, action: (DocxPages) -> T): T? = job {
        (pagesOf(file) as? DocxPages)?.let(action)
    }

    /** Closes the document. Called when the window closes. */
    override fun release() {
        // Not waited for, because this runs on the main thread as the window goes. A job in flight
        // sees the flag and closes the document itself.
        releaseRequested = true
        if (mutex.tryLock()) {
            try {
                closeOpen()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun pagesOf(file: ImportedFile): ImportedDocument? = open?.takeIf { it.token === file.token }?.pages

    private fun closeOpen() {
        releaseRequested = false
        open?.let { document ->
            runCatching { document.pages.close() }
            document.staged?.delete()
        }
        open = null
    }

    companion object {
        /** The longest side a page picture is rendered at, which is what the store keeps. */
        const val PICTURE_LONGEST_PX = 2048

        private const val PDFBOX_FONT_CACHE = "pdfbox.fontcache"
    }
}

/** A page PDFBox drew, as the Skia bitmap the rest of the importer passes around. */
internal fun BufferedImage.toSkiaBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    // Skia's BGRA in memory, which is what a little-endian ARGB int already is byte by byte.
    val bytes = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { index, argb ->
        val at = index * 4
        bytes[at] = (argb and 0xFF).toByte()
        bytes[at + 1] = ((argb shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((argb shr 16) and 0xFF).toByte()
        bytes[at + 3] = (argb ushr 24).toByte()
    }
    val bitmap = Bitmap()
    // Copied into memory the bitmap owns.
    bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, width * 4)
    bitmap.setImmutable()
    return bitmap
}
