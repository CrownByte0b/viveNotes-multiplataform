package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Two-tone ribbon glyphs.
 *
 * The reference UI accents the part of a glyph that carries its meaning — the bullet markers, the
 * numerals, the tick — and leaves the surrounding scaffolding neutral. Material's icons cannot
 * express that: each is a single path, and [androidx.compose.material3.Icon] flattens whatever it is
 * given to one `tint`.
 *
 * Built in Kotlin rather than as XML vector drawables because XML pays runtime inflation on every
 * load while these are plain object allocation. It is also the only form that can take a colour as a
 * parameter, which the font and highlight glyphs need: their bar shows the currently selected colour.
 *
 * Colours are supplied by the caller, so the neutral can follow the theme and the active-button
 * state. See [AppIcons] for where they get built and cached.
 */

/** Row centres shared by the bulleted and numbered list glyphs, so the two line up in the ribbon. */
private val ListRows = floatArrayOf(6f, 12f, 18f)

private inline fun glyph(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

/**
 * [glyph], for artwork taken from Microsoft's Fluent UI System Icons.
 *
 * A third box, because Fluent authors in a 20x20 viewport with the origin at the top left and y
 * measured downwards. So there is no translating group here: pasting a Fluent export's path data in
 * verbatim already lands it where it belongs, the same reason [materialGlyph] keeps Google's
 * inverted box rather than rescaling it.
 *
 * The 24.dp default matches every other glyph in this file, so a 20-unit drawing fills the same
 * square a 960-unit one does.
 */
private inline fun fluentGlyph(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 20f,
        viewportHeight = 20f,
    ).apply(block).build()

/**
 * [glyph], for artwork traced from a Material Symbol rather than drawn from scratch here.
 *
 * Google's exports are authored in a 960×960 box whose origin sits at the *bottom* left
 * (`viewBox="0 -960 960 960"`), so every y is negative. Keeping that space rather than rescaling to
 * the 24 the hand-drawn glyphs use means an export's path data can be pasted in verbatim: nothing
 * is re-derived by hand, so nothing can be re-derived wrongly, and a redrawn `.svg` drops straight
 * in. The translating group is exactly the correction `res/drawable/ms_rounded_*.xml` already
 * applies for the same reason.
 */
private inline fun materialGlyph(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).group(translationY = 960f, block = block).build()

/** The three "text" rules that both list glyphs share. */
private fun ImageVector.Builder.listRules(neutral: Color) {
    ListRows.forEach { cy ->
        path(
            stroke = SolidColor(neutral),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(10f, cy)
            lineTo(20.5f, cy)
        }
    }
}

/**
 * Numerals and ticks are stroked rather than filled. At the 18dp the ribbon renders them, a
 * stroked path is both far easier to author by hand and closer to the reference's thin glyphs
 * than an outlined fill would be.
 */
private fun ImageVector.Builder.strokedAccent(
    accent: Color,
    width: Float,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) {
    path(
        stroke = SolidColor(accent),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/**
 * Material Symbols Rounded `insert_text`, separated into two semantic colours.
 *
 * The selection frame is the action — creating a new text container — so it receives the blue
 * accent. The T remains neutral and therefore follows both the theme and the button's active state.
 */
fun insertTextGlyph(neutral: Color, accent: Color): ImageVector = glyph("InsertText") {
    // Bounding frame. Its four short rules disappear beneath the corner handles, matching the
    // official symbol's continuous selection rectangle without baking in a background colour.
    path(
        stroke = SolidColor(accent),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        moveTo(20f, 5f)
        lineTo(20f, 19f)
        moveTo(19f, 20f)
        lineTo(5f, 20f)
        moveTo(4f, 19f)
        lineTo(4f, 5f)
    }
    listOf(
        2f to 2f,
        18f to 2f,
        18f to 18f,
        2f to 18f,
    ).forEach { (left, top) ->
        path(
            stroke = SolidColor(accent),
            strokeLineWidth = 2f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(left, top)
            lineTo(left + 4f, top)
            lineTo(left + 4f, top + 4f)
            lineTo(left, top + 4f)
            close()
        }
    }
    path(fill = SolidColor(neutral)) {
        moveTo(9f, 8f)
        lineTo(15f, 8f)
        curveTo(15.55f, 8f, 16f, 8.45f, 16f, 9f)
        curveTo(16f, 9.55f, 15.55f, 10f, 15f, 10f)
        lineTo(13f, 10f)
        lineTo(13f, 15f)
        curveTo(13f, 15.55f, 12.55f, 16f, 12f, 16f)
        curveTo(11.45f, 16f, 11f, 15.55f, 11f, 15f)
        lineTo(11f, 10f)
        lineTo(9f, 10f)
        curveTo(8.45f, 10f, 8f, 9.55f, 8f, 9f)
        curveTo(8f, 8.45f, 8.45f, 8f, 9f, 8f)
        close()
    }
}

fun bulletListGlyph(neutral: Color, accent: Color): ImageVector = glyph("BulletList") {
    ListRows.forEach { cy ->
        path(fill = SolidColor(accent)) {
            moveTo(3.2f, cy - 1.8f)
            lineTo(6.8f, cy - 1.8f)
            lineTo(6.8f, cy + 1.8f)
            lineTo(3.2f, cy + 1.8f)
            close()
        }
    }
    listRules(neutral)
}

/**
 * Numerals are kept to roughly 4 units tall and stroked thin. Anything larger closes the gaps
 * between the three rows, and they smear into one another at the 18dp the ribbon draws them at.
 */
fun numberedListGlyph(neutral: Color, accent: Color): ImageVector = glyph("NumberedList") {
    strokedAccent(accent, 1.15f) {             // 1
        moveTo(3.75f, 4.85f)
        lineTo(4.85f, 4.0f)
        lineTo(4.85f, 8.0f)
    }
    strokedAccent(accent, 1.15f) {             // 2
        moveTo(3.2f, 10.85f)
        quadTo(3.5f, 9.7f, 4.8f, 9.75f)
        quadTo(6.1f, 9.85f, 5.6f, 11.3f)
        quadTo(5.1f, 12.6f, 3.2f, 14.0f)
        lineTo(5.95f, 14.0f)
    }
    strokedAccent(accent, 1.15f) {             // 3
        moveTo(3.3f, 16.3f)
        quadTo(4.9f, 15.5f, 5.4f, 16.9f)
        quadTo(5.7f, 17.9f, 4.5f, 17.95f)
        quadTo(6.1f, 18.0f, 5.8f, 19.3f)
        quadTo(5.3f, 20.5f, 3.35f, 19.75f)
    }
    listRules(neutral)
}

fun todoListGlyph(neutral: Color, accent: Color): ImageVector = glyph("TodoList") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.7f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.2f, 4.2f)
        lineTo(19.8f, 4.2f)
        lineTo(19.8f, 19.8f)
        lineTo(4.2f, 19.8f)
        close()
    }
    strokedAccent(accent, 2.1f) {
        moveTo(7.6f, 12.2f)
        lineTo(10.7f, 15.3f)
        lineTo(16.6f, 8.6f)
    }
}

