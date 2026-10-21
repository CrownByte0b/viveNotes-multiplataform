// Ported from viveNotes Android pdf/PageMeasure.kt. Text, equations and pictures use desktop adapters.
package com.vivenotes.pdf

import com.vivenotes.localization.Strings
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import com.vivenotes.data.AttachmentStore
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.pdf.PdfBounds as InkBounds
import com.vivenotes.model.*
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The colours an exported page is painted with.
 *
 * Always the light canvas, whatever Switch Background is set to: that setting is a preference about
 * the screen somebody is writing on, not a property of the document. A page carrying a background
 * colour of its own keeps it — that is in the document — and [com.vivenotes.ui.theme.paintedWith]
 * re-derives legible ink for it.
 *
 * So ink, shape borders, table rules and equations drawn with the automatic colour all re-resolve
 * against [textArgb] through the `automaticColorOr` path they already use, and a page written in
 * white on a dark canvas exports as black on white paper.
 */
data class PdfCanvasColors(
    val backgroundArgb: Int,
    val ruleLineArgb: Int,
    val textArgb: Int,
    val secondaryTextArgb: Int,
    val accentArgb: Int,
    val codeBackgroundArgb: Int,
) {
    companion object {
        /** The app's light primary — `ui/theme/Theme.kt`. Bullets, numbering and quote rules. */
        private const val ACCENT_ARGB = 0xFF0063C6.toInt()

        fun forPage(style: PageStyle): PdfCanvasColors {
            val canvas = com.vivenotes.ui.shell.canvasPalette(style, canvasDark = false)
            val dark = canvas.background.luminance() < 0.45f
            return PdfCanvasColors(
                backgroundArgb = canvas.background.toArgb(),
                ruleLineArgb = canvas.rule.toArgb(),
                textArgb = canvas.ink.toArgb(),
                secondaryTextArgb = canvas.secondaryInk.toArgb(),
                accentArgb = ACCENT_ARGB,
                // On screen this is white at 7%, which is invisible on paper. Inverted with the
                // canvas, so a code block reads as a block on either.
                codeBackgroundArgb = if (dark) 0x12FFFFFF else 0x14000000,
            )
        }
    }
}

/** One text box, laid out once and then both measured and drawn from. */
class PdfTextBlock(val layout: PdfTextLayout, val leftDp: Float, val topDp: Float)

/** A table's measured geometry: where its grid starts, how tall each row came out, and its cells. */
class PdfTableGrid(
    val leftDp: Float,
    val topDp: Float,
    val rowHeightsDp: FloatArray,
    val cells: Map<String, PdfTextLayout>,
) {
    val rowTopsDp: FloatArray = FloatArray(rowHeightsDp.size + 1).also { tops ->
        rowHeightsDp.forEachIndexed { index, height -> tops[index + 1] = tops[index] + height }
    }
    val heightDp: Float get() = rowTopsDp.last()
    val paintOutset: Float = cells.values.maxOfOrNull { it.paintOutset } ?: 0f
}

/**
 * Everything one page needs to be drawn, measured once.
 *
 * The document does not know how tall a text container or a table is — only a canvas does, which is
 * `PageSpace`'s "a far edge is a number the model does not have". The exporter measures at the stored
 * width, or at the printable width when fitting a wide text box, and hands the result to both the
 * tiling (which needs rectangles) and the renderer (which needs the layouts).
 */
