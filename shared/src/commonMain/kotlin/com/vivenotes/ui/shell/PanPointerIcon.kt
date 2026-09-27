package com.vivenotes.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon

/** Direction shown by the native pointer as it moves away from the fixed auto-scroll anchor. */
internal enum class PanDirection { Center, North, NorthEast, East, SouthEast, South, SouthWest, West, NorthWest }

internal fun panDirection(dx: Float, dy: Float): PanDirection {
    val x = if (dx > 16f) 1 else if (dx < -16f) -1 else 0
    val y = if (dy > 16f) 1 else if (dy < -16f) -1 else 0
    return when (x to y) {
        0 to -1 -> PanDirection.North
        1 to -1 -> PanDirection.NorthEast
        1 to 0 -> PanDirection.East
        1 to 1 -> PanDirection.SouthEast
        0 to 1 -> PanDirection.South
        -1 to 1 -> PanDirection.SouthWest
        -1 to 0 -> PanDirection.West
        -1 to -1 -> PanDirection.NorthWest
        else -> PanDirection.Center
    }
}

internal expect fun panPointerIcon(direction: PanDirection): PointerIcon

/** A held mouse button leaves hover mode, so desktop applies its cursor at the host as well. */
@Composable
internal expect fun ApplyPanCursorWhilePressed(active: Boolean, direction: PanDirection)
