package com.vivenotes.desktop.touch

import com.vivenotes.byteink.compose.NativeInkInputSource
import com.vivenotes.diagnostics.DebugLog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/** Connects ByteInk's native Windows/tablet-v2 capture to the entire Compose UI. */
internal object DesktopPointerSource {
    private val attached = WeakHashMap<Window, Unit>()

    fun attach(window: Window, injector: ComposeTouchInjector, log: DebugLog) {
        check(SwingUtilities.isEventDispatchThread())
        if (!injector.hasComposeContent(window)) return
        if (attached.put(window, Unit) != null) return
        val wayland = window.toolkit.javaClass.name == "sun.awt.wl.WLToolkit"
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        // X11 uses the existing XCB connection, selecting only concrete pen devices. ByteInk's
        // primary-authoring X11 source also selects the mouse and cannot forward its other buttons.
        if (!windows && !wayland) return
        val queue = TouchAwareEventQueue.install()
        val frames = NativePointerFrames(window, mouse = !wayland, touch = windows, emit = injector::accept,
            offset = { if (windows) window.insets.let { it.left.toDouble() to it.top.toDouble() } else 0.0 to 0.0 })
        var subscription: AutoCloseable? = null
        var surfaceWait: javax.swing.Timer? = null
        fun stop() {
            surfaceWait?.stop()
            surfaceWait = null
            queue.nativeMouseWindows.remove(window)
            frames.cancel()
            val old = subscription
            subscription = null
            try { old?.close() } catch (failure: Exception) { log.failure("input", "close native pointers", failure) }
        }
        fun start() {
            if (subscription != null || !window.isShowing) return
            try {
                val nativeHandle = handle(window, wayland, windows)
                if (nativeHandle == null) {
                    if (surfaceWait == null) surfaceWait = javax.swing.Timer(50) {
                        if (window.isShowing) start() else stop()
                    }.also { it.start() }
                    return
                }
                val source = NativeInkInputSource(window, nativeHandle,
                    pixelsPerLocalUnit = { if (wayland) 1f else window.graphicsConfiguration.defaultTransform.scaleX.toFloat() },
                    onFailure = { failure -> stop(); log.failure("input", "native pointers", failure) })
                subscription = source.subscribe(frames::accept)
                surfaceWait?.stop()
                surfaceWait = null
                // X11/Windows also deliver real primary mouse packets. Feeding those here avoids
                // a promoted mouse press racing a native stylus press on AWT's separate queue.
                if (!wayland) queue.nativeMouseWindows[window] = { source.isReady }
                log.event("input") { "native pointers attached: ${source.backend}" }
            } catch (failure: Exception) {
                stop()
                log.failure("input", "attach native pointers", failure)
            }
        }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) = start()
            override fun componentHidden(event: ComponentEvent) = stop()
        })
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = start()
            override fun windowClosed(event: WindowEvent) = stop()
            override fun windowIconified(event: WindowEvent) = frames.cancel()
        })
        window.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) = frames.cancel()
        })
        start()
    }

    private fun handle(window: Window, wayland: Boolean, windows: Boolean): Long? {
        if (wayland) {
            val field = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap").apply { isAccessible = true }
            val peers = field.get(null) as Map<*, *>
            val peer = synchronized(peers) { peers.values.firstOrNull { candidate ->
                candidate != null && candidate.javaClass.getMethod("getTarget").apply { isAccessible = true }.invoke(candidate) === window
            } } ?: return null
            val surface = peer.javaClass.getMethod("getSurface").apply { isAccessible = true }.invoke(peer) ?: return null
            return (surface.javaClass.getMethod("getWlSurfacePtr").apply { isAccessible = true }.invoke(surface) as Long).takeIf { it != 0L }
        }
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window)
        val name = if (windows) "getHWnd" else "getWindow"
        val method = generateSequence<Class<*>>(peer.javaClass) { it.superclass }.firstNotNullOf { type ->
            type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
        }.apply { isAccessible = true }
        return method.invoke(peer) as Long
    }
}
