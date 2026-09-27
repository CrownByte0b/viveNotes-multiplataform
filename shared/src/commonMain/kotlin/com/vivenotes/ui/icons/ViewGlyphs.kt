package com.vivenotes.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The View tab's two-tone glyphs, copied from the Android `RibbonGlyphs.kt` View section. The
 * accent marks the part of each glyph that carries its meaning; `DocumentGlyphs.kt` explains why.
 */

private inline fun viewGlyph(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private fun ImageVector.Builder.stroked(color: Color, width: Float, block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(color),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/** A window with its navigation column picked out — the choice Tabs Layout offers. */
fun tabsLayoutGlyph(neutral: Color, accent: Color): ImageVector = viewGlyph("TabsLayout") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.7f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3.2f, 4.2f)
        lineTo(20.8f, 4.2f)
        lineTo(20.8f, 19.8f)
        lineTo(3.2f, 19.8f)
        close()
    }
    path(fill = SolidColor(accent)) {
        moveTo(4.6f, 5.6f)
        lineTo(9.4f, 5.6f)
        lineTo(9.4f, 18.4f)
        lineTo(4.6f, 18.4f)
        close()
    }
    listOf(9.0f, 12.5f, 16.0f).forEach { y ->
        path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(11.6f, y)
            lineTo(18.6f, y)
        }
    }
}

/** Ruled paper: accent rules and the red margin that tells it apart from a grid at ribbon size. */
fun ruleLinesGlyph(neutral: Color, accent: Color, warn: Color): ImageVector = viewGlyph("RuleLines") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.2f, 3.4f)
        lineTo(19.8f, 3.4f)
        lineTo(19.8f, 20.6f)
        lineTo(4.2f, 20.6f)
        close()
    }
    listOf(7.6f, 11.2f, 14.8f, 18.0f).forEach { y ->
        path(stroke = SolidColor(accent), strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
            moveTo(9.2f, y)
            lineTo(17.8f, y)
        }
    }
    path(stroke = SolidColor(warn), strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.4f, 5.2f)
        lineTo(7.4f, 18.8f)
    }
}

/** A sheet with its width and height called out, the way a dimension drawing marks them. */
fun paperSizeGlyph(neutral: Color, accent: Color): ImageVector = viewGlyph("PaperSize") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(9.6f, 6.4f)
        lineTo(16.4f, 6.4f)
        lineTo(20.2f, 10.2f)
        lineTo(20.2f, 20.8f)
        lineTo(9.6f, 20.8f)
        close()
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(16.4f, 6.4f) // the folded corner
        lineTo(16.4f, 10.2f)
        lineTo(20.2f, 10.2f)
    }
    dimension(accent, 9.6f, 3.4f, 20.2f, 3.4f) // width
    dimension(accent, 6.4f, 6.4f, 6.4f, 20.8f) // height
}

/** A dimension line with a tick at each end: at 18dp an arrowhead reads as a blob. */
private fun ImageVector.Builder.dimension(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    stroked(color, 1.3f) {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }
    val tick = 2.2f
    val horizontal = y1 == y2
    stroked(color, 1.3f) {
        if (horizontal) {
            moveTo(x1, y1 - tick / 2f); lineTo(x1, y1 + tick / 2f)
            moveTo(x2, y2 - tick / 2f); lineTo(x2, y2 + tick / 2f)
        } else {
            moveTo(x1 - tick / 2f, y1); lineTo(x1 + tick / 2f, y1)
            moveTo(x2 - tick / 2f, y2); lineTo(x2 + tick / 2f, y2)
        }
    }
}

/** A page whose title line is struck out. */
fun hidePageTitleGlyph(neutral: Color, warn: Color): ImageVector = viewGlyph("HidePageTitle") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.2f, 4.2f)
        lineTo(17.6f, 4.2f)
        lineTo(17.6f, 19.8f)
        lineTo(4.2f, 19.8f)
        close()
    }
    path(fill = SolidColor(neutral)) { // the title it hides
        moveTo(6.6f, 7.4f)
        lineTo(12.4f, 7.4f)
        lineTo(12.4f, 9.6f)
        lineTo(6.6f, 9.6f)
        close()
    }
    stroked(warn, 2.0f) {
        moveTo(15.0f, 5.0f)
        lineTo(21.4f, 11.4f)
        moveTo(21.4f, 5.0f)
        lineTo(15.0f, 11.4f)
    }
}

/** The page, and the span it is being fitted to. */
fun pageWidthGlyph(neutral: Color, accent: Color): ImageVector = viewGlyph("PageWidth") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.4f, 4.6f)
        lineTo(19.6f, 4.6f)
        lineTo(19.6f, 15.4f)
        lineTo(4.4f, 15.4f)
        close()
    }
    stroked(accent, 1.5f) {
        moveTo(2.6f, 19.4f)
        lineTo(21.4f, 19.4f)
    }
    stroked(accent, 1.5f) { // arrowheads, pointing outward
        moveTo(5.4f, 17.0f)
        lineTo(2.6f, 19.4f)
        lineTo(5.4f, 21.8f)
        moveTo(18.6f, 17.0f)
        lineTo(21.4f, 19.4f)
        lineTo(18.6f, 21.8f)
    }
}

/**
 * Page colour: a tipped bucket over a bar showing what the page is painted, so the ribbon shows the
 * page's colour without opening the menu.
 */
fun pageColorGlyph(neutral: Color, swatch: Color): ImageVector = viewGlyph("PageColor") {
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6.4f, 10.6f)
        lineTo(12.2f, 4.8f)
        lineTo(18.0f, 10.6f)
        lineTo(12.2f, 16.4f)
        close()
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
        moveTo(9.0f, 8.0f) // handle
        curveTo(8.2f, 5.4f, 11.4f, 4.0f, 12.6f, 6.2f)
    }
    path(fill = SolidColor(neutral)) { // the drip
        moveTo(19.6f, 11.4f)
        curveTo(21.4f, 14.0f, 21.4f, 15.6f, 19.6f, 15.6f)
        curveTo(17.8f, 15.6f, 17.8f, 14.0f, 19.6f, 11.4f)
        close()
    }
    path(fill = SolidColor(swatch)) {
        moveTo(3.2f, 19.2f)
        lineTo(20.8f, 19.2f)
        lineTo(20.8f, 22.0f)
        lineTo(3.2f, 22.0f)
        close()
    }
}

/** The View tab's two-tone glyphs for one neutral colour. */
class ViewRibbonIcons(neutral: Color, accent: Color, warn: Color) {
    val tabsLayout = tabsLayoutGlyph(neutral, accent)
    val ruleLines = ruleLinesGlyph(neutral, accent, warn)
    val paperSize = paperSizeGlyph(neutral, accent)
    val hidePageTitle = hidePageTitleGlyph(neutral, warn)
    val pageWidth = pageWidthGlyph(neutral, accent)
}

/** Idle and pressed sets: a two-tone glyph cannot be recoloured at draw time. */
@Composable
fun rememberViewRibbonIcons(
    idleNeutral: Color,
    activeNeutral: Color,
    accent: Color,
    warn: Color,
): Pair<ViewRibbonIcons, ViewRibbonIcons> =
    remember(idleNeutral, activeNeutral, accent, warn) {
        ViewRibbonIcons(idleNeutral, accent, warn) to ViewRibbonIcons(activeNeutral, accent, warn)
    }
