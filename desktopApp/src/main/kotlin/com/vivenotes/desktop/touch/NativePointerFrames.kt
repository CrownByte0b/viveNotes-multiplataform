package com.vivenotes.desktop.touch

import androidx.compose.ui.input.pointer.PointerType
import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.compose.InkHoverEvent
import com.vivenotes.byteink.compose.InkInputEvent
import com.vivenotes.byteink.compose.InkPointerSample
import java.awt.Window

/**
 * Retains each device's identity and every real historical pressure sample. EDT only.
 *
 * A tool hovering over the window goes to [onHover] at its point in the window, until the last
 * hovering tool leaves ([onHoverEnd]). Only native Wayland reports hover: elsewhere the pen moves
 * the system pointer itself.
 */
internal class NativePointerFrames(
    private val window: Window,
    private val mouse: Boolean,
    private val touch: Boolean,
    private val emit: (TouchFrame) -> Unit,
    private val offset: () -> Pair<Double, Double> = { 0.0 to 0.0 },
    private val onHover: (Window, Double, Double) -> Unit = { _, _, _ -> },
    private val onHoverEnd: (Window) -> Unit = {},
) {
    private val active = mutableMapOf<Long, InkPointerSample>()
    private val hovering = mutableSetOf<Long>()

    fun hover(event: InkHoverEvent) {
        when (event) {
            is InkHoverEvent.Move -> {
                hovering += event.pointerId
                val (dx, dy) = offset()
                onHover(window, event.sample.x + dx, event.sample.y + dy)
            }
            is InkHoverEvent.Exit -> if (hovering.remove(event.pointerId) && hovering.isEmpty()) onHoverEnd(window)
        }
    }

    /** Capture stopped, so nothing hovers any longer. */
    fun endHover() {
        if (hovering.isEmpty()) return
        hovering.clear()
        onHoverEnd(window)
    }

    fun accept(event: InkInputEvent) {
        when (event) {
            is InkInputEvent.Begin -> {
                val sample = event.sample
                if (sample.toolType == InputToolType.MOUSE && !mouse || sample.toolType == InputToolType.TOUCH && !touch) return
                active[event.pointerId] = sample
                send(event.pointerId, TouchPhase.Down, sample)
            }
            is InkInputEvent.Move -> move(event.pointerId, event.sample)
            is InkInputEvent.Batch -> event.samples.forEach { move(event.pointerId, it) }
            is InkInputEvent.Finish -> {
                val previous = active.remove(event.pointerId) ?: return
                val sample = event.sample ?: previous
                // Touch sources historically retain the last move on up (Wayland has no up position).
                // Send the real final position first so Windows touch does not lose that observation.
                send(event.pointerId, TouchPhase.Move, sample)
                send(event.pointerId, TouchPhase.Up, sample)
            }
            is InkInputEvent.CancelPointer -> if (event.pointerId in active) cancel()
            InkInputEvent.Cancel -> cancel()
            is InkInputEvent.Predict -> Unit
        }
    }

    fun cancel() {
        if (active.isEmpty()) return
        val time = active.values.maxOf { it.uptimeMillis }
        active.clear()
        emit(TouchFrame(time, emptyList(), cancelled = true, cancelledWindow = window))
    }

    private fun move(id: Long, sample: InkPointerSample) {
        if (id !in active) return
        active[id] = sample
        send(id, TouchPhase.Move, sample)
    }

    private fun send(id: Long, phase: TouchPhase, sample: InkPointerSample) {
        val (dx, dy) = offset()
        val type = when (sample.toolType) {
            InputToolType.STYLUS -> PointerType.Stylus
            InputToolType.TOUCH -> PointerType.Touch
            else -> PointerType.Mouse
        }
        emit(TouchFrame(sample.uptimeMillis, listOf(TouchChange(
            id xor Long.MIN_VALUE, phase, window, sample.x + dx, sample.y + dy, type, sample.pressure ?: Float.NaN))))
    }
}
