package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBounds
import com.vivenotes.byteink.kit.InkLassoSelection
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.InkProjectionKey
import com.vivenotes.byteink.kit.unionBounds
import com.vivenotes.byteink.portable.InkProjection
import com.vivenotes.byteink.portable.InkStroke

// The browser's answers to the questions desktop puts to the kit's page strokes (`InkPageIndex`,
// `Lasso.kt`, `planProjectionDelete`), asked of the portable engine's pieces with the same rules.

/** How close to a lasso's edge ink may lie and still count as inside: the kit's default. */
internal const val LASSO_EDGE_TOLERANCE = 4f

/** How far a probe reaches around a point: the mesh's own tolerance, as the kit's. */
private const val PROBE_REACH = 0.25f

/** The dots a held piece is deleted with, smallest first, as the kit's: 1, 2 and 4 dp. */
private const val DOT_MASK_MIN_DP = 1f
private const val DOT_MASK_MAX_DP = 4f

/**
 * One piece of a page's replayed ink. [key] names it by row and its place among that row's pieces;
 * replay is deterministic, so the same rows give the same keys. The projection is borrowed from a
 * replay that [WebInkPages] may close later: use a piece right away.
 */
internal class WebInkPiece(val key: InkProjectionKey, val projection: InkProjection) {
    val id: String get() = projection.id
    val groupId: String? get() = projection.groupId
    val bounds: InkBounds? by lazy { projection.pageBounds }

    /** Whether any ink lies in a page-space box. */
    fun covers(box: InkBounds): Boolean {
        val own = bounds ?: return false
        if (!own.meets(box)) return false
        val t = projection.transform
        return projection.stroke.coverage(InkBounds((box.left - t.offsetX) / t.scaleX, (box.top - t.offsetY) / t.scaleY,
            (box.right - t.offsetX) / t.scaleX, (box.bottom - t.offsetY) / t.scaleY)) > 0f
    }

    /** Whether an eraser mask, in page space, touches this piece's ink. */
    fun touches(mask: InkStroke): Boolean {
        val own = bounds ?: return false
        val reach = mask.bounds ?: return false
        return own.meets(reach) && projection.stroke.intersects(mask, projection.transform)
    }

    /**
     * A page-space point certainly on this piece's ink, or null once it has none: the kit's
     * `pointOnInk`, first at its own input samples, then over its rectangle.
     */
    fun pointOnInk(): InkPoint? {
        val own = bounds ?: return null
        val t = projection.transform
        fun probe(point: InkPoint) = covers(InkBounds(point.x - PROBE_REACH, point.y - PROBE_REACH,
            point.x + PROBE_REACH, point.y + PROBE_REACH))
        for (sample in projection.stroke.inputs()) {
            val point = InkPoint(sample.x * t.scaleX + t.offsetX, sample.y * t.scaleY + t.offsetY)
            if (probe(point)) return point
        }
        val step = maxOf(PROBE_REACH, minOf(own.right - own.left, own.bottom - own.top) / 2f)
        var y = own.top
        while (y <= own.bottom) {
            var x = own.left
            while (x <= own.right) {
                InkPoint(x, y).let { if (probe(it)) return it }
                x += step
            }
            y += step
        }
        return null
    }
}

internal fun InkBounds.meets(other: InkBounds): Boolean =
    left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

/** The kit's `selectWithLasso`: the pieces inside [path], widened to every piece of their groups. */
internal fun List<WebInkPiece>.selectWithLasso(path: List<InkPoint>): InkLassoSelection? {
    if (path.size < 3) return null
    return selectionOf(filter { it.projection.stroke.enclosedBy(path, it.projection.transform, LASSO_EDGE_TOLERANCE) }, path)
}

/** The selection [hits] make on this page: the hits plus every piece of any group one belongs to. */
internal fun List<WebInkPiece>.selectionOf(hits: List<WebInkPiece>, path: List<InkPoint>): InkLassoSelection? {
    if (hits.isEmpty()) return null
    val groups = hits.mapNotNull { it.groupId }.toSet()
    val keys = hits.mapTo(HashSet()) { it.key }
    val selected = if (groups.isEmpty()) hits else filter { it.key in keys || it.groupId in groups }
    val bounds = selected.mapNotNull { it.bounds }.unionBounds() ?: return null
    return InkLassoSelection(path, selected.mapTo(LinkedHashSet()) { it.id }, selected.mapTo(LinkedHashSet()) { it.key }, bounds)
}

/** One stored Object erase standing for "remove this piece of [rowId]"; the caller owns [mask]. */
internal class WebPieceErase(val rowId: String, val mask: InkStroke)

/**
 * The kit's `planProjectionDelete`: a row every piece of which is held is tombstoned; each held piece
 * of another row gets its own Object erase with the smallest dot proved to take it and none of the
 * row's other pieces. A piece no dot can isolate is left rather than risk erasing unchosen ink.
 */
internal fun WebInkPages.planDelete(pieces: List<WebInkPiece>, held: Set<InkProjectionKey>):
    Pair<List<WebPieceErase>, List<String>> {
    val byRow = pieces.groupBy { it.id }
    val wholeRows = byRow.filterValues { row -> row.all { it.key in held } }.keys.toList()
    val erases = mutableListOf<WebPieceErase>()
    byRow.forEach { (rowId, row) ->
        val doomed = row.filter { it.key in held }
        if (doomed.isEmpty() || doomed.size == row.size) return@forEach
        val survivors = row.filterNot { it.key in held }
        doomed.forEach { piece -> dotAvoiding(piece, survivors)?.let { erases += WebPieceErase(rowId, it) } }
    }
    return erases to wholeRows
}

private fun WebInkPages.dotAvoiding(piece: WebInkPiece, others: List<WebInkPiece>): InkStroke? {
    val point = piece.pointOnInk() ?: return null
    var size = DOT_MASK_MIN_DP
    while (size <= DOT_MASK_MAX_DP) {
        // Proved on the mask as it comes back through the codec, which is what replay applies.
        val mask = kit.reloadedEraseMask(listOf(com.vivenotes.byteink.portable.InkSample(point.x, point.y, 0L)), size)
        if (mask != null && mask.bounds != null && piece.touches(mask)) {
            if (others.none { it.touches(mask) }) return mask
            mask.close()
            return null
        }
        mask?.close()
        size *= 2f
    }
    return null
}
