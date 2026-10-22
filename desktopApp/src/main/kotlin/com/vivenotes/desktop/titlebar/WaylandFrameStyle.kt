package com.vivenotes.desktop.titlebar

import com.vivenotes.diagnostics.DebugLog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/**
 * JetBrains Runtime's native Wayland: where the compositor leaves frames to the client, as GNOME
 * does, the runtime draws the title bar itself, with GTK where it can. That decoration takes its
 * colours only from the system setting, through its listener on the toolkit's [DARK_PROPERTY]; this
 * hands the same listener the app's look instead. Frames the compositor draws (KDE's, Hyprland's
 * none) are left as they are. The launch opens `sun.awt.wl` for it.
 */
internal class WaylandFrameStyle(private val window: Window, private val log: DebugLog) : FrameStyle {
    private var dark: Boolean? = null
    /** The decoration last given [dark]: the runtime replaces it on leaving full screen. */
    private var styled: Any? = null

    init {
        // A change of the system setting reaches the decoration's listener too; give it the app's look again after.
        window.toolkit.addPropertyChangeListener(DARK_PROPERTY) {
            SwingUtilities.invokeLater {
                styled = null
                restyle()
            }
        }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = restyle()
        })
    }

    override fun apply(dark: Boolean) {
        this.dark = dark
        styled = null
        style(dark)
    }

    private fun restyle() {
        val dark = dark ?: return
        try {
            if (window.isDisplayable) style(dark)
        } catch (failure: Throwable) {
            log.event("titlebar") { "the Wayland title bar keeps the system's look: $failure" }
        }
    }

    private fun style(dark: Boolean) {
        val peer = peerOf(window)
        if (!decoratedPeer.isInstance(peer)) return
        val decoration = getDecoration.invoke(peer)
        if (decoration === styled) return
        // The runtime's own frames; server-side and full-screen ones have no colours to give.
        if (fullDecoration.isInstance(decoration)) {
            val listener = themeListener.get(decoration) as PropertyChangeListener
            listener.propertyChange(PropertyChangeEvent(window.toolkit, DARK_PROPERTY, !dark, dark))
        }
        styled = decoration
    }

    companion object {
        /** The toolkit's desktop property the runtime keeps the system's dark setting in. */
        const val DARK_PROPERTY = "awt.os.theme.isDark"

        private val decoratedPeer by lazy { Class.forName("sun.awt.wl.WLDecoratedPeer") }
        private val getDecoration by lazy { decoratedPeer.getDeclaredMethod("getDecoration").apply { isAccessible = true } }
        private val fullDecoration by lazy { Class.forName("sun.awt.wl.FullFrameDecorationHelper") }
        private val themeListener by lazy { fullDecoration.getDeclaredField("pcl").apply { isAccessible = true } }
    }
}
