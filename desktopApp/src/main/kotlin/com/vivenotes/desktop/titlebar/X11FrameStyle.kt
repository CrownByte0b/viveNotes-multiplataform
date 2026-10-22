package com.vivenotes.desktop.titlebar

import java.awt.Window

/**
 * X11: the window manager draws the frame. GTK-themed ones, GNOME's included, take its look from the
 * window's `_GTK_THEME_VARIANT`, as GTK applications and browsers set it; others ignore it. Set with
 * XToolkit's own atom, on its display connection; the launch opens `sun.awt.X11` for it.
 */
internal class X11FrameStyle(private val window: Window) : FrameStyle {
    override fun apply(dark: Boolean) {
        val atom = Class.forName("sun.awt.X11.XAtom")
        val variant = atom.getMethod("get", String::class.java).invoke(null, THEME_VARIANT)
        atom.getMethod("setPropertyUTF8", Long::class.javaPrimitiveType, String::class.java)
            .invoke(variant, xWindowOf(window), if (dark) "dark" else "light")
    }

    companion object {
        const val THEME_VARIANT = "_GTK_THEME_VARIANT"

        /** The top-level X window the window manager frames: the window's peer's own. */
        fun xWindowOf(window: Window): Long {
            val peer = checkNotNull(peerOf(window)) { "the window has no peer" }
            val getWindow = generateSequence<Class<*>>(peer.javaClass) { it.superclass }
                .firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == "getWindow" && it.parameterCount == 0 } }
                .apply { isAccessible = true }
            return getWindow.invoke(peer) as Long
        }
    }
}
