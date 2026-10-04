package com.vivenotes.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vivenotes.App
import com.vivenotes.desktop.touch.DesktopTouch
import com.vivenotes.diagnostics.DebugLog
import java.awt.Dimension

internal enum class WindowBackend { NATIVE_WAYLAND, STANDARD }

internal data class LaunchOptions(val x11: Boolean, val debug: Boolean)

internal fun parseLaunchOptions(args: Array<String>): LaunchOptions {
    require(args.all { it == "--x11" || it == "--debug" }) {
        "Unknown option. Supported options: --x11, --debug"
    }
    return LaunchOptions(x11 = "--x11" in args, debug = "--debug" in args)
}

internal fun selectWindowBackend(
    args: Array<String>,
    osName: String,
    sessionType: String?,
    waylandDisplay: String?,
): WindowBackend {
    val options = parseLaunchOptions(args)
    return if (
        !options.x11 &&
        osName.startsWith("Linux", ignoreCase = true) &&
        (sessionType.equals("wayland", ignoreCase = true) || !waylandDisplay.isNullOrBlank())
    ) {
        WindowBackend.NATIVE_WAYLAND
    } else {
        WindowBackend.STANDARD
    }
}

fun main(args: Array<String>) {
    val options = parseLaunchOptions(args)
    val log = DebugLog(options.debug)
    val profile = DesktopProfile.fromProperty()
    val backend = selectWindowBackend(
        args = args,
        osName = System.getProperty("os.name"),
        sessionType = System.getenv("XDG_SESSION_TYPE"),
        waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
    )
    log.event("startup") { "profile=${profile.windowTitle}, backend=$backend" }
    when (backend) {
        WindowBackend.NATIVE_WAYLAND -> {
            System.setProperty("awt.toolkit.name", "WLToolkit")
            useWaylandWindowClass(profile)
            launchWayland(DesktopNotes.open(profile, log), profile, log)
        }
        WindowBackend.STANDARD -> {
            if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
                System.setProperty("awt.toolkit.name", "XToolkit")
                useX11WindowClass(profile)
                DesktopTouch.prepareX11(log)
            }
            launchStandardWindow(DesktopNotes.open(profile, log), profile, log)
        }
    }
}

private fun launchStandardWindow(notes: DesktopNotes, profile: DesktopProfile, log: DebugLog) {
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
                DesktopTouch.attachX11(window, log)
            }
            App(notes.session, remember(window) { notes.pictures(window) }, notes.interfaceSettings,
                notes::updateInterfaceSettings, notes.viewSettings, notes::updateViewSettings,
                notes.keyBindings, notes::updateKeyBindings,
                inputSettings = notes.inputSettings, onInputSettingsChange = notes::updateInputSettings,
                notebookFiles = remember(window) { notes.notebookFiles(window) }, thumbnails = notes.thumbnails,
                onEditorDefaultsChange = notes::updateEditorDefaults,
                onShapeSettingsChange = notes::updateShapeSettings, accountService = notes.accountService,
                appVersion = DesktopBuildInfo.version)
        }
    }
}
