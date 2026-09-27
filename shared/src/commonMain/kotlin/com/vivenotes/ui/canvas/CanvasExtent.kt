package com.vivenotes.ui.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vivenotes.model.PageStyle
import com.vivenotes.workspace.titleFloor
import kotlin.math.roundToInt

/**
 * The page's size and how far the canvas reaches — Android `EditorPane`'s rule, ported.
 *
 * A page on [com.vivenotes.model.PaperSize.Auto] is an endless canvas: it always keeps a screenful
 * of blank page in front of wherever the user has scrolled. A chosen sheet binds the page only while
 * it holds all the content; once something sits outside it, the sheet becomes a dashed guide and the
 * canvas covers the content instead, so choosing a size never puts work out of reach.
 */
internal data class PageExtent(
    /** The chosen sheet, or null on an Auto page. */
    val sheet: DpSize?,
    /** The sheet holds all the content, and so is the page's edge. */
    val bound: Boolean,
    /** What the page is: the sheet while bound, otherwise what the content needs. Page Width fits it. */
    val page: DpSize,
    /** The page plus the working margin past its last object; the canvas is never smaller. */
    val room: DpSize,
) {
    /**
     * The canvas that can be scrolled over. [window] is what the viewport shows, in page dp, and
     * [reachedX]/[reachedY] are how many screenfuls the user has scrolled at most — a high-water
     * mark, so scrolling back never shrinks the canvas out from under the scroll position.
     */
    fun canvasSize(window: DpSize, reachedX: Int, reachedY: Int, density: Density, zoom: Float): DpSize {
        val requested = if (bound) {
            DpSize(maxOf(room.width, window.width), maxOf(room.height, window.height))
        } else {
            DpSize(
                maxOf(room.width, window.width * (reachedX + 1)),
                maxOf(room.height, window.height * (reachedY + 1)),
            )
        }
        return density.limitEmptyCanvas(room, requested, zoom)
    }

    /** Whether page point ([x], [y]) may take new content: on the sheet, below the title. */
    fun canPlaceAt(style: PageStyle, x: Float, y: Float): Boolean {
        val offSheet = bound && (x > page.width.value || y > page.height.value)
        return !offSheet && y >= style.titleFloor
    }

    companion object {
        val CanvasMinWidth = 720.dp
        val CanvasMinHeight = 700.dp

        /** Blank canvas kept right of the widest object, so there is room to start another. */
        val TrailingWidth = 200.dp
        val TrailingHeight = 320.dp

        /** [contentRight] and [contentBottom] are the far edges of everything on the page, in dp. */
        fun of(style: PageStyle, contentRight: Float, contentBottom: Float): PageExtent {
            val sheet = style.pageSizeDp?.let { (width, height) -> DpSize(width.dp, height.dp) }
            val contentWidth = contentRight.coerceAtLeast(0f).dp
            val contentHeight = maxOf(contentBottom, style.titleFloor).dp
            val bound = sheet != null && contentWidth <= sheet.width && contentHeight <= sheet.height
            val page = if (bound) sheet else DpSize(
                maxOf(contentWidth, sheet?.width ?: 0.dp, CanvasMinWidth),
                maxOf(contentHeight, sheet?.height ?: 0.dp, CanvasMinHeight),
            )
            return PageExtent(sheet, bound, page, DpSize(page.width + TrailingWidth, page.height + TrailingHeight))
        }
    }
}

/**
 * Conservative budgets for speculative blank canvas, from Compose [Constraints]' packed axis sizes:
 * either two medium axes, or one narrow axis with one long one.
 */
private const val CONSTRAINT_NARROW_PX = 8_191
private const val CONSTRAINT_SOLE_PX = 262_143
private const val CONSTRAINT_PAIRED_PX = 32_767

private data class CanvasConstraintBudget(val widthPx: Int, val heightPx: Int)

/**
 * Limits only the blank area requested around [requiredSize]; document content is never shortened.
 *
 * Each budget is tested against the required page first, and only its remaining capacity is
 * offered to [requestedSize]. If the content itself exceeds every budget, [requiredSize] is returned
 * unchanged and [documentExtent] carries it without a packed fixed-size constraint. Zoom counts
 * because the zoomed layer reports its scaled size to the scroll containers.
 */
internal fun Density.limitEmptyCanvas(requiredSize: DpSize, requestedSize: DpSize, zoom: Float): DpSize {
    fun rawCeiling(reportedCeiling: Int): Int = if (zoom <= 1f) reportedCeiling else (reportedCeiling / zoom).toInt()

    fun constraintExtent(dp: Dp): Int {
        val raw = dp.roundToPx()
        return maxOf(raw, (raw * zoom).roundToInt())
    }

    fun CanvasConstraintBudget.holds(size: DpSize): Boolean =
        constraintExtent(size.width) <= widthPx && constraintExtent(size.height) <= heightPx

    fun CanvasConstraintBudget.fill(): DpSize {
        fun axis(required: Dp, requested: Dp, ceiling: Int): Dp =
            maxOf(required, minOf(requested, (rawCeiling(ceiling) / density).toDp()))
        return DpSize(
            width = axis(requiredSize.width, requestedSize.width, widthPx),
            height = axis(requiredSize.height, requestedSize.height, heightPx),
        )
    }

    return listOf(
        CanvasConstraintBudget(CONSTRAINT_PAIRED_PX, CONSTRAINT_PAIRED_PX),
        CanvasConstraintBudget(CONSTRAINT_NARROW_PX, CONSTRAINT_SOLE_PX),
        CanvasConstraintBudget(CONSTRAINT_SOLE_PX, CONSTRAINT_NARROW_PX),
    ).asSequence()
        .filter { it.holds(requiredSize) }
        .map { it.fill() }
        .maxByOrNull { constraintExtent(it.width).toLong() * constraintExtent(it.height) }
        ?: requiredSize
}

/**
 * Gives a canvas layer its document-space [extent] without building a fixed [Constraints] pair.
 *
 * Compose packs both axes of one [Constraints] into a single value, so a page may be legal when very
 * wide or very tall but unrepresentable when both. Measuring unbounded and reporting [extent]
 * directly keeps that encoding out of the page's geometry, which an endless canvas needs.
 */
internal fun Modifier.documentExtent(extent: DpSize): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout(extent.width.roundToPx(), extent.height.roundToPx()) { placeable.place(0, 0) }
}
