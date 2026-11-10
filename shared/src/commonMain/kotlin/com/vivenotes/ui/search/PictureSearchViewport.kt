package com.vivenotes.ui.search

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vivenotes.model.Outline
import com.vivenotes.model.ocr.PictureHighlight
import kotlin.math.abs

/** All coordinates, including scroll limits, are in page dp. */
internal fun pictureSearchScrollTarget(
    image: Outline.Image,
    highlight: PictureHighlight,
    viewport: Rect,
    maxScroll: Offset = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
): Offset {
    if (viewport.width <= 0f || viewport.height <= 0f) return viewport.topLeft
    val quads = highlight.quads
    val points = 0 until quads.size / 2
    val bounds = Rect(
        image.x + points.minOf { quads[it * 2] } * image.width,
        image.y + points.minOf { quads[it * 2 + 1] } * image.height,
        image.x + points.maxOf { quads[it * 2] } * image.width,
        image.y + points.maxOf { quads[it * 2 + 1] } * image.height,
    )
    val centered = Offset(
        (bounds.center.x - viewport.width / 2f).coerceIn(0f, maxScroll.x.coerceAtLeast(0f)),
        (bounds.center.y - viewport.height / 2f).coerceIn(0f, maxScroll.y.coerceAtLeast(0f)),
    )
    // Keep adjacent results steady within the middle 60% of the view. Compare with the reachable
    // center so a word near the page's edge can also remain still when scrolling is clamped.
    val visible = bounds.left >= viewport.left && bounds.right <= viewport.right &&
        bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
    val nearCenter = abs(centered.x - viewport.left) <= viewport.width * .3f &&
        abs(centered.y - viewport.top) <= viewport.height * .3f
    return if (visible && nearCenter) viewport.topLeft else centered
}
