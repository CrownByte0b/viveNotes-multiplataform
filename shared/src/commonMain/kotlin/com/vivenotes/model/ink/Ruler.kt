package com.vivenotes.model.ink

import com.vivenotes.workspace.RulerKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Ruler(
    val centerX: Float,
    val centerY: Float,
    val angleRadians: Float,
    val kind: RulerKind,
    val sizeDp: Float,
) {

    val reach: Float get() = sizeDp / 2f

    fun toLocal(point: RulerPoint): RulerPoint {
        val dx = point.x - centerX
        val dy = point.y - centerY
        val cos = cos(-angleRadians)
        val sin = sin(-angleRadians)
        return RulerPoint(dx * cos - dy * sin, dx * sin + dy * cos)
    }

    fun toPage(local: RulerPoint): RulerPoint {
        val cos = cos(angleRadians)
        val sin = sin(angleRadians)
        return RulerPoint(
            centerX + local.x * cos - local.y * sin,
            centerY + local.x * sin + local.y * cos,
        )
    }

    fun grabs(point: RulerPoint): Boolean {
        val local = toLocal(point)
        return when (kind) {
            RulerKind.Straight -> abs(local.x) <= reach && abs(local.y) <= BAND_DP / 2f
            RulerKind.Protractor -> local.y <= 0f && hypot(local.x, local.y) <= reach
        }
    }

    fun engages(point: RulerPoint, tolerance: Float): Boolean {
        val local = toLocal(point)
        return when (kind) {
            RulerKind.Straight ->
                abs(local.x) <= reach + tolerance && abs(local.y) <= BAND_DP / 2f + tolerance
            RulerKind.Protractor ->
                local.y <= tolerance && hypot(local.x, local.y) <= reach + tolerance
        }
    }

    fun sideOf(point: RulerPoint): RulerSide {
        val local = toLocal(point)
        val across = when (kind) {
            RulerKind.Straight -> local.y
            RulerKind.Protractor -> local.x
        }
        return if (across < 0f) RulerSide.Negative else RulerSide.Positive
    }

    fun snap(point: RulerPoint, side: RulerSide): RulerPoint {
        val local = toLocal(point)
        val sign = if (side == RulerSide.Negative) -1f else 1f
        return when (kind) {
            RulerKind.Straight -> toPage(
                RulerPoint(
                    x = local.x.coerceIn(-reach, reach),
                    y = sign * BAND_DP / 2f,
                ),
            )
            RulerKind.Protractor -> {
                val distance = hypot(local.x, local.y)
                if (local.y > 0f || distance == 0f) {
                    toPage(RulerPoint(sign * reach, 0f))
                } else {
                    toPage(RulerPoint(local.x / distance * reach, local.y / distance * reach))
                }
            }
        }
    }

    fun dialCenter(): RulerPoint = when (kind) {
        RulerKind.Straight -> RulerPoint(0f, 0f)
        RulerKind.Protractor -> RulerPoint(0f, -reach * PROTRACTOR_DIAL_INSET)
    }

    fun grabsDial(point: RulerPoint): Boolean {
        val local = toLocal(point)
        val dial = dialCenter()
        return hypot(local.x - dial.x, local.y - dial.y) <= DIAL_RADIUS_DP * DIAL_TOUCH_SLACK
    }

    fun movedBy(dx: Float, dy: Float): Ruler = copy(centerX = centerX + dx, centerY = centerY + dy)

    fun turnedBy(radians: Float): Ruler = copy(angleRadians = angleRadians + radians)

    fun degrees(): Int {
        val raw = (-(angleRadians * 180.0 / PI)).toInt() % 360
        return if (raw < 0) raw + 360 else raw
    }

    fun turnedToNextEighth(): Ruler {
        val step = PI.toFloat() / 4f
        val next = kotlin.math.floor((angleRadians - step * EIGHTH_EPSILON) / step)
        return copy(angleRadians = next * step)
    }

    companion object {
        
        const val DIAL_RADIUS_DP = 18.5f

        const val DIAL_TOUCH_SLACK = 1.6f

        const val PROTRACTOR_DIAL_INSET = 0.42f

        private const val EIGHTH_EPSILON = 0.001f

        const val BAND_DP = 112f

        const val SNAP_TOLERANCE_DP = 28f
    }
}

data class RulerPlacement(val centerX: Float, val centerY: Float, val angleRadians: Float)

enum class RulerSide { Negative, Positive }

data class RulerPoint(val x: Float, val y: Float)
