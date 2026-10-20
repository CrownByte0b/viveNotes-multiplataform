package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Android ObjectTooltip Material Symbols Rounded paths. */
object ObjectSymbols {
    val Functions: ImageVector by lazy {
        ImageVector.Builder("Functions", 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
            addPath(addPathNodes("M500-480 253-708q-6-6-9.5-13.5T240-737v-23q0-17 11.5-28.5T280-800h380q25 0 42.5 17.5T720-740q0 25-17.5 42.5T660-680H431l184 171q13 12 13 29t-13 29L431-280h229q25 0 42.5 17.5T720-220q0 25-17.5 42.5T660-160H269q-12 0-20.5-8.5T240-189v-38q0-6 2-11.5t7-10.5l251-231Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    /** Google Material Symbols Rounded, expand_content (24 px). */
    val ExpandContent: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExpandContent", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M280-280h120q17 0 28.5 11.5T440-240q0 17-11.5 28.5T400-200H240q-17 0-28.5-11.5T200-240v-160q0-17 11.5-28.5T240-440q17 0 28.5 11.5T280-400v120Zm400-400H560q-17 0-28.5-11.5T520-720q0-17 11.5-28.5T560-760h160q17 0 28.5 11.5T760-720v160q0 17-11.5 28.5T720-520q-17 0-28.5-11.5T680-560v-120Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Delete: ImageVector by lazy {
        ImageVector.Builder(
            name = "Delete", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M280-120q-33 0-56.5-23.5T200-200v-520q-17 0-28.5-11.5T160-760q0-17 11.5-28.5T200-800h160q0-17 11.5-28.5T400-840h160q17 0 28.5 11.5T600-800h160q17 0 28.5 11.5T800-760q0 17-11.5 28.5T760-720v520q0 33-23.5 56.5T680-120H280Zm400-600H280v520h400v-520ZM400-280q17 0 28.5-11.5T440-320v-280q0-17-11.5-28.5T400-640q-17 0-28.5 11.5T360-600v280q0 17 11.5 28.5T400-280Zm160 0q17 0 28.5-11.5T600-320v-280q0-17-11.5-28.5T560-640q-17 0-28.5 11.5T520-600v280q0 17 11.5 28.5T560-280ZM280-720v520-520Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Lock", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80ZM240-160v-400 400Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val LockOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "LockOpen", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h360v-80q0-50-35-85t-85-35q-42 0-73.5 25.5T364-751q-4 14-16.5 22.5T320-720q-17 0-28.5-11t-8.5-26q14-69 69-116t128-47q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm0-80h480v-400H240v400Zm240-120q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM240-160v-400 400Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    /** Google Material Symbols Rounded, background_replace: a picture's background colour is set. */
    val BackgroundReplace: ImageVector by lazy {
        ImageVector.Builder(
            name = "BackgroundReplace", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes(BACKGROUND_REPLACE), fill = SolidColor(Color.Black))
        }.build()
    }

    /**
     * Android `ic_background_none`, not a Google symbol: [BackgroundReplace] with an X in its top
     * right corner. The clip is the whole viewport with a circle wound the other way, which non-zero
     * filling turns into a hole, so the X sits in clear space instead of on the stripes.
     */
    val BackgroundNone: ImageVector by lazy {
        ImageVector.Builder(
            name = "BackgroundNone", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f, clipPathData = addPathNodes(
            "M0-960H960V0H0Z M1020-800A220 220 0 1 0 580-800A220 220 0 1 0 1020-800Z")) {
            addPath(addPathNodes(BACKGROUND_REPLACE), fill = SolidColor(Color.Black))
        }.group(translationY = 960f) {
            addPath(addPathNodes("M700-900L900-700M900-900L700-700"), stroke = SolidColor(Color.Black),
                strokeLineWidth = 56f, strokeLineCap = StrokeCap.Round)
        }.build()
    }

    private fun symbol(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
            addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        }.build()

    /** Google Material Symbols Rounded, table_rows: the Table Class toolkit's Rows menu. */
    val TableRows: ImageVector by lazy {
        symbol("TableRows", "M760-200v-120H200v120h560Zm0-200v-160H200v160h560Zm0-240v-120H200v120h560ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Z")
    }

    /** Google Material Symbols Rounded, view_column: the Table Class toolkit's Columns menu. */
    val ViewColumn: ImageVector by lazy {
        symbol("ViewColumn", "M121-280v-400q0-33 23.5-56.5T201-760h559q33 0 56.5 23.5T840-680v400q0 33-23.5 56.5T760-200H201q-33 0-56.5-23.5T121-280Zm79 0h133v-400H200v400Zm213 0h133v-400H413v400Zm213 0h133v-400H626v400Z")
    }

    /** Google Material Symbols Rounded, add_row_below: insert a row below the held one. */
    val AddRowBelow: ImageVector by lazy {
        symbol("AddRowBelow", "M760-560v-240H200v240h560ZM200-160q-33 0-56.5-23.5T120-240v-560q0-33 23.5-56.5T200-880h560q33 0 56.5 23.5T840-800v560q0 33-23.5 56.5T760-160h-40q-17 0-28.5-11.5T680-200q0-17 11.5-28.5T720-240h40v-240H200v240h40q17 0 28.5 11.5T280-200q0 17-11.5 28.5T240-160h-40Zm251.5 68.5Q440-103 440-120v-40h-40q-17 0-28.5-11.5T360-200q0-17 11.5-28.5T400-240h40v-40q0-17 11.5-28.5T480-320q17 0 28.5 11.5T520-280v40h40q17 0 28.5 11.5T600-200q0 17-11.5 28.5T560-160h-40v40q0 17-11.5 28.5T480-80q-17 0-28.5-11.5ZM480-480Zm0-80v80-80Zm0 0Z")
    }

    /** Google Material Symbols Rounded, add_column_right: insert a column right of the held one. */
    val AddColumnRight: ImageVector by lazy {
        symbol("AddColumnRight", "M400-760H160v560h240v-560ZM160-120q-33 0-56.5-23.5T80-200v-560q0-33 23.5-56.5T160-840h560q33 0 56.5 23.5T800-760v40q0 17-11.5 28.5T760-680q-17 0-28.5-11.5T720-720v-40H480v560h240v-40q0-17 11.5-28.5T760-280q17 0 28.5 11.5T800-240v40q0 33-23.5 56.5T720-120H160Zm571.5-251.5Q720-383 720-400v-40h-40q-17 0-28.5-11.5T640-480q0-17 11.5-28.5T680-520h40v-40q0-17 11.5-28.5T760-600q17 0 28.5 11.5T800-560v40h40q17 0 28.5 11.5T880-480q0 17-11.5 28.5T840-440h-40v40q0 17-11.5 28.5T760-360q-17 0-28.5-11.5ZM480-480Zm-80 0h80-80Zm0 0Z")
    }

    /** Google Material Symbols Rounded, remove: delete the held row or column, not the object. */
    val Remove: ImageVector by lazy {
        symbol("Remove", "M240-440q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h480q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H240Z")
    }

    /** Google Material Symbols Rounded, block: a table or shape with no fill. */
    val Block: ImageVector by lazy {
        symbol("Block", "M324-111.5Q251-143 197-197t-85.5-127Q80-397 80-480t31.5-156Q143-709 197-763t127-85.5Q397-880 480-880t156 31.5Q709-817 763-763t85.5 127Q880-563 880-480t-31.5 156Q817-251 763-197t-127 85.5Q563-80 480-80t-156-31.5ZM480-160q54 0 104-17.5t92-50.5L228-676q-33 42-50.5 92T160-480q0 134 93 227t227 93Zm252-124q33-42 50.5-92T800-480q0-134-93-227t-227-93q-54 0-104 17.5T284-732l448 448ZM480-480Z")
    }

    private const val BACKGROUND_REPLACE = "M120-574v-85l181-181h85L120-574Zm0-196v-70h70l-70 70Zm527 67q-10-11-21.5-21.5T602-743l97-97h85L647-703ZM220-361l77-77q7 11 14.5 20t16.5 17q-28 7-56.5 17.5T220-361Zm480-197v-2q0-19-3-37t-9-35l152-152v86L700-558ZM436-776l65-64h85l-64 64q-11-2-21-3t-21-1q-11 0-22 1t-22 3ZM120-375v-85l144-144q-2 11-3 22t-1 22q0 11 1 21t3 20L120-375Zm709 83q-8-12-18.5-23T788-335l52-52v85l-11 10Zm-116-82q-7-3-14-5.5t-14-4.5q-9-3-17.5-6t-17.5-5l190-191v86L713-374Zm-233-26q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47Zm56.5-103.5Q560-527 560-560t-23.5-56.5Q513-640 480-640t-56.5 23.5Q400-593 400-560t23.5 56.5Q447-480 480-480t56.5-23.5ZM160-160v-31q0-34 17-63t47-44q51-26 115.5-44T480-360q76 0 140.5 18T736-298q30 15 47 44t17 63v31q0 17-11.5 28.5T760-120H200q-17 0-28.5-11.5T160-160Zm81-40h478q-2-9-7-15.5T699-226q-36-18-91.5-36T480-280q-72 0-127.5 18T261-226q-8 4-13 11t-7 15Zm239 0Zm0-360Z"
}
