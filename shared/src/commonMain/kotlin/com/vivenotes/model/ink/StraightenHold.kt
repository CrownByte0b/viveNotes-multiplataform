package com.vivenotes.model.ink

import kotlin.math.hypot

internal class StraightenHold(private val slop: Float) {
    private val points = ArrayList<Float>()
    private var anchor: RulerPoint? = null
    private var overflowed = false
    var spent = false
        private set
    fun observe(x: Float, y: Float): Boolean {
        if (spent || overflowed) return false
        if (points.size >= 8192) { overflowed = true; return false }
        points.add(x); points.add(y)
        val a = anchor
        return if (a == null || hypot(x - a.x, y - a.y) > slop) {
            anchor = RulerPoint(x, y); true
        } else false
    }
    fun line(): StraightLine? = if (overflowed || spent) null else StraightLineFit.of(points.toFloatArray())
    fun spend() { spent = true; points.clear() }
}