/** The neutral X shared by sub- and superscript, positioned by its vertical extent. */
private fun ImageVector.Builder.cross(neutral: Color, top: Float, bottom: Float) {
    path(stroke = SolidColor(neutral), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(2.8f, top)
        lineTo(12.6f, bottom)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(12.6f, top)
        lineTo(2.8f, bottom)
    }
}

/** The accented 2, drawn from [top] so one shape serves both scripts. */
private fun ImageVector.Builder.smallTwo(accent: Color, top: Float) {
    strokedAccent(accent, 1.5f) {
        moveTo(15.2f, top + 1.1f)
        curveTo(15.5f, top - 0.4f, 19.6f, top - 0.3f, 19.2f, top + 1.8f)
        curveTo(19.0f, top + 3.1f, 15.5f, top + 4.4f, 15.3f, top + 5.6f)
        lineTo(19.8f, top + 5.6f)
    }
}

fun subscriptGlyph(neutral: Color, accent: Color): ImageVector = glyph("Subscript") {
    cross(neutral, 4.4f, 15.6f)
    smallTwo(accent, 14.4f)
}

fun superscriptGlyph(neutral: Color, accent: Color): ImageVector = glyph("Superscript") {
    cross(neutral, 8.4f, 19.6f)
    smallTwo(accent, 4.0f)
}

fun stylesGlyph(neutral: Color, accent: Color): ImageVector = glyph("Styles") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(2.8f, 16.8f)
        lineTo(8.2f, 4.2f)
        lineTo(13.6f, 16.8f)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
        moveTo(5.2f, 12.2f)
        lineTo(11.2f, 12.2f)
    }
    strokedAccent(accent, 2.3f) {              // the brush stroke, tucked under the A's baseline
        moveTo(8.8f, 19.3f)
        curveTo(13.2f, 20.8f, 18.4f, 18.2f, 20.9f, 13.4f)
    }
}

/** The bar both colour glyphs sit on. Shows the selected colour, so it is never a theme value. */
private fun ImageVector.Builder.swatchBar(swatch: Color) {
    path(fill = SolidColor(swatch)) {
        moveTo(3.2f, 19.2f)
        lineTo(20.8f, 19.2f)
        lineTo(20.8f, 22.0f)
        lineTo(3.2f, 22.0f)
        close()
    }
}

fun fontColorGlyph(neutral: Color, swatch: Color): ImageVector = glyph("FontColor") {
    path(
        stroke = SolidColor(neutral),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4.4f, 16.4f)
        lineTo(10.4f, 4.2f)
        lineTo(16.4f, 16.4f)
    }
    path(stroke = SolidColor(neutral), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.0f, 11.8f)
        lineTo(13.8f, 11.8f)
    }
    swatchBar(swatch)
}

/** The Android ribbon's two-tone artwork with neutral paths recoloured for the pressed state. */
class DocumentRibbonIcons(neutral: Color, accent: Color) {
    val insertText = insertTextGlyph(neutral, accent)
    val bulletList = bulletListGlyph(neutral, accent)
    val numberedList = numberedListGlyph(neutral, accent)
    val todoList = todoListGlyph(neutral, accent)
    val subscript = subscriptGlyph(neutral, accent)
    val superscript = superscriptGlyph(neutral, accent)
    val styles = stylesGlyph(neutral, accent)
}

@Composable
fun rememberDocumentRibbonIcons(
    idleNeutral: Color,
    activeNeutral: Color,
    accent: Color,
): Pair<DocumentRibbonIcons, DocumentRibbonIcons> =
    remember(idleNeutral, activeNeutral, accent) {
        DocumentRibbonIcons(idleNeutral, accent) to DocumentRibbonIcons(activeNeutral, accent)
    }
