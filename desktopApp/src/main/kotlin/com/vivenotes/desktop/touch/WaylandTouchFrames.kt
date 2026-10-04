package com.vivenotes.desktop.touch

/** A contact's change on a Wayland surface, in that surface's coordinates. */
internal data class SurfaceTouchChange(
    val id: Long,
    val phase: TouchPhase,
    val surface: Long,
    val x: Double,
    val y: Double,
)

internal data class SurfaceTouchFrame(
    val timeMillis: Long,
    val changes: List<SurfaceTouchChange>,
    val cancelled: Boolean = false,
)

/**
 * `wl_touch` events gathered into frames. Wayland sends a contact's surface only when it lands, and
 * its position only while it moves; `frame` closes what belongs together, and `cancel` means the
 * compositor took every touch over (a system gesture) and none will be released.
 *
 * Contacts are keyed by the caller, which makes them unique across seats.
 */
internal class WaylandTouchFrames(private val emit: (SurfaceTouchFrame) -> Unit) {
    private class Contact(val surface: Long, var x: Double, var y: Double)

    private val contacts = HashMap<Long, Contact>()
    private val pending = mutableListOf<SurfaceTouchChange>()
    private var time = 0L

    fun down(contact: Long, timeMillis: Long, surface: Long, x: Double, y: Double) {
        contacts[contact] = Contact(surface, x, y)
        add(SurfaceTouchChange(contact, TouchPhase.Down, surface, x, y), timeMillis)
    }

    fun motion(contact: Long, timeMillis: Long, x: Double, y: Double) {
        val known = contacts[contact] ?: return
        known.x = x
        known.y = y
        add(SurfaceTouchChange(contact, TouchPhase.Move, known.surface, x, y), timeMillis)
    }

    fun up(contact: Long, timeMillis: Long) {
        val known = contacts.remove(contact) ?: return
        add(SurfaceTouchChange(contact, TouchPhase.Up, known.surface, known.x, known.y), timeMillis)
    }

    fun frame() {
        if (pending.isEmpty()) return
        emit(SurfaceTouchFrame(time, pending.toList()))
        pending.clear()
    }

    fun cancel() {
        pending.clear()
        if (contacts.isEmpty()) return
        contacts.clear()
        emit(SurfaceTouchFrame(time, emptyList(), cancelled = true))
    }

    private fun add(change: SurfaceTouchChange, timeMillis: Long) {
        // A frame holds one change per contact; a compositor that sends two closes it early.
        if (pending.any { it.id == change.id }) frame()
        pending += change
        time = timeMillis
    }
}
