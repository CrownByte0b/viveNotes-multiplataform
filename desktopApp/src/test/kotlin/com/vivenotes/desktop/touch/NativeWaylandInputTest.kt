package com.vivenotes.desktop.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.desktop.rendering.WaylandRendering
import com.vivenotes.desktop.rendering.waylandComposePanel
import java.awt.Toolkit
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
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
        WaylandRendering.prepare()
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
                val panel = waylandComposePanel(log)
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

    /**
     * The touch source's own pointer and pinch gesture, on the runtime's connection: a request the
     * compositor rejects is a protocol error, which ends the connection and the app with it. Where the
     * compositor has no pointer gestures (Weston) touch must come up as before.
     */
    @Test fun touchpadPinchIsAskedForWithoutAProtocolError() {
        assumeTrue(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit")
        val lines = CopyOnWriteArrayList<String>()
        val displayClass = Class.forName("sun.awt.wl.WLDisplay")
        val instance = displayClass.getMethod("getInstance").invoke(null)
        val display = MemorySegment.ofAddress(displayClass.getMethod("getDisplayPtr").invoke(instance) as Long)
        val source = WaylandTouchSource(display, {}, DebugLog(true) { lines += it })
        source.start()
        try {
            val deadline = System.currentTimeMillis() + 5000
            while (lines.none { "wayland touch listening" in it }) {
                check(System.currentTimeMillis() < deadline) { "the source never listened: $lines" }
                Thread.sleep(10)
            }
            // The compositor's answer to the pinch requests, if it has one, arrives after the listening line.
            Thread.sleep(500)
            val getError = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("libwayland-client.so.0", Arena.global()).find("wl_display_get_error").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS))
            assertEquals(0, getError.invoke(display) as Int, lines.joinToString("\n"))
            assertFalse(lines.any { "unavailable" in it || "failed" in it }, lines.joinToString("\n"))
            println(lines.joinToString("\n"))
        } finally {
            source.stop()
        }
    }
}
