package com.vivenotes.desktop.touch

import com.vivenotes.diagnostics.DebugLog
import java.awt.IllegalComponentStateException
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.foreign.MemorySegment
import javax.swing.SwingUtilities

/**
 * Touch screens for the desktop windows, the way Android's editor takes them: real multi-touch
 * pointers in Compose, from the platform's own touch protocol, since AWT has none.
 *
 * Native Wayland reads `wl_touch` on JetBrains Runtime's connection; X11 reads XInput 2.2 touch on a
 * connection of its own. `-Dvivenotes.touch=off` leaves the platform's default behaviour alone.
 */
internal object DesktopTouch {
    private val enabled: Boolean get() = System.getProperty("vivenotes.touch") != "off"
    private var x11: X11TouchSource? = null
    private var injector: ComposeTouchInjector? = null

    /** Native Wayland: one listener for every window of the process. Once the toolkit is running. */
    fun startWayland(log: DebugLog) {
        if (!enabled) return
        try {
            val toolkit = Class.forName("sun.awt.wl.WLToolkit")
            val peerFromSurface = toolkit.getDeclaredMethod("peerFromSurface", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val displayClass = Class.forName("sun.awt.wl.WLDisplay")
            val instance = displayClass.getMethod("getInstance").invoke(null)
            val address = displayClass.getMethod("getDisplayPtr").invoke(instance) as Long
            check(address != 0L) { "no Wayland display" }
            val injector = injector(log)
            fun windowOf(surface: Long): Window? {
                val peer = peerFromSurface.invoke(null, surface) ?: return null
                return peer.javaClass.getMethod("getTarget").invoke(peer) as? Window
            }
            WaylandTouchSource(MemorySegment.ofAddress(address), { frame ->
                SwingUtilities.invokeLater {
                    // Surface coordinates are the window's own: the runtime maps pointer events alike.
                    val inWindows = TouchFrame(frame.timeMillis, frame.changes.map {
                        TouchChange(it.id, it.phase, windowOf(it.surface), it.x, it.y)
                    }, frame.cancelled)
                    firstTouch("wayland", inWindows, log)
                    injector.accept(inWindows)
                }
            }, log).start()
        } catch (failure: Throwable) {
            log.event("touch") { "wayland touch unavailable: $failure" }
        }
    }

    /** X11, before any window exists: the runtime must not take touch for itself. */
    fun prepareX11(log: DebugLog) {
        if (enabled) X11TouchSource.disableRuntimeTouch(log)
    }

    /** X11: touch for [window], once it has an X window. On the Swing thread. */
    fun attachX11(window: Window, log: DebugLog) {
        if (!enabled || window.toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
        // Now rather than at the first touch: the queue must already be watching the mouse.
        val injector = injector(log)
        val source = x11 ?: X11TouchSource({ frame ->
            SwingUtilities.invokeLater {
                val inWindow = frame.fromRootPixels()
                firstTouch("x11", inWindow, log)
                injector.accept(inWindow)
            }
        }, log).also {
            it.start()
            x11 = it
        }
        fun watch() {
            val id = xWindowOf(window, log) ?: return
            source.watch(id, window)
        }
        if (window.isDisplayable) watch()
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = watch()
        })
    }

    private fun injector(log: DebugLog): ComposeTouchInjector = injector ?: ComposeTouchInjector(log).also { injector = it }

    private var touched = false

    /** Once per run: the first touch, which shows the platform delivers them at all. */
    private fun firstTouch(source: String, frame: TouchFrame, log: DebugLog) {
        if (touched || frame.changes.isEmpty()) return
        touched = true
        log.event("touch") { "first $source touch: ${frame.changes.first().phase}, window=${frame.changes.first().window != null}" }
    }

    /** X11 reports touches in root-window pixels; Compose's mapping starts from window coordinates. */
    private fun TouchFrame.fromRootPixels(): TouchFrame = copy(changes = changes.map { change ->
        val window = change.window ?: return@map change
        try {
            val transform = window.graphicsConfiguration.defaultTransform
            val origin = window.locationOnScreen
            change.copy(x = change.x / transform.scaleX - origin.x, y = change.y / transform.scaleY - origin.y)
        } catch (_: IllegalComponentStateException) {
            change.copy(window = null)
        }
    })

    /** The X window of an AWT window: its peer's, through the runtime's own accessor. */
    private fun xWindowOf(window: Window, log: DebugLog): Int? = try {
        val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
        val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            .getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window)
        val getWindow = generateSequence<Class<*>>(peer.javaClass) { it.superclass }
            .firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == "getWindow" && it.parameterCount == 0 } }
            .apply { isAccessible = true }
        (getWindow.invoke(peer) as Long).toInt()
    } catch (failure: Throwable) {
        log.event("touch") { "no X window for touch: $failure" }
        null
    }
}
