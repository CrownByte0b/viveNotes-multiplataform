// Ported from viveNotes Android pdf/PdfExporter.kt; the platform file and drawing boundaries differ.
package com.vivenotes.pdf

import com.vivenotes.localization.Strings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.vivenotes.data.*
import com.vivenotes.data.db.PageEntity
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.ink.desktopGeometry
import com.vivenotes.model.*
import com.vivenotes.diagnostics.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Turns pages into a PDF.
 *
 * The public shape is three steps, and they are separate because the dialog needs them separately:
 * [plan] answers "how many sheets, and where do they cut", [preview] draws one of them into a
 * bitmap, and [write] draws all of them into a document. All three share one [PageRenderer], so
 * what the preview shows is what the file gets.
 */
class PdfExporter(
    private val repository: NotesRepository,
    private val attachments: AttachmentStore? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val log: DebugLog = DebugLog(),
    private val chooseSave: suspend (String) -> String? = { null },
) : PdfExportService {
    private val measurer = PageMeasurer(attachments, log)
    private val renderer = PageRenderer()

    override suspend fun chooseDestination(suggestedName: String): String? = chooseSave(suggestedName)

    /**
     * One job at a time.
     *
     * [PageRenderer] holds its `Paint`s and its scratch `Matrix` between calls — that is what keeps a
     * hundred-sheet export from allocating a hundred of each — so two renders at once would paint
     * each other's colours. The dialog can ask for one easily enough: a preview render is still in
     * flight when the file picker comes back and the write begins. This is also what makes [cached]
     * safe to hold without a lock of its own.
     */
    private val mutex = Mutex()

    /** The last page measured with its pictures, so consecutive sheets of it are free. */
    private var cached: RenderTarget? = null

    /** What the dialog opens on: the page's own paper, or A4 where it has chosen none. */
    override suspend fun defaultOptionsFor(pageId: String?): PdfExportOptions = withContext(dispatcher) {
        val style = pageId?.let { (repository.loadDoc(it) as? PageLoad.Loaded)?.doc?.style }
            ?: return@withContext PdfExportOptions()
        PdfExportOptions(
            paper = if (style.paper == PaperSize.Auto) PaperSize.A4 else style.paper,
            orientation = style.orientation,
            // Coerced into the dialog's own range, so a page bound with a four-inch margin does not
            // open the field already holding a number it refuses.
            margins = PrintMargins(
                topInches = style.margins.topInches.coerceIn(PdfExportOptions.MARGIN_RANGE),
                bottomInches = style.margins.bottomInches.coerceIn(PdfExportOptions.MARGIN_RANGE),
                leftInches = style.margins.leftInches.coerceIn(PdfExportOptions.MARGIN_RANGE),
                rightInches = style.margins.rightInches.coerceIn(PdfExportOptions.MARGIN_RANGE),
            ),
        )
    }

    override suspend fun plan(request: PdfExportRequest): PdfExportPlan = withContext(dispatcher) {
        mutex.withLock {
            clearCache()
            log.event("pdf") { "planning ${request.options.scope.name.lowercase()}" }
            val sources = sourcesFor(request)
            val paper = paperFor(request.options, sources.firstOrNull()?.style)
            val planned = sources.map { source ->
                coroutineContext.ensureActive()
                val page = measure(source, paper, loadPictures = false, fitContent = request.options.fitContent)
                PlannedPage(
                    pageId = source.id,
                    title = source.title,
                    plan = PageTiling.plan(
                        items = page.items,
                        tileWidthDp = paper.tileWidthDp,
                        tileHeightDp = paper.tileHeightDp,
                        fit = request.options.fitContent,
                        boundSheet = page.boundSheet,
                    ),
                )
            }
            val name = (request.name ?: sources.firstOrNull()?.title).orEmpty()
            PdfExportPlan(
                options = request.options,
                paper = paper,
                pages = planned,
                sheets = planned.flatMapIndexed { pageIndex: Int, page: PlannedPage ->
                    page.plan.tiles.indices.map { PdfSheetRef(pageIndex, it) }
                },
                suggestedName = "${name.sanitisedForFileName()}.pdf",
            ).also { log.event("pdf") { "planned ${it.pages.size} pages into ${it.sheetCount} sheets" } }
        }
    }

    /**
     * One sheet as a bitmap, at most [maxWidthPx] across.
     *
     * The same renderer the file gets, at a different scale — which is the whole reason there is one
     * renderer. Returns null when the sheet index is out of range, which is what a preview asked for
     * a plan that has since been replaced looks like.
     */
    override suspend fun preview(plan: PdfExportPlan, sheetIndex: Int, maxWidthPx: Int): ImageBitmap? =
        withContext(dispatcher) {
            mutex.withLock {
                val ref = plan.sheets.getOrNull(sheetIndex) ?: return@withLock null
                val planned = plan.pages[ref.pageIndex]
                val stored = planned.plan.tiles.getOrNull(ref.tileIndex) ?: return@withLock null
                val target = measureCached(planned.pageId, plan)
                val tile = target.plan.tiles.getOrNull(ref.tileIndex) ?: stored
                val scale = (maxWidthPx / plan.paper.widthDp)
                    .coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
                val width = (plan.paper.widthDp * scale).roundToInt().coerceAtLeast(1)
                val height = (plan.paper.heightDp * scale).roundToInt().coerceAtLeast(1)
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    renderer.drawSheet(
                        canvas = surface.canvas,
                        page = target.page,
                        tile = tile,
                        plan = target.plan,
                        paper = plan.paper,
                        ruling = plan.options.includeRuling,
                        scale = scale,
                    )
                    surface.makeImageSnapshot().toComposeImageBitmap()
                }
            }
        }

    /**
     * Writes the whole export to [destination], reporting each sheet as it is finished.
     *
     * The stream is the user's chosen document — a Storage Access Framework grant for exactly that
     * file, which is the same door Export Notebook goes through and needs no storage permission.
     */
    override suspend fun write(
        plan: PdfExportPlan,
        destination: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(dispatcher) {
        mutex.withLock {
            require(plan.sheetCount > 0) { Strings.nothing_could_be_written_to_the_chosen_file }
            val chosen = File(destination).absoluteFile
            var staging: java.nio.file.Path? = null
            log.event("pdf") { "writing ${plan.sheetCount} sheets" }
            try {
                val staged = Files.createTempFile(chosen.parentFile.toPath(), ".vive-pdf-", ".part")
                staging = staged
                Files.newOutputStream(staged).buffered().use { stream ->
                    PdfVectorDocument(stream, plan.paper.widthPoints, plan.paper.heightPoints).use { document ->
                        plan.sheets.forEachIndexed { index, ref ->
                            coroutineContext.ensureActive()
                            val planned = plan.pages[ref.pageIndex]
                            val target = measureCached(planned.pageId, plan)
                            val tile = target.plan.tiles.getOrNull(ref.tileIndex) ?: planned.plan.tiles[ref.tileIndex]
                            document.sheet { canvas ->
                                renderer.drawSheet(canvas, target.page, tile, target.plan, plan.paper,
                                    plan.options.includeRuling, PdfPaper.POINTS_PER_DP)
                            }
                            onProgress(index + 1, plan.sheetCount)
                        }
                    }
                }
                coroutineContext.ensureActive()
                try {
                    Files.move(staged, chosen.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staged, chosen.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                log.event("pdf") { "wrote ${plan.sheetCount} sheets" }
                plan.sheetCount
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.failure("pdf", "write", error)
                throw error
            } finally {
                staging?.let { Files.deleteIfExists(it) }
                clearCache()
            }
        }
    }

    /** Serialized with rendering so a closing preview cannot free a picture still being drawn. */
    override suspend fun release() = withContext(dispatcher) {
        mutex.withLock { clearCache() }
    }

    private fun clearCache() {
        cached?.page?.close()
        cached = null
        renderer.clearCache()
    }

    // --- loading -----------------------------------------------------------------------------

    private class SourcePage(
        val id: String,
        val title: String,
        val createdAt: Long,
        val doc: PageDoc,
        val strokes: List<PageStroke>,
    ) {
        val style: PageStyle get() = doc.style
    }

    private suspend fun sourcesFor(request: PdfExportRequest): List<SourcePage> {
        val ids = when (request.options.scope) {
            PdfExportScope.Page -> listOfNotNull(request.pageId)
            PdfExportScope.Section -> request.sectionId
                ?.let { repository.pagesInSection(it).map(PageEntity::id) }
                ?: listOfNotNull(request.pageId)
        }
        return ids.mapNotNull { load(it) }
    }

    private suspend fun load(pageId: String): SourcePage? {
        val entity = repository.pageById(pageId) ?: return null
        // An unreadable page exports as the page it can be read as — empty — rather than failing the
        // whole section. Nothing here writes, so nothing can make the damage worse.
        val doc = (repository.loadDoc(pageId) as? PageLoad.Loaded)?.doc?.migrated() ?: PageDoc()
        return SourcePage(
            id = pageId,
            title = entity.title,
            createdAt = entity.createdAt,
            doc = doc,
            strokes = repository.loadInk(pageId).desktopGeometry().projections,
        )
    }

    private suspend fun measure(source: SourcePage, paper: PdfPaper, loadPictures: Boolean, fitContent: Boolean) =
        measurer.measure(
            pageId = source.id,
            title = source.title,
            createdAt = source.createdAt,
            doc = source.doc,
            strokes = source.strokes,
            paper = paper,
            loadPictures = loadPictures,
            fitContent = fitContent,
        )

    /**
     * A page measured with its pictures, and the fit worked out against *that* measurement.
     *
     * [options] and [paper] are what it was measured *for*, and they are half of the cache key. A
     * cache keyed on the page alone is a cache that answers a question nobody asked: toggling Fit
     * content re-plans the export and re-renders the preview, and the render would be handed back
     * the page as it was laid out under the old setting — the sheet count changing while the sheets
     * themselves did not.
     */
    private class RenderTarget(
        val page: MeasuredPage,
        val plan: PdfPagePlan,
        val options: PdfExportOptions,
        val paper: PdfPaper,
    )

    /**
     * The page to draw, and where its parts go.
     *
     * The fit is worked out again here, against the measurement about to be drawn, rather than
     * carried over from [plan]. A page is measured twice in an export — once to decide how many
     * sheets it takes, once per page to draw it — and those are two separate loads of the same ink.
     * Carrying a map keyed by anything those two loads might name differently fails silently: the
     * sheet count is right, the tiles are right, and one formula quietly loses half of itself.
     *
     * The names are stable now (`ContentGroups.inkAtoms` says how), so the two agree — but they
     * agree because the geometry is the same, which is a smaller thing to trust than a naming
     * scheme. The stored tile still decides which sheet this is; only the shifts are local.
     */
    private suspend fun measureCached(pageId: String, exportPlan: PdfExportPlan): RenderTarget {
        cached
            ?.takeIf {
                it.page.pageId == pageId &&
                    it.options == exportPlan.options &&
                    it.paper == exportPlan.paper
            }
            ?.let { return it }
        val source = load(pageId) ?: SourcePage(pageId, "", 0L, PageDoc(), emptyList())
        val page = measure(source, exportPlan.paper, loadPictures = true, fitContent = exportPlan.options.fitContent)
        val target = RenderTarget(
            page = page,
            plan = PageTiling.plan(
                items = page.items,
                tileWidthDp = exportPlan.paper.tileWidthDp,
                tileHeightDp = exportPlan.paper.tileHeightDp,
                fit = exportPlan.options.fitContent,
                boundSheet = page.boundSheet,
            ),
            options = exportPlan.options,
            paper = exportPlan.paper,
        )
        clearCache()
        cached = target
        return target
    }

    /**
     * The sheet the export goes onto.
     *
     * Margins are the dialog's, having opened on the page's own — the band the writer already sees
     * drawn while the Paper Size pane is open. One export is one stack of paper either way: a
     * section takes its first page's, because a stack whose printable area changed halfway down is
     * not one stack.
     */
    private fun paperFor(options: PdfExportOptions, style: PageStyle?): PdfPaper = PdfPaper.of(
        size = options.paper,
        orientation = options.orientation,
        custom = style?.customPaper,
        margins = options.margins,
    )

    private companion object {
        /** Below this a preview is not a preview; above it, it is a second render nobody looks at. */
        const val MIN_PREVIEW_SCALE = 0.2f
        const val MAX_PREVIEW_SCALE = 3f
    }
}

