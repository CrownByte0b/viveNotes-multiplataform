// Ported from viveNotes Android ui/NotesViewModel.kt: insertImportedPages and its helpers, over the
// desktop workspace's state instead of the ViewModel's.
package com.vivenotes.ui.ribbon.file

import com.vivenotes.data.PictureLibrary
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.fileimport.FileImportMode
import com.vivenotes.fileimport.FileImportService
import com.vivenotes.fileimport.ImportedFile
import com.vivenotes.model.Outline
import com.vivenotes.model.newId
import com.vivenotes.richtext.CanvasPasteItem
import com.vivenotes.ui.canvas.contentEdge
import com.vivenotes.ui.shell.CanvasPlacement
import com.vivenotes.ui.shell.CanvasScrollTarget
import com.vivenotes.workspace.PasteFrames
import com.vivenotes.workspace.WorkspaceState
import com.vivenotes.workspace.documentHeight
import com.vivenotes.workspace.isUnwritten
import com.vivenotes.workspace.stackPastedOutlines
import com.vivenotes.workspace.titleFloor

/**
 * Puts the pages of an open document on the canvas, under whatever is already there, as one step of
 * Undo. A second document therefore follows the first instead of landing on top of it.
 *
 * From here it is the road a paste takes: the pieces are stacked by the heights the canvas measures
 * and placed by [WorkspaceState.insertClipboardOutlines]. Pictures nothing placed are left to
 * `AttachmentSweep`, as a paste's are on desktop.
 */
