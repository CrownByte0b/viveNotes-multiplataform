package com.vivenotes.desktop.titlebar

import com.vivenotes.diagnostics.DebugLog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * X11 window managers that draw GTK-themed frames read the look from the window's property. Needs
 * XToolkit and a display — the build's Xvfb run — and the launch's `sun.awt.X11` and `sun.awt` access,
 * which the test JVM shares.
 */
class X11FrameStyleTest {
    @Test
    fun theWindowsThemeVariantFollowsTheAppsLook() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        assumeTrue(frameKindOf(Toolkit.getDefaultToolkit().javaClass.name) == FrameKind.X11)
        val logged = mutableListOf<String>()
        onSwing {
            val frame = JFrame("Title bar test")
            try {
                val titleBar = TitleBar(frame, dark = true, DebugLog(enabled = true, logged::add))
                frame.addNotify()
                assertEquals("dark", themeVariantOf(frame))
                titleBar.dark = false
                assertEquals("light", themeVariantOf(frame))
            } finally {
                frame.dispose()
            }
        }
        assertEquals(emptyList(), logged)
    }

    /** The property as the window manager reads it: UTF-8 text. */
    private fun themeVariantOf(frame: JFrame): String? {
        val atom = Class.forName("sun.awt.X11.XAtom")
        val get = atom.getMethod("get", String::class.java)
        val utf8 = atom.getDeclaredMethod("getAtom").apply { isAccessible = true }.invoke(get.invoke(null, "UTF8_STRING"))
        val bytes = atom.getMethod("getByteArrayProperty", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(get.invoke(null, X11FrameStyle.THEME_VARIANT), X11FrameStyle.xWindowOf(frame), utf8) as ByteArray?
        return bytes?.toString(Charsets.UTF_8)
    }
}
