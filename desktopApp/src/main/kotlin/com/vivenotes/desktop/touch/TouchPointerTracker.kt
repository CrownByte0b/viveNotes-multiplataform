package com.vivenotes.desktop.touch

import java.awt.Window

/** A contact as Compose sees it: a pointer of its own, at a point in its window. */
internal data class TouchPointer(val pointerId: Long, val window: Window, val x: Double, val y: Double, val pressed: Boolean)

internal enum class TouchEventType { Press, Move, Release }

/** What one scene is sent: [type], and every touch pointer it knows of at that moment. */
internal sealed interface TouchDispatch<T> {
    val target: T

    data class Event<T>(
        override val target: T,
        val type: TouchEventType,
        val pointers: List<TouchPointer>,
        val timeMillis: Long,
    ) : TouchDispatch<T>

    /** The system took the touches back: whatever the scene was doing with them, it stops. */
    data class Cancel<T>(override val target: T) : TouchDispatch<T>
}

/**
 * Turns touch frames into the sequence of pointer events a Compose scene expects from a touch
 * screen, Android's sequence: each event lists every pointer down on that scene, a press or release
 * changes the pressed state of the pointers it concerns, and a released pointer is not listed again.
 *
 * A contact goes to the [T] found under it when it lands ([resolve]) and stays there; a second
 * finger joins the scene the first is on, so a pinch is one gesture even when it spans two things.
 * A contact that lands on nothing is ignored for its whole life.
 */
internal class TouchPointerTracker<T : Any>(
    private val resolve: (Window, Double, Double) -> T?,
    private var nextPointerId: Long = FIRST_POINTER_ID,
) {
    private class Contact<T>(val pointerId: Long, val target: T, var window: Window, var x: Double, var y: Double)

    private val contacts = LinkedHashMap<Long, Contact<T>>()
    private val ignored = mutableSetOf<Long>()

    val active: Boolean get() = contacts.isNotEmpty()

    fun accept(frame: TouchFrame): List<TouchDispatch<T>> {
        if (frame.cancelled) {
            val targets = contacts.values.map { it.target }.distinct()
            contacts.clear()
            ignored.clear()
            return targets.map { TouchDispatch.Cancel(it) }
        }
        val out = mutableListOf<TouchDispatch<T>>()
        val moved = mutableSetOf<T>()
        val pressed = mutableSetOf<T>()
        val released = mutableMapOf<T, MutableList<Contact<T>>>()

        for (change in frame.changes) {
            when (change.phase) {
                TouchPhase.Down -> {
                    if (change.id in contacts || change.id in ignored) continue
                    val window = change.window ?: continue
                    val target = contacts.values.firstOrNull { it.window === window }?.target
                        ?: resolve(window, change.x, change.y)
                    if (target == null) {
                        ignored += change.id
                        continue
                    }
                    contacts[change.id] = Contact(nextPointerId++, target, window, change.x, change.y)
                    pressed += target
                }
                TouchPhase.Move -> {
                    val contact = contacts[change.id] ?: continue
                    contact.window = change.window ?: contact.window
                    contact.x = change.x
                    contact.y = change.y
                    moved += contact.target
                }
                TouchPhase.Up -> {
                    if (ignored.remove(change.id)) continue
                    val contact = contacts[change.id] ?: continue
                    released.getOrPut(contact.target) { mutableListOf() } += contact
                }
            }
        }

        // Moves first, among the pointers already down; then the new ones; then the lifted ones.
        val newIds = frame.changes.filter { it.phase == TouchPhase.Down }.mapNotNull { contacts[it.id]?.pointerId }.toSet()
        for (target in moved) {
            out += event(target, TouchEventType.Move, frame.timeMillis) { it.pointerId !in newIds }
        }
        for (target in pressed) out += event(target, TouchEventType.Press, frame.timeMillis)
        for ((target, lifted) in released) {
            val liftedIds = lifted.map { it.pointerId }.toSet()
            out += TouchDispatch.Event(target, TouchEventType.Release,
                pointersOf(target).map { if (it.pointerId in liftedIds) it.copy(pressed = false) else it },
                frame.timeMillis)
            contacts.values.removeAll { it.pointerId in liftedIds }
        }
        return out
    }

    private fun event(target: T, type: TouchEventType, time: Long, include: (TouchPointer) -> Boolean = { true }) =
        TouchDispatch.Event(target, type, pointersOf(target).filter(include), time)

    private fun pointersOf(target: T): List<TouchPointer> = contacts.values.filter { it.target == target }
        .map { TouchPointer(it.pointerId, it.window, it.x, it.y, pressed = true) }

    companion object {
        /** Clear of the mouse's pointer, which Compose numbers from zero. */
        const val FIRST_POINTER_ID = 1L shl 20
    }
}
