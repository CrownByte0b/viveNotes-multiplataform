package com.vivenotes.workspace

/**
 * How this device's pointing hardware is used: Settings → Hardware → Stylus.
 *
 * Android's `drawWithFinger`, which it keeps in its own preferences because it describes the
 * machine — is there a pen, and should a finger write without one — rather than the notes. The desktop
 * keeps it in its config directory for the same reason; it never syncs.
 */
data class InputSettings(
    /**
     * Whether a finger uses the armed drawing tool. Off, as on Android: a finger moves the page and
     * leaves the pen, eraser, shape and lasso tools to a stylus or mouse.
     */
    val drawWithFinger: Boolean = false,
)