internal class ImportedPages(
    private val state: () -> WorkspaceState,
    private val change: ((WorkspaceState) -> WorkspaceState) -> Unit,
    private val pictures: PictureLibrary?,
    private val placement: CanvasPlacement,
    private val log: DebugLog = DebugLog(),
) {
    /**
     * Puts [pages] of [file] on the open page. False when nothing landed — the page changed while the
     * document was being read, or there was nothing on the chosen pages to place.
     */
    suspend fun insert(importer: FileImportService, file: ImportedFile, pages: List<Int>, mode: FileImportMode): Boolean {
        val pageId = state().selectedPage?.takeIf { it.editable }?.id ?: return false
        val outlines = when (mode) {
            FileImportMode.Objects -> importedObjects(importer, file, pages)
            FileImportMode.Pictures -> importedPagePictures(importer, file, pages)
        }
        if (outlines.isEmpty()) {
            log.event("import") { "nothing to place from ${pages.size} pages" }
            return false
        }
        // Asked even of a single object, because the same answer says where the page's content
        // ends. Given longer than a paste: a long document is many text boxes to measure, and the
        // estimate this falls back on would stack them on top of one another.
        val layout = laidOut(outlines, PASTE_LAYOUT_TIMEOUT_MS + outlines.size * MEASURE_ALLOWANCE_MS)
        val top = importTop(layout.contentBottom)
        var landed = false
        change { current ->
            val next = current.insertClipboardOutlines(pageId, layout.outlines, placement.visibleOrigin().x + INSERT_MARGIN, top)
            landed = next !== current
            next
        }
        if (landed) placement.scrollTo(CanvasScrollTarget(pageId, top))
        log.event("import") { "${if (landed) "placed" else "could not place"} ${outlines.size} objects as $mode" }
        return landed
    }

    /** Objects stacked for an import, and where the canvas said the page's content ends. */
    private class StackedPaste(val outlines: List<Outline>, val contentBottom: Float?)

    /**
     * [outlines] stacked by the heights the canvas measures them at. Should no canvas answer — the
     * editor left the screen mid-import — the document's own sizes stand in.
     */
    private suspend fun laidOut(outlines: List<Outline>, timeoutMs: Long): StackedPaste {
        val frames = placement.measure(outlines, timeoutMs)
            ?: PasteFrames(outlines.associate { it.id to it.documentHeight() })
        return StackedPaste(stackPastedOutlines(outlines, frames.heights), frames.contentBottom)
    }

    /**
     * The height an imported document starts at: a margin under the page's last content, or on a page
     * with nothing on it, where the user is looking.
     *
     * [contentBottom] is the canvas's own figure, which knows how its text wrapped. A page no canvas
     * answered for gets the document's estimate; better a rough place below the content than on top.
     * A text box nobody wrote in is a place for a caret, not content: Android never saves one.
     */
    private fun importTop(contentBottom: Float?): Float {
        val page = state().selectedPage?.takeIf { it.editable }
        val written = page?.document?.outlines.orEmpty().filterNot { it is Outline.Text && it.isUnwritten() }
        val ink = page?.ink?.takeIf { it.strokes.isNotEmpty() }
        if (page != null && written.isEmpty() && ink == null) {
            val titleFloor = page.document.style.titleFloor
            return maxOf(placement.visibleOrigin().y + INSERT_MARGIN, titleFloor)
        }
        val bottom = contentBottom ?: maxOf(
            written.maxOfOrNull { it.y + it.documentHeight() } ?: 0f,
            ink?.contentEdge()?.y ?: 0f,
        )
        return bottom + INSERT_MARGIN
    }

    private suspend fun importedObjects(
        importer: FileImportService,
        file: ImportedFile,
        pages: List<Int>,
    ): List<Outline> = importer.objects(file, pages, state().editorDefaults.textMarks()).mapNotNull { item ->
        when (item) {
            is CanvasPasteItem.Text -> Outline.Text.empty().copy(blocks = item.blocks)
            is CanvasPasteItem.Table -> item.outline
            is CanvasPasteItem.Image -> {
                val stored = item.bytes?.let { pictures?.import(it) } ?: return@mapNotNull null
                val width = item.widthDp ?: Outline.Image.DEFAULT_WIDTH
                Outline.Image(
                    id = newId(),
                    attachmentId = stored.attachmentId,
                    width = width,
                    height = item.heightDp ?: (width * stored.pixelHeight / stored.pixelWidth.coerceAtLeast(1))
                        .coerceAtLeast(Outline.Image.MIN_SIZE),
                )
            }
        }
    }

    private suspend fun importedPagePictures(
        importer: FileImportService,
        file: ImportedFile,
        pages: List<Int>,
    ): List<Outline> = pages.sorted().mapNotNull { index ->
        val size = file.pages.getOrNull(index) ?: return@mapNotNull null
        val bitmap = importer.pagePicture(file, index) ?: return@mapNotNull null
        val stored = try {
            pictures?.importDrawn(bitmap)
        } finally {
            importer.recycle(bitmap)
        } ?: return@mapNotNull null
        val width = pagePictureWidth(size.widthDp)
        Outline.Image(
            id = newId(),
            attachmentId = stored.attachmentId,
            width = width,
            height = width * size.heightDp / size.widthDp.coerceAtLeast(1f),
        )
    }

    /**
     * How wide a page of a document lands: across the view, up to its real size, and never so small
     * that it is narrower than a text box. Real size is what an A4 page is at 100%, and a picture of a
     * page bigger than that would only be a blurrier one.
     */
    private fun pagePictureWidth(pageWidthDp: Float): Float {
        val room = placement.visibleWidth() - INSERT_MARGIN * 2
        return minOf(pageWidthDp, maxOf(Outline.Text.DEFAULT_WIDTH, room))
    }

    companion object {
        /** Android's `INSERT_MARGIN`: a picture's distance from the view's corner, and from what is above. */
        internal const val INSERT_MARGIN = 24f

        /** How long the import waits for the canvas to measure what it brings. */
        internal const val PASTE_LAYOUT_TIMEOUT_MS = 2_000L

        /** A generous per-object price for measuring text the canvas has not drawn yet. */
        internal const val MEASURE_ALLOWANCE_MS = 40L
    }
}
