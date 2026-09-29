package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** The Settings glyphs from Android's RibbonGlyphs.kt, preserving its two-tone path splits. */
private fun settingsMaterialGlyph(name: String, neutral: List<androidx.compose.ui.graphics.vector.PathNode>,
    detail: List<androidx.compose.ui.graphics.vector.PathNode>, neutralColor: Color, accent: Color): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
        addPath(neutral, fill = SolidColor(neutralColor))
        addPath(detail, fill = SolidColor(accent))
    }.build()

fun integratedGlyph(neutral: Color, accent: Color): ImageVector =
    settingsMaterialGlyph("Integrated", ChipBody, ChipCore, neutral, accent)

fun hardwareGlyph(neutral: Color, accent: Color): ImageVector =
    settingsMaterialGlyph("Hardware", KeyboardFrame, KeyboardKeys, neutral, accent)

fun aboutGlyph(neutral: Color, accent: Color): ImageVector =
    settingsMaterialGlyph("About", InfoRing, InfoMark, neutral, accent)

/** Android's video preview card and play triangle. */
fun linkPreviewGlyph(neutral: Color, accent: Color): ImageVector =
    ImageVector.Builder("LinkPreview", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M3.2 5.6L20.8 5.6L20.8 18.4L3.2 18.4Z"),
            stroke = SolidColor(neutral), strokeLineWidth = 1.7f, strokeLineJoin = StrokeJoin.Round,
            strokeLineCap = StrokeCap.Round)
        addPath(addPathNodes("M10.1 8.6L15.6 12L10.1 15.4Z"), fill = SolidColor(accent))
    }.build()

private val ChipBody = addPathNodes(
    "M360,-160v-40h-80q-33 0-56.5-23.5T200-280v-80h-40q-17 0-28.5-11.5T120-400q0-17 " +
        "11.5-28.5T160-440h40v-80h-40q-17 0-28.5-11.5T120-560q0-17 " +
        "11.5-28.5T160-600h40v-80q0-33 23.5-56.5T280-760h80v-40q0-17 11.5-28.5T400-840q17 0 " +
        "28.5 11.5T440-800v40h80v-40q0-17 11.5-28.5T560-840q17 0 28.5 11.5T600-800v40h80q33 0" +
        " 56.5 23.5T760-680v80h40q17 0 28.5 11.5T840-560q0 17-11.5 28.5T800-520h-40v80h40q17 " +
        "0 28.5 11.5T840-400q0 17-11.5 28.5T800-360h-40v80q0 33-23.5 56.5T680-200h-80v40q0 " +
        "17-11.5 28.5T560-120q-17 0-28.5-11.5T520-160v-40h-80v40q0 17-11.5 28.5T400-120q-17 " +
        "0-28.5-11.5T360-160ZM680,-280v-400H280v400h400Z",
)

private val ChipCore = addPathNodes(
    "M360-400v-160q0-17 11.5-28.5T400-600h160q17 0 28.5 11.5T600-560v160q0 17-11.5 " +
        "28.5T560-360H400q-17 0-28.5-11.5T360-400ZM440,-440h80v-80h-80v80Z",
)

private val KeyboardFrame = addPathNodes(
    "M160-200q-33 0-56.5-23.5T80-280v-400q0-33 23.5-56.5T160-760h640q33 0 56.5 " +
        "23.5T880-680v400q0 33-23.5 56.5T800-200H160ZM160,-280h640v-400H160v400Z",
)

private val KeyboardKeys = addPathNodes(
    "M360,-320h240q17 0 28.5-11.5T640-360q0-17-11.5-28.5T600-400H360q-17 0-28.5 " +
        "11.5T320-360q0 17 11.5 28.5T360-320ZM268.5,-571.5Q280-583 280-600t-11.5-28.5Q257-640" +
        " 240-640t-28.5 11.5Q200-617 200-600t11.5 28.5Q223-560 " +
        "240-560t28.5-11.5ZM388.5,-571.5Q400-583 400-600t-11.5-28.5Q377-640 360-640t-28.5 " +
        "11.5Q320-617 320-600t11.5 28.5Q343-560 360-560t28.5-11.5ZM508.5,-571.5Q520-583 " +
        "520-600t-11.5-28.5Q497-640 480-640t-28.5 11.5Q440-617 440-600t11.5 28.5Q463-560 " +
        "480-560t28.5-11.5ZM628.5,-571.5Q640-583 640-600t-11.5-28.5Q617-640 600-640t-28.5 " +
        "11.5Q560-617 560-600t11.5 28.5Q583-560 600-560t28.5-11.5ZM748.5,-571.5Q760-583 " +
        "760-600t-11.5-28.5Q737-640 720-640t-28.5 11.5Q680-617 680-600t11.5 28.5Q703-560 " +
        "720-560t28.5-11.5ZM268.5,-451.5Q280-463 280-480t-11.5-28.5Q257-520 240-520t-28.5 " +
        "11.5Q200-497 200-480t11.5 28.5Q223-440 240-440t28.5-11.5ZM388.5,-451.5Q400-463 " +
        "400-480t-11.5-28.5Q377-520 360-520t-28.5 11.5Q320-497 320-480t11.5 28.5Q343-440 " +
        "360-440t28.5-11.5ZM508.5,-451.5Q520-463 520-480t-11.5-28.5Q497-520 480-520t-28.5 " +
        "11.5Q440-497 440-480t11.5 28.5Q463-440 480-440t28.5-11.5ZM628.5,-451.5Q640-463 " +
        "640-480t-11.5-28.5Q617-520 600-520t-28.5 11.5Q560-497 560-480t11.5 28.5Q583-440 " +
        "600-440t28.5-11.5ZM748.5,-451.5Q760-463 760-480t-11.5-28.5Q737-520 720-520t-28.5 " +
        "11.5Q680-497 680-480t11.5 28.5Q703-440 720-440t28.5-11.5Z",
)

private val InfoRing = addPathNodes(
    "M480,-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 " +
        "127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 " +
        "156T763-197q-54 54-127 85.5T480-80ZM480,-160q134 0 " +
        "227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Z",
)

private val InfoMark = addPathNodes(
    "M480-280q17 0 28.5-11.5T520-320v-160q0-17-11.5-28.5T480-520q-17 0-28.5 " +
        "11.5T440-480v160q0 17 11.5 28.5T480-280ZM480,-600q17 0 " +
        "28.5-11.5T520-640q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640q0 17 11.5 " +
        "28.5T480-600Z",
)
