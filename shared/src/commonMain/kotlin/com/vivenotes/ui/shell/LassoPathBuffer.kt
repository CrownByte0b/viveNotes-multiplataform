package com.vivenotes.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/** Owned by the pointer/draw thread; only the draw phase observes [revision]. */
internal class LassoPathBuffer {
    private val points = ArrayList<Offset>()
    val path = Path()
    var revision by mutableIntStateOf(0)
        private set
    val size: Int get() = points.size
    private var farthest = 0f

    fun start(point: Offset) {
        points.clear()
        path.reset()
        points.add(point)
        path.moveTo(point.x, point.y)
        farthest = 0f
        revision++
    }

    fun append(point: Offset) {
        if (points.isEmpty()) return start(point)
        farthest = maxOf(farthest, (point - points[0]).getDistance())
        points.add(point)
        path.lineTo(point.x, point.y)
        revision++
    }

    fun distanceFromStart(): Float = farthest
    fun snapshot(): List<Offset> = points.toList()
    fun isEmpty(): Boolean = points.isEmpty()

    fun clear() {
        if (points.isEmpty()) return
        points.clear()
        path.reset()
        farthest = 0f
        revision++
    }
}
