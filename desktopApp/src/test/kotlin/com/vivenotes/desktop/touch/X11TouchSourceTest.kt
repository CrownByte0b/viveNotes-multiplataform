package com.vivenotes.desktop.touch

import com.vivenotes.diagnostics.DebugLog
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.JFrame
import javax.swing.SwingUtilities
import java.util.concurrent.TimeUnit
import kotlin.test.*

class X11TouchSourceTest {
    private fun firstTouchLine(libraries: List<String>): String {
        val lines = LinkedBlockingQueue<String>()
        val source = X11TouchSource({}, DebugLog(enabled = true, output = lines::add), libraries)
        source.start()
        try {
            return assertNotNull(lines.poll(10, TimeUnit.SECONDS), "the source logged nothing")
        } finally {
            source.stop()
        }
    }

    // Regression: Ubuntu without libxcb-xinput0 logged "java.lang.ExceptionInInitializerError", which
    // hid the reason and failed the AppImage smoke test.
    @Test fun aMissingXcbLibraryIsNamedWithoutAnException() {
        // X11 touch is Linux's, and libc.so.6 the library every glibc system can open.
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val line = firstTouchLine(listOf("libc.so.6", "libvivenotes-absent.so.0"))
        assertEquals("ViveNotes DEBUG [touch] x11 touch unavailable: libvivenotes-absent.so.0 is not installed", line)
        assertFalse("Exception" in line || "Error" in line)
    }

    /**
     * The server checks the mask: pinches need XInput 2.4 asked for on the connection, and all three
     * of their events at once. One it refuses would leave the window with touch and no pinch.
     */
    @Test fun aWindowIsSelectedForPinchesWithItsTouch() {
        assumeTrue(!GraphicsEnvironment.isHeadless() && Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit")
        val lines = CopyOnWriteArrayList<String>()
        val log = DebugLog(enabled = true, output = lines::add)
        val source = X11TouchSource({}, log)
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = JFrame("X11 pinch selection").apply {
                    setSize(300, 200)
                    isVisible = true
                }
            }
            val window = checkNotNull(frame)
            source.start()
            waitFor(lines) { "x11 touch listening" in it }
            var id: Int? = null
            SwingUtilities.invokeAndWait { id = DesktopTouch.xWindowOf(window, log) }
            source.watch(checkNotNull(id), window)
            // A refusal is logged as the selection is checked, before this.
            waitFor(lines) { "x11 touch selected on" in it }
            assertFalse(lines.any { "refused" in it || "unavailable" in it }, lines.joinToString("\n"))
            println(lines.joinToString("\n"))
        } finally {
            source.stop()
            frame?.let { SwingUtilities.invokeAndWait { it.dispose() } }
        }
    }

    private fun waitFor(lines: List<String>, line: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (lines.none(line)) {
            check(System.currentTimeMillis() < deadline) { "timed out: $lines" }
            Thread.sleep(10)
        }
    }
}