class MeasuredPage(
    val pageId: String,
    val title: String,
    val createdAt: Long,
    val doc: PageDoc,
    val colors: PdfCanvasColors,
    val items: List<PdfItem>,
    val texts: Map<String, PdfTextBlock>,
    val tables: Map<String, PdfTableGrid>,
    /** The entities the fit moves — `ContentGroups.kt`. Objects and ink together, by design. */
    val groups: List<PdfGroup>,
    /** The sheet this page is already laid out on, or null when it has to be tiled. */
    val boundSheet: InkBounds?,
    equations: Map<String, PdfEquation>,
    images: Map<String, org.jetbrains.skia.Image>,
    val header: PdfPageHeader?,
) {
    internal val drawingEquations = equations.toMutableMap()
    internal val drawingImages = images.toMutableMap()
    val equations: Map<String, PdfEquation> get() = drawingEquations
    val images: Map<String, org.jetbrains.skia.Image> get() = drawingImages
    internal var drawingAssetsLoaded = false
    private var indexedPlan: PdfPagePlan? = null
    private var drawIndex: PdfDrawIndex? = null
    internal val allContent = PdfDrawContent(doc.outlines, groups)
    internal fun visibleContent(plan: PdfPagePlan, tile: PdfTile): PdfDrawContent {
        if (indexedPlan !== plan) {
            drawIndex = PdfDrawIndex(this, plan)
            indexedPlan = plan
        }
        return drawIndex!!.content(tile)
    }

    internal fun releaseDrawingAssets() {
        drawingImages.values.forEach { it.close() }
        drawingImages.clear()
        drawingEquations.values.forEach { it.close() }
        drawingEquations.clear()
        drawingAssetsLoaded = false
    }

    fun close() {
        releaseDrawingAssets()
        texts.values.flatMap { it.layout.equations }.forEach { it.close() }
        tables.values.flatMap { it.cells.values }.flatMap { it.equations }.forEach { it.close() }
    }

    val style: PageStyle get() = doc.style

    /** Which group each outline ended up in, so a shift planned for the group reaches its parts. */
    private val groupOf: Map<String, String> = buildMap {
        groups.forEach { group ->
            group.atoms.forEach { atom -> atom.outlineId?.let { put(it, group.id) } }
        }
    }

    /** Every outline's own rectangle, for culling a sheet down to what lands on it. */
    private val atomBounds: Map<String, InkBounds> = buildMap {
        groups.forEach { group ->
            group.atoms.forEach { atom -> atom.outlineId?.let { put(it, atom.bounds) } }
        }
    }

    /**
     * What [plan] moved this outline by.
     *
     * Through the group, never the outline: the plan shifts whole entities, and an outline that
     * asked for its own answer would be the one part of a diagram that stayed behind.
     */
    fun shiftOf(outlineId: String, plan: PdfPagePlan): PdfShift =
        plan.shiftFor(groupOf[outlineId] ?: outlineId)

    fun boundsOf(outlineId: String): InkBounds? = atomBounds[outlineId]
}

/**
 * The title band's measured geometry, or null on a page that hides it.
 *
 * Laid out at the origin and then [offsetBy] wherever the sheet wants it, because where it wants it
 * is not a fact about the header — see [PageMeasurer.measure], which anchors it to the content
 * block rather than to the page's own corner.
 */
class PdfPageHeader(
    val title: String,
    val subtitle: String,
    val leftDp: Float,
    val topDp: Float,
    val widthDp: Float,
    val heightDp: Float,
    val ruleWidthDp: Float,
    val ruleTopDp: Float,
    val subtitleBaselineDp: Float,
    val titleBaselineDp: Float,
) {
    val bounds: InkBounds
        get() = InkBounds(leftDp, topDp, leftDp + widthDp, topDp + heightDp)

    fun offsetBy(dx: Float, dy: Float): PdfPageHeader = PdfPageHeader(
        title = title,
        subtitle = subtitle,
        leftDp = leftDp + dx,
        topDp = topDp + dy,
        widthDp = widthDp,
        heightDp = heightDp,
        ruleWidthDp = ruleWidthDp,
        ruleTopDp = ruleTopDp + dy,
        subtitleBaselineDp = subtitleBaselineDp + dy,
        titleBaselineDp = titleBaselineDp + dy,
    )
}

/**
 * Lays out a page for export.
 *
 * **Everything is measured in device pixels and drawn in page dp.** A font size mark is stored as an
 * `AbsoluteSizeSpan(sp, dip = true)`, which converts itself using the device's own display metrics
 * whenever it is measured — so a layout built at any other density would disagree with the marks
 * inside it. Measuring at the device's density and scaling the *drawing* by `1/density` is what
 * makes the exported line breaks the same line breaks the writer saw.
 */
