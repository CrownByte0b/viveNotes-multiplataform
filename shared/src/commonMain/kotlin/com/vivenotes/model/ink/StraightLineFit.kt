package com.vivenotes.model.ink

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

data class StraightLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

object StraightLineFit {

    const val MIN_TRAVEL_DP = 24f

    const val SIDEWAYS_FRACTION = 0.035f
    const val MIN_SIDEWAYS_DP = 3f

    const val BACKTRACK_FRACTION = 0.06f
    const val MIN_BACKTRACK_DP = 4f

    const val AXIS_SNAP_DEGREES = 5f

    fun of(points: FloatArray): StraightLine? {
        if (points.size < 4 || points.size % 2 != 0 || points.any { !it.isFinite() }) return null

        val startX = points[0]
        val startY = points[1]
        val endX = points[points.size - 2]
        val endY = points[points.size - 1]

        val spanX = endX - startX
        val spanY = endY - startY
        val chord = hypot(spanX, spanY)
        if (chord < MIN_TRAVEL_DP) return null

        val alongX = spanX / chord
        val alongY = spanY / chord

        val sidewaysLimit = max(MIN_SIDEWAYS_DP, chord * SIDEWAYS_FRACTION)
        val backtrackLimit = max(MIN_BACKTRACK_DP, chord * BACKTRACK_FRACTION)

        var furthestAlong = 0f
        for (index in 0 until points.size step 2) {
            val dx = points[index] - startX
            val dy = points[index + 1] - startY

            val across = abs(dx * -alongY + dy * alongX)
            if (across > sidewaysLimit) return null

            val along = dx * alongX + dy * alongY
            if (along < -backtrackLimit) return null
            if (along > chord + backtrackLimit) return null
            if (along < furthestAlong - backtrackLimit) return null
            if (along > furthestAlong) furthestAlong = along
        }

        return snappedToAxis(startX, startY, endX, endY, chord)
    }

    private fun snappedToAxis(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        chord: Float,
    ): StraightLine {
        val degrees = (atan2((endY - startY).toDouble(), (endX - startX).toDouble()) * 180.0 / PI)
        val fromAxis = (((degrees % 90.0) + 90.0) % 90.0).let { minOf(it, 90.0 - it) }
        if (fromAxis > AXIS_SNAP_DEGREES) {
            return StraightLine(startX, startY, endX, endY)
        }
        val quadrant = (degrees / 90.0).roundToInt()
        return when (((quadrant % 4) + 4) % 4) {
            0 -> StraightLine(startX, startY, startX + chord, startY)
            1 -> StraightLine(startX, startY, startX, startY + chord)
            2 -> StraightLine(startX, startY, startX - chord, startY)
            else -> StraightLine(startX, startY, startX, startY - chord)
        }
    }
}