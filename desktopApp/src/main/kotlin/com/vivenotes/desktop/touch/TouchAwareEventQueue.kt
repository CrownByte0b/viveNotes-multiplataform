package com.vivenotes.desktop.touch

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

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
 * Confined to the event thread, like everything it touches.
 */
internal class TouchAwareEventQueue : EventQueue() {
    /** Where the mouse is in one of the app's windows, in that window's coordinates. */
    data class MouseAt(val window: Window, val x: Double, val y: Double)

    /** Whether a finger is down on any of the app's windows. */
    var touching = false

    /** The cursor while it is inside a window and has not been sent away; null otherwise. */
    var mouse: MouseAt? = null
        private set

    override fun dispatchEvent(event: AWTEvent) {
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

    private fun track(event: MouseEvent) {
        val source = event.component ?: return
        val window = source as? Window ?: SwingUtilities.getWindowAncestor(source) ?: return
        val at = SwingUtilities.convertPoint(source, event.point, window)
        val inside = at.x >= 0 && at.y >= 0 && at.x < window.width && at.y < window.height
        mouse = if (event.id == MouseEvent.MOUSE_EXITED && !inside) null else MouseAt(window, at.x.toDouble(), at.y.toDouble())
    }

    companion object {
        private val HOVER = setOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED)
        private var installed: TouchAwareEventQueue? = null

        /** The process's queue, pushed onto AWT's the first time touch is set up. */
        @Synchronized
        fun install(): TouchAwareEventQueue = installed ?: TouchAwareEventQueue().also {
            Toolkit.getDefaultToolkit().systemEventQueue.push(it)
            installed = it
        }
    }
}
