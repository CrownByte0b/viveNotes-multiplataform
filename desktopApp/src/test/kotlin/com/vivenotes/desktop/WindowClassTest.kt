package com.vivenotes.desktop

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Linux desktops match the app's windows by this name, on Wayland and X11 alike. */
class WindowClassTest {
    @Test
    fun theWindowClassIsVivenotes() {
        assertEquals("vivenotes", WINDOW_CLASS)
    }

    @Test
    fun nativeWaylandReadsItFromTheAppIdProperty() {
        val before = System.getProperty("awt.app.id")
        try {
            useWaylandWindowClass()
            assertEquals("vivenotes", System.getProperty("awt.app.id"))
        } finally {
            if (before == null) System.clearProperty("awt.app.id") else System.setProperty("awt.app.id", before)
        }
    }

    /**
     * XToolkit names windows after the main class unless its field is replaced before the first one.
     * Needs Linux and a display — the build's Xvfb run — and the launch's `--add-opens`, which the
     * test JVM shares.
     */
    @Test
    fun x11TakesItFromXToolkit() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        assumeTrue(java.awt.Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit")

        assertTrue(useX11WindowClass())
        val name = Class.forName("sun.awt.X11.XToolkit").getDeclaredMethod("getAWTAppClassName")
            .apply { isAccessible = true }.invoke(null)
        assertEquals("vivenotes", name)
    }
}
