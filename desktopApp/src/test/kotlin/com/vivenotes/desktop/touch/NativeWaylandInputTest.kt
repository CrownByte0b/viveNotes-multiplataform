package com.vivenotes.desktop.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import com.vivenotes.diagnostics.DebugLog
import java.awt.Toolkit
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Runs on JBR in a private Wayland session; the ordinary X11/Windows suite skips it. */
class NativeWaylandInputTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun nativeCaptureWaitsForTheConfiguredSurfaceAndReattachesAfterHideShow() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(true) { lines += it }
        var frame: JFrame? = null
        fun waitForAttachments(count: Int) {
            val deadline = System.currentTimeMillis() + 5000
            while (lines.count { "native pointers attached: LINUX_WAYLAND_TABLET" in it } < count) {
                check(System.currentTimeMillis() < deadline) { "Native attachment timed out: $lines" }
                Thread.sleep(10)
            }
        }
        try {
            SwingUtilities.invokeAndWait {
                val window = JFrame("Wayland native input")
                frame = window
                val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
                window.add(panel)
                panel.setContent { Box(Modifier.fillMaxSize()) }
                window.setSize(400, 300)
                window.isVisible = true
                DesktopPointerSource.attach(window, ComposeTouchInjector(log), log)
            }
            val window = checkNotNull(frame)
            waitForAttachments(1)
            SwingUtilities.invokeAndWait { window.isVisible = false }
            SwingUtilities.invokeAndWait { window.isVisible = true }
            waitForAttachments(2)
            Thread.sleep(300)
            assertFalse(lines.any { "failed" in it }, lines.joinToString("\n"))
        } finally {
            frame?.let { window -> SwingUtilities.invokeAndWait { window.dispose() } }
        }
    }
}
