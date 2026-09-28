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

/** Material Symbols Rounded `history`, with the hands accented. */
fun versionHistoryGlyph(neutral: Color, accent: Color): ImageVector = fileGlyph("VersionHistory",
    listOf("M480-120q-126 0-223-76.5T131-392q-4-15 6-27.5t27-14.5q16-2 29 6t18 24q24 90 99 " +
        "147t170 57q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 " +
        "88h70q17 0 28.5 11.5T360-600q0 17-11.5 28.5T320-560H160q-17 " +
        "0-28.5-11.5T120-600v-160q0-17 11.5-28.5T160-800q17 0 28.5 11.5T200-760v54q51-64 " +
        "124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77" +
        " 114q-48.5 48.5-114 77T480-120Z"),
    listOf("M520,-496l100 100q11 11 11 28t-11 28q-11 11-28 " +
        "11t-28-11L452-452q-6-6-9-13.5t-3-15.5v-159q0-17 11.5-28.5T480-680q17 0 28.5 " +
        "11.5T520-640v144Z"), neutral, accent)

private const val BinBody =
    "M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 " +
        "11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 " +
        "28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 " +
        "56.5T680-120H280ZM680,-720H280v520h400v-520Z"

/** Material Symbols Rounded `restore_from_trash`. */
fun deletedItemsGlyph(neutral: Color, accent: Color): ImageVector = fileGlyph("DeletedItems", listOf(BinBody),
    listOf("M440,-486v126q0 17 11.5 28.5T480-320q17 0 28.5-11.5T520-360v-126l36 35q11 11 27.5 " +
        "11t28.5-12q11-11 11-28t-11-28L508-612q-12-12-28-12t-28 12L348-508q-11 11-11.5 " +
        "27.5T348-452q11 11 27.5 11.5T404-451l36-35Z"), neutral, accent)

/** Material Symbols Rounded `delete`, with its contents in the destructive colour. */
fun deleteNotebookGlyph(neutral: Color, warning: Color): ImageVector = fileGlyph("DeleteNotebook", listOf(BinBody),
    listOf("M400-280q17 0 28.5-11.5T440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 " +
        "11.5T360-600v280q0 17 11.5 28.5T400-280ZM560,-280q17 0 " +
        "28.5-11.5T600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 " +
        "28.5T560-280Z"), neutral, warning)
