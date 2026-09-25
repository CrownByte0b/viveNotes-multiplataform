package com.vivenotes.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vivenotes.App
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ViveNotes",
        state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(720, 540)
        }
        App()
    }
}
