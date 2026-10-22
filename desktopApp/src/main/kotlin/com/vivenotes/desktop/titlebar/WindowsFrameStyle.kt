package com.vivenotes.desktop.titlebar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.vivenotes.ui.theme.headerBarColor
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Windows: DWM draws the title bar, light unless the window asks for the dark one (Windows 10 20H1
 * on; 1809 to 1909 knew the same attribute by another number). Windows 11 also takes a caption
 * colour, the header bar's, so title bar and header bar read as one; earlier versions refuse it and
 * keep their own dark or light grey.
 */
internal class WindowsFrameStyle(private val window: Window) : FrameStyle {
    override fun apply(dark: Boolean) {
        val handle = MemorySegment.ofAddress(Class.forName("sun.awt.windows.WComponentPeer")
            .getMethod("getHWnd").invoke(peerOf(window)) as Long)
        Arena.ofConfined().use { arena ->
            val darkMode = arena.allocateFrom(JAVA_INT, if (dark) 1 else 0)
            if (Dwm.setAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE, darkMode) != S_OK) {
                Dwm.setAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, darkMode)
            }
            Dwm.setAttribute(handle, DWMWA_CAPTION_COLOR, arena.allocateFrom(JAVA_INT, colorRef(headerBarColor(dark))))
        }
    }

    private object Dwm {
        private val setWindowAttribute: MethodHandle = Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("dwmapi", Arena.global()).find("DwmSetWindowAttribute").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))

        /** `DwmSetWindowAttribute`; its HRESULT. */
        fun setAttribute(window: MemorySegment, attribute: Int, value: MemorySegment): Int =
            setWindowAttribute.invoke(window, attribute, value, value.byteSize().toInt()) as Int
    }

    private companion object {
        const val S_OK = 0
        const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
        const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
        const val DWMWA_CAPTION_COLOR = 35
    }
}

/** [color] as a Win32 `COLORREF`, `0x00BBGGRR`. */
internal fun colorRef(color: Color): Int {
    val argb = color.toArgb()
    return ((argb shr 16) and 0xFF) or (argb and 0xFF00) or ((argb and 0xFF) shl 16)
}
