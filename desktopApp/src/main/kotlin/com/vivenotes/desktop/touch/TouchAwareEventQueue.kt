package com.vivenotes.desktop.touch

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.floor

/**
 * The app's AWT event queue once touch is in use: it keeps the mouse out of a finger's gesture, the
 * way Android ends mouse hover while a finger is down.
 *
 * Two things need it. X11 moves the mouse pointer along with the first finger, and those moves reach
 * Compose from the window as mouse events; mid-gesture, Compose answers a mouse event by releasing
 * the finger's pointer, which ends the gesture. And Compose keeps a resting cursor's last change on
 * the elements under it after it re-sends an unchanged position, so a finger's press there arrives
 * beside a mouse change and `clickable` does not take it as a press. So while a finger is down, hover
 * motion is not delivered, and [mouse] tells the injector where the cursor is, so a gesture can start
 * by sending the cursor away.
 *
 * A hovering tablet pen moves the mouse through it too ([penHover]).
 *
 * Confined to the event thread, like everything it touches.
 */
internal class TouchAwareEventQueue : EventQueue() {
    /** Where the mouse is in one of the app's windows, in that window's coordinates. */
    data class MouseAt(val window: Window, val x: Double, val y: Double)

    /** Whether a finger is down on any of the app's windows. */
    var touching = false

    /** Sources which deliver primary input themselves; leave other windows and buttons to AWT. */
    val nativeMouseWindows = mutableMapOf<Window, () -> Boolean>()

    /** The cursor while it is inside a window and has not been sent away; null otherwise. */
    var mouse: MouseAt? = null
        private set

    /** Where a hovering pen last moved the mouse. */
    private var pen: MouseAt? = null

    /** Told when the mouse itself moves, presses or scrolls in a window, rather than a pen for it. */
    val mouseUsed = mutableMapOf<Window, () -> Unit>()

    override fun dispatchEvent(event: AWTEvent) {
        if (event is MouseEvent && event !is PenHoverEvent && event.id in USED && mouseUsed.isNotEmpty()) {
            val window = event.component as? Window ?: SwingUtilities.getWindowAncestor(event.component)
            mouseUsed[window]?.invoke()
        }
        if (event is MouseEvent && (event.id == MouseEvent.MOUSE_DRAGGED &&
                event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK != 0 ||
                event.button == MouseEvent.BUTTON1 && event.id in PRIMARY)) {
            val window = event.component as? Window ?: SwingUtilities.getWindowAncestor(event.component)
            if (nativeMouseWindows[window]?.invoke() == true) return
        }
        if (event is MouseEvent && event.id in HOVER) {
            if (touching) return
            track(event)
        }
        super.dispatchEvent(event)
    }

    /** The scene has been told the cursor left; it is back once the mouse moves again. */
    fun forgetMouse() {
        mouse = null
    }

    /**
     * A tablet pen hovering at ([x], [y]) in [window], on native Wayland. Once the app binds the
     * tablet protocol there, the compositor no longer moves the mouse pointer for the pen, so the
     * pen's hover moves the mouse here instead — as the pen itself does on X11 and Windows. Compose's
     * hover states, tooltips and pointer icons follow it, and ByteInk shows the resulting cursor for
     * the pen. Like any mouse motion it is held back while a finger or the pen is down.
     */
    fun penHover(window: Window, x: Double, y: Double) {
        pen = MouseAt(window, x, y)
        postEvent(PenHoverEvent(window, MouseEvent.MOUSE_MOVED, x, y))
    }

    /** The pen left [window] or its proximity: the mouse it stood in for leaves with it. */
    fun penHoverEnd(window: Window) {
        val last = pen?.takeIf { it.window === window } ?: return
        pen = null
        postEvent(PenHoverEvent(window, MouseEvent.MOUSE_EXITED, last.x, last.y))
    }

    private fun track(event: MouseEvent) {
        val source = event.component ?: return
        val window = source as? Window ?: SwingUtilities.getWindowAncestor(source) ?: return
        val at = SwingUtilities.convertPoint(source, event.point, window)
        val inside = at.x >= 0 && at.y >= 0 && at.x < window.width && at.y < window.height
        mouse = if (event.id == MouseEvent.MOUSE_EXITED && (!inside || event is PenHoverEvent)) null
            else MouseAt(window, at.x.toDouble(), at.y.toDouble())
    }

    /** The mouse motion a hovering pen stands for, aimed at its window as the toolkit aims its own. */
    private class PenHoverEvent(window: Window, id: Int, x: Double, y: Double) :
        MouseEvent(window, id, System.currentTimeMillis(), 0, floor(x).toInt(), floor(y).toInt(), 0, false)

    companion object {
        private val HOVER = setOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED)
        private val PRIMARY = setOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)
        private val USED = setOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_WHEEL)
        private var installed: TouchAwareEventQueue? = null

        /** The process's queue, pushed onto AWT's the first time touch is set up. */
        @Synchronized
        fun install(): TouchAwareEventQueue = installed ?: TouchAwareEventQueue().also {
            Toolkit.getDefaultToolkit().systemEventQueue.push(it)
            installed = it
        }
    }
}
