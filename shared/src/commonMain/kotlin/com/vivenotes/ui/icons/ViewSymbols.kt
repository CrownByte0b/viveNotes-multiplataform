package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Exact Material Symbols Rounded path data from the Android View tab and tool pane drawables. */
object ViewSymbols {
    private fun symbol(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
            addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        }.build()

    val ZoomIn: ImageVector by lazy {
        symbol("ZoomIn", "M340-540h-40q-17 0-28.5-11.5T260-580q0-17 11.5-28.5T300-620h40v-40q0-17 11.5-28.5T380-700q17 0 28.5 11.5T420-660v40h40q17 0 28.5 11.5T500-580q0 17-11.5 28.5T460-540h-40v40q0 17-11.5 28.5T380-460q-17 0-28.5-11.5T340-500v-40Zm40 220q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z")
    }

    val ZoomOut: ImageVector by lazy {
        symbol("ZoomOut", "M320-540q-17 0-28.5-11.5T280-580q0-17 11.5-28.5T320-620h120q17 0 28.5 11.5T480-580q0 17-11.5 28.5T440-540H320Zm60 220q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z")
    }

    /** The 100% command's page. */
    val Article: ImageVector by lazy {
        symbol("Article", "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm0-80h560v-560H200v560Zm0-560v560-560Zm120 480h200q17 0 28.5-11.5T560-320q0-17-11.5-28.5T520-360H320q-17 0-28.5 11.5T280-320q0 17 11.5 28.5T320-280Zm0-160h320q17 0 28.5-11.5T680-480q0-17-11.5-28.5T640-520H320q-17 0-28.5 11.5T280-480q0 17 11.5 28.5T320-440Zm0-160h320q17 0 28.5-11.5T680-640q0-17-11.5-28.5T640-680H320q-17 0-28.5 11.5T280-640q0 17 11.5 28.5T320-600Z")
    }

    /** Switch Background. */
    val WbSunny: ImageVector by lazy {
        symbol("WbSunny", "M440-840v-40q0-17 11.5-28.5T480-920q17 0 28.5 11.5T520-880v40q0 17-11.5 28.5T480-800q-17 0-28.5-11.5T440-840Zm0 760v-40q0-17 11.5-28.5T480-160q17 0 28.5 11.5T520-120v40q0 17-11.5 28.5T480-40q-17 0-28.5-11.5T440-80Zm440-360h-40q-17 0-28.5-11.5T800-480q0-17 11.5-28.5T840-520h40q17 0 28.5 11.5T920-480q0 17-11.5 28.5T880-440Zm-760 0H80q-17 0-28.5-11.5T40-480q0-17 11.5-28.5T80-520h40q17 0 28.5 11.5T160-480q0 17-11.5 28.5T120-440Zm670-293-14 14q-11 11-27.5 11T720-720q-11-11-11.5-27.5T719-776l15-15q11-12 28-12t29 12q12 12 11.5 29T790-733ZM241-184l-15 15q-11 12-28 12t-29-12q-12-12-11.5-29t12.5-29l14-14q11-11 27.5-11t28.5 12q11 11 11.5 27.5T241-184Zm492 14-14-14q-11-11-11-27.5t12-28.5q11-11 27.5-11.5T776-241l15 15q12 11 12 28t-12 29q-12 12-29 11.5T733-170ZM184-719l-15-15q-12-11-12-28t12-29q12-12 29-11.5t29 12.5l14 14q11 11 11 27.5T240-720q-11 11-27.5 11.5T184-719Zm296 479q-100 0-170-70t-70-170q0-100 70-170t170-70q100 0 170 70t70 170q0 100-70 170t-170 70Zm0-80q67 0 113.5-46.5T640-480q0-67-46.5-113.5T480-640q-67 0-113.5 46.5T320-480q0 67 46.5 113.5T480-320Zm0-160Z")
    }

    /** The tick beside a menu's current choice. */
    val Check: ImageVector by lazy {
        symbol("Check", "m382-354 339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z")
    }

    /** Closes a docked tool pane. */
    val Close: ImageVector by lazy {
        symbol("Close", "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z")
    }
}
