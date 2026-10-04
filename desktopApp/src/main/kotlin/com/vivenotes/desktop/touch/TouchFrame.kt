package com.vivenotes.desktop.touch

import java.awt.Window

/** What one contact did. */
internal enum class TouchPhase { Down, Move, Up }

/**
 * One contact's change, at a point in [window]'s own AWT coordinates — the coordinates a mouse
 * event on that window carries. [id] is unique among the contacts on the screen at one time; an
 * up's position is not used, because Wayland does not report one.
 */
internal data class TouchChange(
    val id: Long,
    val phase: TouchPhase,
    val window: Window?,
    val x: Double,
    val y: Double,
)

/**
 * The changes a touch screen reported together, in order. A frame never holds two changes of one
 * contact. [cancelled] means the system took every touch away (a compositor gesture began): none of
 * them ends in a release.
 */
internal data class TouchFrame(
    val timeMillis: Long,
    val changes: List<TouchChange>,
    val cancelled: Boolean = false,
)

/**
 * Groups changes into frames for a source that has no frame event of its own (X11 reports each
 * contact separately): changes that arrive together stay together, except that a contact's second
 * change starts a new frame.
 */
internal class TouchFrameBuilder(private val emit: (TouchFrame) -> Unit) {
    private val pending = mutableListOf<TouchChange>()
    private var time = 0L

    fun add(change: TouchChange, timeMillis: Long) {
        if (pending.any { it.id == change.id }) flush()
        pending += change
        time = timeMillis
    }

    fun flush() {
        if (pending.isEmpty()) return
        emit(TouchFrame(time, pending.toList()))
        pending.clear()
    }
}
