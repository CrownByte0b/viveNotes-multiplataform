package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The File tab's two-tone glyphs, copied from the Android `RibbonGlyphs.kt`: Material Symbols split
 * into their meaning-carrying subpath, in the accent, and the rest. Export's book keeps its
 * bookmark accented; Import's arrow is the accent that tells the pair apart.
 */

private fun fileGlyph(name: String, neutral: List<String>, accent: List<String>, neutralColor: Color, accentColor: Color) =
    ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
        neutral.forEach { addPath(addPathNodes(it), fill = SolidColor(neutralColor)) }
        accent.forEach { addPath(addPathNodes(it), fill = SolidColor(accentColor)) }
    }.build()

private const val BookCover =
    "M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h480q33 0 56.5 " +
        "23.5T800-800v640q0 33-23.5 56.5T720-80H240Zm0-80h480v-640h-80v245q0 12-10 " +
        "17.5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 6-20.5.5T440-555v-245H240v640Z"

private const val BookMark =
    "M640-800v245q0 12-10 17.5t-20-.5l-49-30q-10-6-20.5-6t-20.5 6l-49 30q-10 " +
        "6-20.5.5T440-555v-245Z"

private const val ImportNotebookCover =
    "m 240,-80 c -22,0 -40.83333,-7.833333 -56.5,-23.5 C 167.83333,-119.16667 160,-138 160,-160 " +
        "v -640 c 0,-22 7.83333,-40.83333 23.5,-56.5 15.66667,-15.66667 34.5,-23.5 56.5,-23.5 " +
        "h 480 c 22,0 40.83333,7.83333 56.5,23.5 15.66667,15.66667 23.5,34.5 23.5,56.5 v 640 " +
        "c 0,22 -7.83333,40.83333 -23.5,56.5 C 760.83333,-87.833333 742,-80 720,-80 Z " +
        "m 0,-80 H 720 V -800 H 640 440 240 Z m 0,0 v -640 z"

private const val ImportNotebookArrow =
    "m 589.65093,-799.95709 v 240.2522 l 76.71592,-76.64487 41.30858,42.74425 " +
        "-147.53063,147.39399 -147.53062,-147.39399 41.30857,-42.74425 76.71593,76.64487 " +
        "v -240.2522 z"

/** Export Notebook — the bookmark accented, answering Import's accented arrow beside it. */
fun exportNotebookGlyph(neutral: Color, accent: Color): ImageVector =
    fileGlyph("ExportNotebook", listOf(BookCover), listOf(BookMark), neutral, accent)

/** Import — a book with an arrow coming down into it through the cover. */
fun importNotebookGlyph(neutral: Color, accent: Color): ImageVector =
    fileGlyph("ImportNotebook", listOf(ImportNotebookCover), listOf(ImportNotebookArrow), neutral, accent)
