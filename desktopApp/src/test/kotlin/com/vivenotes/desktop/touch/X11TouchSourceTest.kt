package com.vivenotes.desktop.touch

import com.vivenotes.diagnostics.DebugLog
import org.junit.Assume.assumeTrue
import java.util.concurrent.LinkedBlockingQueue
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
}
