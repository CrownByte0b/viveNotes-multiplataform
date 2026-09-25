package com.vivenotes.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vivenotes.App
import java.awt.Dimension

internal enum class WindowBackend { NATIVE_WAYLAND, STANDARD }

internal fun selectWindowBackend(
    args: Array<String>,
    osName: String,
    sessionType: String?,
    waylandDisplay: String?,
): WindowBackend {
    require(args.all { it == "--x11" }) { "Unknown option. Supported option: --x11" }
    return if (
        args.none { it == "--x11" } &&
        osName.startsWith("Linux", ignoreCase = true) &&
        (sessionType.equals("wayland", ignoreCase = true) || !waylandDisplay.isNullOrBlank())
    ) {
        WindowBackend.NATIVE_WAYLAND
    } else {
        WindowBackend.STANDARD
    }
}

fun main(args: Array<String>) {
    when (
        selectWindowBackend(
            args = args,
            osName = System.getProperty("os.name"),
            sessionType = System.getenv("XDG_SESSION_TYPE"),
            waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
        )
    ) {
        WindowBackend.NATIVE_WAYLAND -> {
            System.setProperty("awt.toolkit.name", "WLToolkit")
            launchWayland()
        }
        WindowBackend.STANDARD -> {
            if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
                System.setProperty("awt.toolkit.name", "XToolkit")
            }
            launchStandardWindow()
        }
    }
}

private fun launchStandardWindow() = application {
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
