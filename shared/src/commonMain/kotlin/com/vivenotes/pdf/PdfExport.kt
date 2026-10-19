package com.vivenotes.pdf

import com.vivenotes.localization.Strings
import androidx.compose.ui.graphics.ImageBitmap
import com.vivenotes.model.*

// Export models copied from the Android PdfExporter.
/** Whether the export covers the open page or every page of its section. */
enum class PdfExportScope { Page, Section }

/**
 * Everything the dialog can change.
 *
 * [fitContent] defaults on: content that straddles a page boundary is kept whole rather than cut in
 * half. [includeRuling] defaults on because the ruling lives in the document rather than in
 * preferences — a page written on squared paper is squared paper — and one tap says otherwise.
 *
 * [margins] open on the page's own and can then be set here, because the margin is the one paper
 * setting whose right value is a property of the printer rather than of the document. The cuts are
 * made inside them, so widening them is not a border — it is less page per sheet.
 */
data class PdfExportOptions(
    val scope: PdfExportScope = PdfExportScope.Page,
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.Portrait,
    val margins: PrintMargins = PrintMargins(),
    val fitContent: Boolean = true,
    val includeRuling: Boolean = true,
) {
    companion object {
        /**
         * What the dialog offers: nothing at all, up to an inch.
         *
         * Narrower than [PrintMargins.MAX_INCHES], which has to reach four so that the Paper Size
         * pane can describe an oddly bound page. Nobody prints a note with a four-inch margin, and
         * a field that will accept one is a field that will accept a typo.
         */
        val MARGIN_RANGE: ClosedFloatingPointRange<Float> = 0f..1f
    }
}

/** What the export covers, resolved to page ids before anything is measured. */
data class PdfExportRequest(
    val pageId: String?,
    val sectionId: String?,
    val options: PdfExportOptions,
    /** The page's title or the section's name, whichever the scope names. Only the file name uses it. */
    val name: String? = null,
)

/** One note page's contribution: the sheets it was cut into, and how to find it again. */
class PlannedPage(
    val pageId: String,
    val title: String,
    val plan: PdfPagePlan,
)

/** Which sheet of which page, in the order they are written. */
data class PdfSheetRef(val pageIndex: Int, val tileIndex: Int)

/**
 * A complete export, planned but not drawn.
 *
 * **Deliberately holds no page content.** A section's documents and — far more to the point — its
 * ink meshes are native-backed and there can be a great many of them; keeping fifty pages of them
 * alive to render one preview is how an export runs a tablet out of memory. What is kept is the
 * tiling, which is a handful of rectangles per page, and the exporter loads a page again when it is
 * actually asked to draw it. [PdfExporter] caches the last page it measured, so walking the sheets
 * in order — which is what both the preview and the write do — measures each page once.
 */
class PdfExportPlan(
    val options: PdfExportOptions,
    val paper: PdfPaper,
    val pages: List<PlannedPage>,
    val sheets: List<PdfSheetRef>,
    /** What the file should be called, before the user renames it in the picker. */
    val suggestedName: String,
) {
    val sheetCount: Int get() = sheets.size
}


/** Platform rendering and the native destination picker behind the shared File UI. */
interface PdfExportService {
    suspend fun defaultOptionsFor(pageId: String?): PdfExportOptions
    suspend fun plan(request: PdfExportRequest): PdfExportPlan
    suspend fun preview(plan: PdfExportPlan, sheetIndex: Int, maxWidthPx: Int): ImageBitmap?
    suspend fun chooseDestination(suggestedName: String): String?
    suspend fun write(plan: PdfExportPlan, destination: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int
    suspend fun release()
}

/** Android's page-space rectangle, without a native ink dependency in common code. */
data class PdfBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun translated(dx: Float, dy: Float) = PdfBounds(left + dx, top + dy, right + dx, bottom + dy)
}

class PdfExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A title is whatever the writer typed, and a file name is not. */
internal fun String.sanitisedForFileName(): String =
    trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-").take(80).ifBlank { Strings.notes }
