package com.vivenotes.desktop

import java.awt.Toolkit

/**
 * The name Linux desktops know the app's windows by: Wayland's `app_id` and X11's `WM_CLASS`. Window
 * rules, docks and `.desktop` files match on it. Without it JetBrains Runtime uses the launch
 * command and OpenJDK the main class, both `com…MainKt`-style names.
 */
internal const val WINDOW_CLASS = "vivenotes"

/**
 * Sets [WINDOW_CLASS] for native Wayland. JetBrains Runtime's WLToolkit reads the `awt.app.id`
 * property as each window is created, so this only has to come before the first one.
 */
internal fun useWaylandWindowClass() {
    System.setProperty("awt.app.id", WINDOW_CLASS)
}

/**
 * Sets [WINDOW_CLASS] for X11. XToolkit fixes its class name from the main class when it starts
 * and has no property for it, so this starts the toolkit and replaces the name before any window
 * exists. It needs `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`, which the launch passes on
 * Linux; without it, or on another toolkit, the default name stays. Returns whether it was set.
 */
internal fun useX11WindowClass(): Boolean {
    Toolkit.getDefaultToolkit()
    return runCatching {
        val field = Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, WINDOW_CLASS)
    }.onFailure { System.err.println("ViveNotes: the X11 window class stays the default: $it") }.isSuccess
}
