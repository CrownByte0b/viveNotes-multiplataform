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
    internal val work = PdfWorkCounts().also { measurer.work = it; renderer.work = it }

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
    private val prepared = LinkedHashMap<String, RenderTarget>(4, .75f, true)

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
            val ids = sourceIdsFor(request)
            var paper: PdfPaper? = null
            var firstTitle: String? = null
            val planned = ArrayList<PlannedPage>()
            try {
                for (id in ids) {
                    coroutineContext.ensureActive()
                    val source = loadPdfSource(repository, id) ?: continue
                    if (paper == null) {
                        paper = paperFor(request.options, source.style)
                        firstTitle = source.title
                    }
                    val target = prepare(source, request.options, paper)
                    planned += PlannedPage(source.id, source.title, target.plan)
                    if (!retainPrepared(target)) target.page.close()
                }
            } catch (error: Throwable) {
                clearCache()
                throw error
            }
            val selectedPaper = paper ?: paperFor(request.options, null)
            val name = (request.name ?: firstTitle).orEmpty()
            PdfExportPlan(
                options = request.options,
                paper = selectedPaper,
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
                        var writingTarget: RenderTarget? = null
                        plan.sheets.forEachIndexed { index, ref ->
                            coroutineContext.ensureActive()
                            val planned = plan.pages[ref.pageIndex]
                            val target = writingTarget?.takeIf { it.page.pageId == planned.pageId }
                                ?: measureCached(planned.pageId, plan).also { writingTarget = it }
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
        val current = cached
        prepared.values.forEach { it.page.close() }
        if (current != null && prepared.values.none { it === current }) current.page.close()
        prepared.clear()
        cached = null
        renderer.clearCache()
    }

    // --- loading -----------------------------------------------------------------------------

    private suspend fun sourceIdsFor(request: PdfExportRequest): List<String> =
        when (request.options.scope) {
            PdfExportScope.Page -> listOfNotNull(request.pageId)
            PdfExportScope.Section -> request.sectionId
                ?.let { repository.pagesInSection(it).map(PageEntity::id) }
                ?: listOfNotNull(request.pageId)
        }

    private suspend fun measure(source: PdfSource, paper: PdfPaper, loadPictures: Boolean, fitContent: Boolean) =
        measurer.measure(
            pageId = source.id,
            title = source.title,
            createdAt = source.createdAt,
            doc = source.doc,
            strokes = source.decodedStrokes,
            paper = paper,
            loadPictures = loadPictures,
            fitContent = fitContent,
            loadDrawingAssets = loadPictures,
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
        val identity: PdfSourceIdentity,
        val page: MeasuredPage,
        val plan: PdfPagePlan,
        val options: PdfExportOptions,
        val paper: PdfPaper,
    ) {
        val characters = page.texts.values.sumOf { block ->
            block.layout.paragraphs.sumOf { it.layout.layoutInput.text.length + 1 }
        } + page.tables.values.sumOf { table -> table.cells.values.sumOf { cell ->
            cell.paragraphs.sumOf { it.layout.layoutInput.text.length + 1 }
        } }
        val strokeCount = page.groups.sumOf { it.strokes.size }
    }

    private suspend fun prepare(source: PdfSource, options: PdfExportOptions, paper: PdfPaper): RenderTarget {
        val page = measure(source, paper, loadPictures = false, fitContent = options.fitContent)
        try {
            return RenderTarget(source.identity, page, PageTiling.plan(page.items,
                paper.tileWidthDp, paper.tileHeightDp, options.fitContent, page.boundSheet), options, paper)
        } catch (error: Throwable) {
            page.close()
            throw error
        }
    }

    private fun retainPrepared(target: RenderTarget): Boolean {
        val page = target.page
        if (target.characters > MAX_PREPARED_CHARACTERS || target.strokeCount > MAX_PREPARED_STROKES) return false
        prepared.remove(page.pageId)?.takeUnless { it === target }?.page?.close()
        prepared[page.pageId] = target
        while (prepared.size > MAX_PREPARED_PAGES ||
            prepared.values.sumOf { it.characters } > MAX_PREPARED_CHARACTERS ||
            prepared.values.sumOf { it.strokeCount } > MAX_PREPARED_STROKES) {
            val oldest = prepared.entries.iterator().next()
            prepared.remove(oldest.key)
            oldest.value.page.close()
        }
        work.peakPreparedPages = maxOf(work.peakPreparedPages, prepared.size)
        return prepared[page.pageId] === target
    }

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
        val source = loadPdfSource(repository, pageId) ?: PdfSource(pageId,
            PdfSourceIdentity("", 0L, PageDoc(), "missing"), emptyList(), emptyList(), emptyList())
        cached
            ?.takeIf {
                it.page.pageId == pageId &&
                    it.options == exportPlan.options &&
                    it.paper == exportPlan.paper && it.identity == source.identity
            }
            ?.let { return it }
        val previous = cached
        cached = null
        previous?.page?.releaseDrawingAssets()
        if (previous != null && prepared.values.none { it === previous }) previous.page.close()
        renderer.clearCache()
        val reusable = prepared[pageId]?.takeIf { it.identity == source.identity &&
            it.options == exportPlan.options && it.paper == exportPlan.paper }
        if (reusable == null) prepared.remove(pageId)?.page?.close()
        val target = reusable ?: prepare(source, exportPlan.options, exportPlan.paper)
        if (reusable == null && !retainPrepared(target)) {
            prepared.values.forEach { it.page.close() }
            prepared.clear()
        }
        try {
            measurer.hydrateDrawingAssets(target.page)
            cached = target
            return target
        } catch (error: Throwable) {
            target.page.releaseDrawingAssets()
            if (prepared.values.none { it === target }) target.page.close()
            throw error
        }
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
        const val MAX_PREPARED_PAGES = 2
        const val MAX_PREPARED_CHARACTERS = 250_000
        const val MAX_PREPARED_STROKES = 10_000
    }
}

