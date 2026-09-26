package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Rounded from the Android app for the desktop header bar. */
object ShellSymbols {
    val Menu: ImageVector by lazy {
        ImageVector.Builder("Menu", 24.dp, 24.dp, 960f, 960f).group(translationY = 960f) {
            addPath(addPathNodes("M160-240q-17 0-28.5-11.5T120-280q0-17 11.5-28.5T160-320h640q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240H160Zm0-200q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-200q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h640q17 0 28.5 11.5T840-680q0 17-11.5 28.5T800-640H160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Undo: ImageVector by lazy {
        ImageVector.Builder("Undo", 24.dp, 24.dp, 960f, 960f, autoMirror = true)
            .group(translationY = 960f) {
                addPath(addPathNodes("M320-200q-17 0-28.5-11.5T280-240q0-17 11.5-28.5T320-280h244q63 0 109.5-40T720-420q0-60-46.5-100T564-560H312l76 76q11 11 11 28t-11 28q-11 11-28 11t-28-11L188-572q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l144-144q11-11 28-11t28 11q11 11 11 28t-11 28l-76 76h252q97 0 166.5 63T800-420q0 94-69.5 157T564-200H320Z"), fill = SolidColor(Color.Black))
            }.build()
    }

    val Redo: ImageVector by lazy {
        ImageVector.Builder("Redo", 24.dp, 24.dp, 960f, 960f, autoMirror = true)
            .group(translationY = 960f) {
                addPath(addPathNodes("M648-560H396q-63 0-109.5 40T240-420q0 60 46.5 100T396-280h244q17 0 28.5 11.5T680-240q0 17-11.5 28.5T640-200H396q-97 0-166.5-63T160-420q0-94 69.5-157T396-640h252l-76-76q-11-11-11-28t11-28q11-11 28-11t28 11l144 144q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L628-428q-11 11-28 11t-28-11q-11-11-11-28t11-28l76-76Z"), fill = SolidColor(Color.Black))
            }.build()
    }
}
