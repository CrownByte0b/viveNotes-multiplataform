package com.vivenotes.desktop.titlebar

import com.vivenotes.diagnostics.DebugLog
import java.awt.Component
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The sizes the desktop resources hold the app icon at: title bars, task bars and window switchers. */
internal val APP_ICON_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

/** The app icon at each of [APP_ICON_SIZES], each one resampled from the artwork rather than by AWT. */
internal val appIcons: List<BufferedImage> by lazy {
    APP_ICON_SIZES.map { size ->
        checkNotNull(TitleBar::class.java.getResourceAsStream("/icons/vivenotes-$size.png")) { "no $size px app icon" }
            .use(ImageIO::read)
    }
}

/** Who draws a window's frame, by the AWT toolkit the window has. */
internal enum class FrameKind { WINDOWS, X11, WAYLAND }

internal fun frameKindOf(toolkitClassName: String): FrameKind? = when (toolkitClassName) {
    "sun.awt.windows.WToolkit" -> FrameKind.WINDOWS
    "sun.awt.X11.XToolkit" -> FrameKind.X11
    "sun.awt.wl.WLToolkit" -> FrameKind.WAYLAND
    else -> null
}

/** One platform's way to give a window's frame the dark or light look. On the Swing thread, once the window has a peer. */
internal fun interface FrameStyle {
    fun apply(dark: Boolean)
}

/**
 * The frame the platform draws around [window] — title bar, border and icon — in the app's look rather
 * than the platform default: Windows draws a light title bar unless asked otherwise, and JetBrains
 * Runtime's Wayland title bar follows only the system setting, never the app's own choice. The icon and
 * look are set as soon as the window has its peer, before it is first shown, and the look again
 * whenever [dark] changes.
 */
internal class TitleBar(
    private val window: Window,
    dark: Boolean,
    private val log: DebugLog,
    private val style: FrameStyle? = frameStyleFor(window, log),
) {
    /** Whether the frame has the dark look. Set on the Swing thread. */
    var dark: Boolean = dark
        set(value) {
            if (field == value) return
            field = value
            if (window.isDisplayable) applyStyle()
        }

    init {
        window.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && window.isDisplayable) {
                prepare()
            }
        }
        if (window.isDisplayable) prepare()
    }

    // Not before the peer exists: Compose's window clears the icon when it first applies its own, none.
    private fun prepare() {
        window.iconImages = appIcons
        applyStyle()
    }

    private fun applyStyle() {
        try {
            style?.apply(dark)
        } catch (failure: Throwable) {
            log.event("titlebar") { "the frame keeps the platform's look: $failure" }
        }
    }
}

private fun frameStyleFor(window: Window, log: DebugLog): FrameStyle? = when (frameKindOf(window.toolkit.javaClass.name)) {
    FrameKind.WINDOWS -> WindowsFrameStyle(window)
    FrameKind.X11 -> X11FrameStyle(window)
    FrameKind.WAYLAND -> WaylandFrameStyle(window, log)
    null -> null
}

/** The AWT peer of [window], through the runtime's own accessor; the launch exports `sun.awt` for it. */
internal fun peerOf(window: Window): Any? {
    val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    return Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
        .getMethod("getPeer", Component::class.java).invoke(accessor, window)
}
