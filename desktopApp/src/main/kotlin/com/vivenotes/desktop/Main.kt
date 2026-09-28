package com.vivenotes.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    val profile = DesktopProfile.fromProperty()
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
            useWaylandWindowClass(profile)
            launchWayland(DesktopNotes.open(profile), profile)
        }
        WindowBackend.STANDARD -> {
            if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
                System.setProperty("awt.toolkit.name", "XToolkit")
                useX11WindowClass(profile)
            }
            launchStandardWindow(DesktopNotes.open(profile), profile)
        }
    }
}

private fun launchStandardWindow(notes: DesktopNotes, profile: DesktopProfile) {
    notes.start()
    val monitor = primaryMonitorArea()
    val initialSize = initialWindowSize(monitor)
    application {
        Window(
            // The window stays until the notes are written and closed; then the application ends.
            onCloseRequest = { notes.close(::exitApplication) },
            title = profile.windowTitle,
            state = rememberWindowState(size = DpSize(initialSize.width.dp, initialSize.height.dp)),
        ) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(720.coerceAtMost(monitor.workArea.width),
                    540.coerceAtMost(monitor.workArea.height))
            }
            App(notes.session, remember(window) { notes.pictures(window) }, notes.interfaceSettings,
                notes::updateInterfaceSettings, notes.viewSettings, notes::updateViewSettings,
                notes.keyBindings, notes::updateKeyBindings,
                notebookFiles = remember(window) { notes.notebookFiles(window) }, thumbnails = notes.thumbnails,
                onEditorDefaultsChange = notes::updateEditorDefaults)
        }
    }
}
