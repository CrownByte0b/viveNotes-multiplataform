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

private const val NotebookCover =
    "M2.99487 10.3988C3.31176 10.561 3.64645 10.6933 3.99524 10.7921V16C3.99524 16.5523 4.44312 " +
        "17 4.9956 17H12.9985C13.551 17 13.9989 16.5523 13.9989 16V4C13.9989 3.44772 13.551 3 " +
        "12.9985 3H10.4008C10.2178 2.64222 9.99683 2.30711 9.74311 2H12.9985C14.1035 2 14.9993 " +
        "2.89543 14.9993 4V16C14.9993 17.1046 14.1035 18 12.9985 18H4.9956C3.89063 18 2.99487 " +
        "17.1046 2.99487 16V10.3988Z" +
        "M15.9996 6H16.4998C16.7761 6 17 6.22386 17 6.5V8C17 8.27614 16.7761 8.5 16.4998 " +
        "8.5H15.9996V6Z" +
        "M16.4998 9.5H15.9996V12H16.4998C16.7761 12 17 11.7761 17 11.5V10C17 9.72386 16.7761 " +
        "9.5 16.4998 9.5Z" +
        "M15.9996 13H16.4998C16.7761 13 17 13.2239 17 13.5V15C17 15.2761 16.7761 15.5 16.4998 " +
        "15.5H15.9996V13Z"

private const val NotebookArrowBadge =
    "M1 5.5C1 3.01472 3.01546 1 5.50165 1C7.98784 1 10.0033 3.01472 10.0033 5.5C10.0033 7.98528 " +
        "7.98784 10 5.50165 10C3.01546 10 1 7.98528 1 5.5Z" +
        "M7.39879 6.39645L6.50202 7.29289V5.75C6.50202 4.23122 5.27034 3 3.75101 3H3.50092C3.22467 " +
        "3 3.00073 3.22386 3.00073 3.5C3.00073 3.77614 3.22467 4 3.50092 4H3.75101C4.71786 4 " +
        "5.50165 4.7835 5.50165 5.75V7.29289L4.60487 6.39645C4.40954 6.20118 4.09284 6.20118 " +
        "3.89751 6.39645C3.70217 6.59171 3.70217 6.90829 3.89751 7.10355L5.65027 8.85567C5.69774 " +
        "8.90256 5.75225 8.93802 5.81037 8.96206C5.86934 8.98651 5.93401 9 6.00183 9C6.06965 9 " +
        "6.13432 8.98651 6.19329 8.96206C6.25228 8.93766 6.30755 8.90149 6.35551 8.85355L8.10615 " +
        "7.10355C8.30148 6.90829 8.30148 6.59171 8.10615 6.39645C7.91082 6.20118 7.59412 6.20118 " +
        "7.39879 6.39645Z"

private const val MenuBookCovers =
    "M260-320q47 0 91.5 10.5T440-278v-394q-41-24-87-36t-93-12q-36 0-71.5 7T120-692v396q35-12 " +
        "69.5-18t70.5-6Z" +
        "M520-278q44-21 88.5-31.5T700-320q36 0 70.5 6t69.5 18v-396q-33-14-68.5-21t-71.5-7q-47 " +
        "0-93 12t-87 36v394Z" +
        "M480-181q-14 0-26.5-3.5T430-194q-39-23-82-34.5T260-240q-42 0-82.5 11T100-198q-21 " +
        "11-40.5-1T40-234v-482q0-11 5.5-21T62-752q46-24 96-36t102-12q58 0 113.5 15T480-740q51-30 " +
        "106.5-45T700-800q52 0 102 12t96 36q11 5 16.5 15t5.5 21v482q0 23-19.5 35t-40.5 " +
        "1q-37-20-77.5-31T700-240q-45 0-88 11.5T530-194q-11 6-23.5 9.5T480-181Z"

private const val MenuBookRules =
    "M560-609q0-9 6.5-18.5T581-640q29-10 58-15t61-5q20 0 39.5 2.5T778-651q9 2 15.5 10t6.5 18q0 " +
        "17-11 25t-28 4q-14-3-29.5-4.5T700-600q-26 0-51 5t-48 13q-18 7-29.5-1T560-609Z" +
        "M560-389q0-9 6.5-18.5T581-420q29-10 58-15t61-5q20 0 39.5 2.5T778-431q9 2 15.5 10t6.5 18q0 " +
        "17-11 25t-28 4q-14-3-29.5-4.5T700-380q-26 0-51 4.5T601-363q-18 7-29.5-.5T560-389Z" +
        "M560-499q0-9 6.5-18.5T581-530q29-10 58-15t61-5q20 0 39.5 2.5T778-541q9 2 15.5 10t6.5 18q0 " +
        "17-11 25t-28 4q-14-3-29.5-4.5T700-490q-26 0-51 5t-48 13q-18 7-29.5-1T560-499Z"

/** Android's Fluent notebook with its curved-arrow badge; Fluent UI System Icons, MIT licensed. */
fun closeNotebookGlyph(neutral: Color, accent: Color): ImageVector =
    ImageVector.Builder("CloseNotebook", 24.dp, 24.dp, 20f, 20f).apply {
        addPath(addPathNodes(NotebookCover), fill = SolidColor(neutral))
        addPath(addPathNodes(NotebookArrowBadge), fill = SolidColor(accent))
    }.build()

/** Android's Material Symbols Rounded open book with accented page rules. */
fun closedNotebooksGlyph(neutral: Color, accent: Color): ImageVector =
    fileGlyph("ClosedNotebooks", listOf(MenuBookCovers), listOf(MenuBookRules), neutral, accent)

// Android Export PDF glyph paths, copied verbatim.
private val PdfCards = "M320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 " +
        "33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480Z" +
        "M160-80q-33 0-56.5-23.5T80-160v-560h80v560h560v80H160Z"

private val PdfLetters = "M360-460h40v-80h40q17 0 28.5-11.5T480-580v-40q0-17-11.5-28.5T440-660h-80v200Zm40-120v-40h40v40h" +
        "-40Zm120 120h80q17 0 28.5-11.5T640-500v-120q0-17-11.5-28.5T600-660h-80v200Zm40-40v-120h40v" +
        "120h-40Zm120 40h40v-80h40v-40h-40v-40h40v-40h-80v200Z"

fun exportPdfGlyph(neutral: Color, accent: Color): ImageVector =
    fileGlyph("ExportPdf", listOf(PdfCards), listOf(PdfLetters), neutral, accent)