class PageMeasurer(
    private val attachments: AttachmentStore? = null,
    private val log: DebugLog = DebugLog(),
) {
    private val density = 1f
    private val text = PdfTextMeasurer()
    internal var work: PdfWorkCounts? = null

    suspend fun measure(
        pageId: String,
        title: String,
        createdAt: Long,
        doc: PageDoc,
        strokes: List<PageStroke>,
        paper: PdfPaper,
        /**
         * Whether to decode the page's pictures.
         *
         * False while a plan is being built, and that costs the plan nothing: a picture's rectangle
         * is in the document, so the tiling and the fit reach the same answer either way. It is what
         * lets a fifty-page section be planned without fifty pages of print-resolution bitmaps.
         */
        loadPictures: Boolean = true,
        fitContent: Boolean = true,
        loadDrawingAssets: Boolean = true,
    ): MeasuredPage {
        work?.let { it.measuredPages++ }
        val colors = PdfCanvasColors.forPage(doc.style)
        val items = mutableListOf<PdfItem>()
        val atoms = mutableListOf<PdfAtom>()

        val texts = mutableMapOf<String, PdfTextBlock>()
        val tables = mutableMapOf<String, PdfTableGrid>()
        val equations = mutableMapOf<String, PdfEquation>()
        val images = mutableMapOf<String, org.jetbrains.skia.Image>()
        try {
        suspend fun measureText(outline: Outline.Text, width: Float): PdfAtom {
            coroutineContext.ensureActive()
            val layout = layoutFor(outline.blocks, width - TEXT_PADDING_DP * 2, colors)
            val left = outline.x + TEXT_PADDING_DP
            val top = outline.y + GRIP_HEIGHT_DP + TEXT_PADDING_DP
            texts.put(outline.id, PdfTextBlock(layout, left, top))?.layout?.equations?.forEach { it.close() }
            val height = maxOf(layout.height / density, outline.minHeight)
            return PdfAtom(
                id = outline.id,
                outlineId = outline.id,
                bounds = InkBounds(
                    left = outline.x,
                    top = outline.y + GRIP_HEIGHT_DP,
                    right = outline.x + width,
                    bottom = top + height + TEXT_PADDING_DP,
                ),
            )
        }
        val textOutlines = doc.outlines.filterIsInstance<Outline.Text>()
        val originalSheet = doc.style.pageSizeDp
        val measureOriginalText = originalSheet != null && originalSheet.first == paper.widthDp &&
            originalSheet.second == paper.heightDp
        val initiallyWrapped = HashSet<String>()
        val textAtomIndices = HashMap<String, Int>()
        textOutlines.forEach { outline ->
            textAtomIndices[outline.id] = atoms.size
            val wrap = fitContent && !measureOriginalText && outline.width > paper.tileWidthDp
            if (wrap) initiallyWrapped += outline.id
            atoms += measureText(outline, if (wrap) paper.tileWidthDp else outline.width)
        }

        doc.outlines.filterIsInstance<Outline.Table>().forEach { table ->
            val grid = measureTable(table, colors)
            tables[table.id] = grid
            atoms += PdfAtom(
                id = table.id,
                outlineId = table.id,
                bounds = InkBounds(
                    left = grid.leftDp,
                    top = grid.topDp,
                    right = grid.leftDp + table.width,
                    bottom = grid.topDp + grid.heightDp,
                ),
            )
        }

        doc.outlines.filterIsInstance<Outline.Shape>().forEach { shape ->
            // Grown by half the pen, because a border is drawn centred on the geometry and the
            // stored box is the geometry — a two-dp rule on the edge of a tile is otherwise halved.
            val pen = shape.borderWidth / 2f
            atoms += PdfAtom(
                id = shape.id,
                outlineId = shape.id,
                bounds = InkBounds(
                    left = shape.x - pen,
                    top = shape.y - pen,
                    right = shape.x + shape.width + pen,
                    bottom = shape.y + shape.height + pen,
                ),
            )
        }

        doc.outlines.filterIsInstance<Outline.Equation>().forEach { equation ->
            coroutineContext.ensureActive()
            if (loadDrawingAssets) try {
                equations[equation.id] = PdfEquation.create(
                    latex = equation.latex,
                    fontSizePx = EQUATION_BASE_DP,
                    color = equation.colorArgb ?: colors.textArgb,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            atoms += PdfAtom(
                id = equation.id,
                outlineId = equation.id,
                bounds = InkBounds(
                    left = equation.x,
                    top = equation.y,
                    right = equation.x + equation.width,
                    bottom = equation.y + equation.height,
                ),
            )
        }

        doc.outlines.filterIsInstance<Outline.Image>().forEach { image ->
            coroutineContext.ensureActive()
            val store = attachments
            if (loadPictures && store != null && image.attachmentId !in images) {
                // Asked for at print resolution rather than at screen resolution: a picture that
                // looked fine at 320 dp on a tablet is a 300-dpi block of paper here.
                val target = (maxOf(image.width, image.height) * PRINT_PIXELS_PER_DP)
                    .roundToInt()
                    .coerceIn(1, MAX_IMAGE_EDGE)
                loadImage(store, image.attachmentId, target)?.let { images[image.attachmentId] = it }
            }
            atoms += PdfAtom(
                id = image.id,
                outlineId = image.id,
                bounds = InkBounds(
                    left = image.x,
                    top = image.y,
                    right = image.x + image.width,
                    bottom = image.y + image.height,
                ),
            )
        }

        atoms += inkAtoms(strokes)

        // Determine the bound-sheet exception from the original canvas, before any export reflow.
        val boundSheet = if (measureOriginalText) boundSheetFor(doc.style, paper, atoms.map(PdfAtom::bounds).unionOrNull()) else null
        if (fitContent && boundSheet == null) {
            val wideTexts = textOutlines.filter { it.width > paper.tileWidthDp }
            wideTexts.forEach { outline ->
                if (outline.id in initiallyWrapped) return@forEach
                val index = textAtomIndices.getValue(outline.id)
                atoms[index] = measureText(outline, paper.tileWidthDp)
            }
            if (wideTexts.isNotEmpty()) log.event("pdf") { "wrapped ${wideTexts.size} wide text boxes" }
        }

        // The whole correction: objects and ink are gathered into entities *together*, by the empty
        // page between them, rather than each outline standing alone with ink clustered separately.
        // A sum written beside a paragraph is part of it, and now travels with it.
        val groups = groupContent(atoms, paper.tileWidthDp, paper.tileHeightDp)
        groups.forEach { group -> items += PdfItem(group.id, PdfItemKind.Content, group.bounds) }

        val content = groups.map(PdfGroup::bounds).unionOrNull()
        val header = if (doc.style.hideTitle) {
            null
        } else {
            placeHeader(
                measureHeader(title, createdAt, paper.tileWidthDp),
                content = content,
                bound = boundSheet != null,
            )
        }
        header?.let { items += PdfItem(HEADER_ITEM_ID, PdfItemKind.Title, it.bounds) }

        return MeasuredPage(
            pageId = pageId,
            title = title,
            createdAt = createdAt,
            doc = doc,
            colors = colors,
            items = items,
            texts = texts,
            tables = tables,
            groups = groups,
            boundSheet = boundSheet,
            equations = equations,
            images = images,
            header = header,
        ).also { it.drawingAssetsLoaded = loadDrawingAssets && loadPictures }
        } catch (error: Throwable) {
            images.values.forEach { it.close() }
            equations.values.forEach { it.close() }
            texts.values.forEach { it.layout.equations.forEach(PdfEquation::close) }
            tables.values.forEach { table -> table.cells.values.forEach { it.equations.forEach(PdfEquation::close) } }
            throw error
        }
    }

    internal suspend fun hydrateDrawingAssets(page: MeasuredPage) {
        if (page.drawingAssetsLoaded) return
        for (equation in page.allContent.equations) {
            coroutineContext.ensureActive()
            if (equation.id !in page.drawingEquations) try {
                page.drawingEquations[equation.id] = PdfEquation.create(equation.latex, EQUATION_BASE_DP,
                    equation.colorArgb ?: page.colors.textArgb)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        val store = attachments
        if (store != null) for (image in page.allContent.pictures) {
            coroutineContext.ensureActive()
            if (image.attachmentId in page.drawingImages) continue
            val target = (maxOf(image.width, image.height) * PRINT_PIXELS_PER_DP)
                .roundToInt().coerceIn(1, MAX_IMAGE_EDGE)
            loadImage(store, image.attachmentId, target)?.let { page.drawingImages[image.attachmentId] = it }
        }
        page.drawingAssetsLoaded = true
        work?.let { it.hydratedPages++ }
    }

    /** The sheet a bound page fits inside, or null when it does not — the one exception. */
    private fun boundSheetFor(style: PageStyle, paper: PdfPaper, content: InkBounds?): InkBounds? {
        val (sheetWidth, sheetHeight) = style.pageSizeDp ?: return null
        // Only when the export is going onto the sheet the page was already laid out for. A page
        // bound to A5 printed onto A4 is a re-layout, and re-layout is what the tiling is.
        if (sheetWidth != paper.widthDp || sheetHeight != paper.heightDp) return null
        if (content == null) return InkBounds(0f, 0f, sheetWidth, sheetHeight)
        if (content.right > sheetWidth || content.bottom > sheetHeight) return null
        return InkBounds(0f, 0f, sheetWidth, sheetHeight)
    }

    /**
     * Where the title band goes.
     *
     * On a tiled export it is anchored to the content, not to the page's own corner, and that is the
     * whole of the left-hand normalisation. The grid starts at the leftmost thing on the page, so a
     * page whose writing begins two inches in has those two inches collapsed — but only if the title
     * stops holding the left edge open at x = 40. It used to, and every page exported with its own
     * dead margin baked in and content pushed off the right edge.
     *
     * So the header sits directly above the content block and shares its left edge, the way a
     * letterhead does; never above the page's own top, so a page whose writing starts at the very
     * top does not push the band off the sheet.
     *
     * A bound page keeps its own placement, because that page already is a sheet.
     */
    private fun placeHeader(header: PdfPageHeader, content: InkBounds?, bound: Boolean): PdfPageHeader {
        if (bound || content == null) return header.offsetBy(HEADER_INSET_DP, HEADER_TOP_DP)
        return header.offsetBy(
            dx = content.left,
            dy = maxOf(0f, content.top - header.heightDp - HEADER_CONTENT_GAP_DP),
        )
    }

    private suspend fun layoutFor(blocks: List<Block>, widthDp: Float, colors: PdfCanvasColors): PdfTextLayout =
        text.layout(blocks, widthDp, colors)

    private suspend fun loadImage(store: AttachmentStore, id: String, target: Int): org.jetbrains.skia.Image? {
        val bytes = store.bytes(id) ?: return null
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull()?.let { source ->
            if (maxOf(source.width, source.height) <= target) return@let source
            val scale = target.toFloat() / maxOf(source.width, source.height)
            try {
                org.jetbrains.skia.Surface.makeRasterN32Premul(
                    (source.width * scale).roundToInt().coerceAtLeast(1),
                    (source.height * scale).roundToInt().coerceAtLeast(1),
                ).use { surface ->
                    surface.canvas.drawImageRect(source, org.jetbrains.skia.Rect.makeWH(
                        surface.width.toFloat(), surface.height.toFloat()))
                    surface.makeImageSnapshot()
                }
            } finally { source.close() }
        }
    }

    private suspend fun measureTable(
        table: Outline.Table,
        colors: PdfCanvasColors,
    ): PdfTableGrid {
        val cells = mutableMapOf<String, PdfTextLayout>()
        val heights = FloatArray(table.rows.size)
        table.rows.forEachIndexed { rowIndex, row ->
            var tallest = row.minHeight
            row.cells.forEachIndexed { columnIndex, cell ->
                if (table.inkOnly) return@forEachIndexed
                val columnWidth = table.columns.getOrElse(columnIndex) { 0f }
                val layout = layoutFor(
                    blocks = cell.blocks,
                    widthDp = (columnWidth - CELL_PADDING_DP * 2).coerceAtLeast(1f),
                    colors = colors,
                )
                cells[cell.id] = layout
                tallest = maxOf(tallest, layout.height / density + CELL_PADDING_DP * 2)
            }
            heights[rowIndex] = tallest
        }
        return PdfTableGrid(
            // The grid sits inside the gutter the handles live in — `TableContainer`'s two leading
            // strips — so a table's stored corner is not where its first rule is drawn.
            leftDp = table.x + TABLE_GUTTER_DP,
            topDp = table.y + TABLE_GUTTER_DP,
            rowHeightsDp = heights,
            cells = cells,
        )
    }

    private fun measureHeader(title: String, createdAt: Long, widthDp: Float): PdfPageHeader {
        val titleHeight: Float
        val titleBaseline: Float
        org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL), TITLE_SP).use { font ->
            titleHeight = -font.metrics.top + font.metrics.bottom
            titleBaseline = -font.metrics.top
        }
        val subtitleHeight: Float
        val subtitleTop: Float
        org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL), SUBTITLE_SP).use { font ->
            subtitleHeight = -font.metrics.top + font.metrics.bottom
            subtitleTop = -font.metrics.top
        }
        val ruleTop = titleHeight + TITLE_RULE_GAP_DP
        val subtitleBaseline = ruleTop + HEADER_RULE_HEIGHT_DP + SUBTITLE_GAP_DP + subtitleTop
        val bottom = ruleTop + HEADER_RULE_HEIGHT_DP + SUBTITLE_GAP_DP + subtitleHeight
        return PdfPageHeader(
            title = title.ifEmpty { UNTITLED },
            subtitle = formatCreated(createdAt),
            leftDp = 0f,
            topDp = 0f,
            widthDp = minOf(widthDp.coerceAtLeast(1f), TITLE_MAX_WIDTH_DP),
            heightDp = bottom,
            ruleWidthDp = minOf(widthDp.coerceAtLeast(1f), HEADER_RULE_MAX_WIDTH_DP),
            ruleTopDp = ruleTop,
            titleBaselineDp = titleBaseline,
            subtitleBaselineDp = subtitleBaseline,
        )
    }

    companion object {
        /** `EditorPane`'s `editorStyle`, in the dp it computes those pixel values from. */
        const val INDENT_STEP_DP = 28f
        const val LIST_GAP_DP = 34f
        const val BULLET_RADIUS_DP = 3f

        /** `OutlineContainer`: a reserved grip strip above the editor and 6 dp of padding round it. */
        const val GRIP_HEIGHT_DP = 18f
        const val TEXT_PADDING_DP = 6f

        /** `NoteEditor.setLineSpacing(0f, 1.25f)`. */
        const val LINE_SPACING_MULTIPLIER = 1.25f

        /** `TableContainer`'s own two. */
        const val TABLE_GUTTER_DP = 16f
        const val CELL_PADDING_DP = 6f

        /** `EquationLayer.BASE_FONT_DP` — the size a formula's metrics are laid out at. */
        const val EQUATION_BASE_DP = 22f

        /** `PageHeader`'s insets and type, read off the composable. */
        /** Where a *bound* page's band sits, which is where the canvas draws it. */
        const val HEADER_INSET_DP = 40f
        const val HEADER_TOP_DP = 24f

        /** Air between the band and the first thing written under it, on a tiled export. */
        const val HEADER_CONTENT_GAP_DP = 24f
        const val TITLE_SP = 26f
        const val SUBTITLE_SP = 12.5f
        const val TITLE_RULE_GAP_DP = 2f
        const val HEADER_RULE_HEIGHT_DP = 1f
        const val SUBTITLE_GAP_DP = 6f
        const val TITLE_MAX_WIDTH_DP = 900f
        const val HEADER_RULE_MAX_WIDTH_DP = 420f
        val UNTITLED = Strings.untitled_page

        /** 300 dpi over the 160 dp per inch a page is measured in. */
        const val PRINT_PIXELS_PER_DP = 300f / PageStyle.DP_PER_INCH
        const val MAX_IMAGE_EDGE = 3000

        /** The id the header is tiled under. Not an outline id; nothing else can collide with it. */
        const val HEADER_ITEM_ID = "\u0000header"
    }
}

/**
 * The page's created-at line, in the format the canvas header uses.
 *
 * A copy of `EditorPane.formatCreated` rather than a shared function, and deliberately: that one is
 * private to a composable file whose only other job is drawing, and the two are the same sentence
 * only because they describe the same page. `SimpleDateFormat` is not thread-safe and the exporter
 * runs off the main thread, so these are built per call rather than held.
 */
internal fun formatCreated(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = java.util.Date(timestamp)
    val day = java.text.SimpleDateFormat(Strings.date_full_pattern, java.util.Locale.getDefault())
    val time = java.text.SimpleDateFormat(Strings.time_pattern, java.util.Locale.getDefault())
    return "${day.format(date)}    ${time.format(date)}"
}
