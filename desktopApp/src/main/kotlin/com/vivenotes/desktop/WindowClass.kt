package com.vivenotes.desktop

import java.awt.Toolkit

/**
 * The production name Linux desktops know the app's windows by: Wayland's `app_id` and X11's
 * `WM_CLASS`. The development profile uses `vivenotes-dev`.
 */
internal const val WINDOW_CLASS = "vivenotes"

/**
 * Sets the profile's app id for native Wayland. JetBrains Runtime's WLToolkit reads the `awt.app.id`
 * property as each window is created, so this only has to come before the first one.
 */
internal fun useWaylandWindowClass(profile: DesktopProfile = DesktopProfile.PRODUCTION) {
    System.setProperty("awt.app.id", profile.windowClass)
}

/**
 * Sets the profile's WM_CLASS for X11. XToolkit fixes its class name from the main class when it starts
 * and has no property for it, so this starts the toolkit and replaces the name before any window
 * exists. It needs `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`, which the launch passes on
 * Linux; without it, or on another toolkit, the default name stays. Returns whether it was set.
 */
internal fun useX11WindowClass(profile: DesktopProfile = DesktopProfile.PRODUCTION): Boolean {
    Toolkit.getDefaultToolkit()
    return runCatching {
        val field = Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, profile.windowClass)
    }.onFailure { System.err.println("ViveNotes: the X11 window class stays the default: $it") }.isSuccess
}
