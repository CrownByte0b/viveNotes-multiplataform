package com.vivenotes.ink

import com.vivenotes.byteink.kit.InkBounds
import com.vivenotes.byteink.kit.InkPoint

/** A rectangle as a lasso path, corner to corner. */
internal fun InkBounds.selectionPath(): List<InkPoint> =
    listOf(InkPoint(left, top), InkPoint(right, top), InkPoint(right, bottom), InkPoint(left, bottom))

/** How far a lasso made from ink's own bounds stands off that ink: Android's `SPACE_LASSO_MARGIN`. */
internal const val HELD_LASSO_MARGIN = 4f

/**
 * The lasso that holds the ink whose union [InkBounds] this is, to store with its move and to select
 * it again by: the box grown by [HELD_LASSO_MARGIN]. A path along the bounds themselves touches the
 * ink, and enclosure is tested on a cut piece (which has no outline) without any edge tolerance, so
 * such a piece would be neither moved by replay nor selected again. Android pads the Insert Space box
 * it stores for the same reason.
 */
internal fun InkBounds.heldLassoPath(): List<InkPoint> = InkBounds(left - HELD_LASSO_MARGIN, top - HELD_LASSO_MARGIN,
    right + HELD_LASSO_MARGIN, bottom + HELD_LASSO_MARGIN).selectionPath()
