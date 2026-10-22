package com.vivenotes.desktop.titlebar

import com.vivenotes.diagnostics.DebugLog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * JetBrains Runtime's own Wayland title bar takes the app's look, not only the system's. Needs JBR's
 * WLToolkit on a compositor that leaves frames to clients (GNOME, weston), and the launch's
 * `sun.awt.wl` and `sun.awt` access; elsewhere it is skipped.
 */
class WaylandFrameStyleTest {
    @Test
    fun theRuntimesDecorationIsDrawnInTheAppsLook() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        assumeTrue(frameKindOf(Toolkit.getDefaultToolkit().javaClass.name) == FrameKind.WAYLAND)
        val logged = mutableListOf<String>()
        onSwing {
            val frame = JFrame("Title bar test")
            try {
                frame.addNotify()
                val system = darkDecoration(frame)
                assumeTrue("the compositor draws the frame", system != null)
                val titleBar = TitleBar(frame, dark = system != true, DebugLog(enabled = true, logged::add))
                assertEquals(system != true, darkDecoration(frame))
                titleBar.dark = system == true
                assertEquals(system == true, darkDecoration(frame))
            } finally {
                frame.dispose()
            }
        }
        assertEquals(emptyList(), logged)
    }

    /** Whether the runtime's decoration is dark; null when it draws none with colours of its own. */
    private fun darkDecoration(frame: JFrame): Boolean? {
        val peer = peerOf(frame)
        val decoration = Class.forName("sun.awt.wl.WLDecoratedPeer").getDeclaredMethod("getDecoration")
            .apply { isAccessible = true }.invoke(peer)
        val full = Class.forName("sun.awt.wl.FullFrameDecorationHelper")
        return if (full.isInstance(decoration)) full.getMethod("isDarkTheme").invoke(decoration) as Boolean else null
    }
}
