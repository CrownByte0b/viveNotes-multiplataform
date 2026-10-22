package com.vivenotes.desktop.titlebar

import com.vivenotes.diagnostics.DebugLog
import com.vivenotes.ui.theme.headerBarColor
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * DWM's title bar takes the app's look, read back from DWM itself. Needs Windows with a desktop — the
 * build's Windows run — and the launch's `sun.awt` and `sun.awt.windows` exports, which the test JVM
 * shares.
 */
class WindowsFrameStyleTest {
    @Test
    fun dwmDrawsTheTitleBarDarkOrLightInTheHeaderBarsColour() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        assumeTrue(frameKindOf(Toolkit.getDefaultToolkit().javaClass.name) == FrameKind.WINDOWS)
        val logged = mutableListOf<String>()
        onSwing {
            val frame = JFrame("Title bar test")
            try {
                val titleBar = TitleBar(frame, dark = true, DebugLog(enabled = true, logged::add))
                frame.addNotify()
                assertEquals(1, attribute(frame, DWMWA_USE_IMMERSIVE_DARK_MODE))
                captionColour(frame)?.let { assertEquals(colorRef(headerBarColor(darkTheme = true)), it) }
                titleBar.dark = false
                assertEquals(0, attribute(frame, DWMWA_USE_IMMERSIVE_DARK_MODE))
                captionColour(frame)?.let { assertEquals(colorRef(headerBarColor(darkTheme = false)), it) }
            } finally {
                frame.dispose()
            }
        }
        assertEquals(emptyList(), logged)
    }

    /** Windows 11's caption colour; null where DWM has none (Windows 10). */
    private fun captionColour(frame: JFrame): Int? =
        runCatching { attribute(frame, DWMWA_CAPTION_COLOR) }.getOrNull()

    private fun attribute(frame: JFrame, attribute: Int): Int = Arena.ofConfined().use { arena ->
        val handle = Class.forName("sun.awt.windows.WComponentPeer").getMethod("getHWnd").invoke(peerOf(frame)) as Long
        val value = arena.allocate(JAVA_INT)
        val result = getWindowAttribute.invoke(MemorySegment.ofAddress(handle), attribute, value, 4) as Int
        check(result == 0) { "DwmGetWindowAttribute($attribute) failed: 0x${result.toUInt().toString(16)}" }
        value.get(JAVA_INT, 0)
    }

    private companion object {
        const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
        const val DWMWA_CAPTION_COLOR = 35

        val getWindowAttribute by lazy {
            Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("dwmapi", Arena.global()).find("DwmGetWindowAttribute").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))
        }
    }
}
