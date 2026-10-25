package com.vivenotes.ui.canvas

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType

/** The CSS cursors the canvas shows over everything on it while the mouse holds something. */
internal enum class CanvasCursor(val keyword: String) {
    /** Middle-button auto-scroll. */
    AllScroll("all-scroll"),
    /** An object the mouse is moving. */
    Grabbing("grabbing"),
}

@Composable
internal expect fun canvasPointerIcon(cursor: CanvasCursor): PointerIcon

/** A held mouse button leaves hover mode, so desktop applies [cursor] at the host as well. */
@Composable
internal expect fun ApplyCanvasCursorWhilePressed(cursor: CanvasCursor?)

/**
 * Which drag, if any, has an object in the mouse's hold: the canvas shows [CanvasCursor.Grabbing]
 * meanwhile. Each drag holds and releases with its own token, so one handler stopping (its element
 * leaving the page, say) cannot let go of another's object.
 */
internal class ObjectGrab {
    private var owner: Any? by mutableStateOf(null)

    val held: Boolean get() = owner != null

    /** [change] moved an object for [by]; a finger has no cursor to change. */
    fun hold(by: Any, change: PointerInputChange) {
        if (change.type != PointerType.Touch) owner = by
    }

    fun release(by: Any) {
        if (owner === by) owner = null
    }

    /** Every button is up, so nothing can still be held. */
    fun releaseAll() {
        owner = null
    }
}

internal val LocalObjectGrab = staticCompositionLocalOf<ObjectGrab?> { null }

/**
 * [detectDragGestures] for a drag that moves an object. While [moves] says the drag is moving it,
 * the mouse holds it in [grab]; letting go, a cancelled drag and a cancelled handler all release it.
 */
internal suspend fun PointerInputScope.detectObjectDrag(
    grab: ObjectGrab?,
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    moves: () -> Boolean = { true },
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    val token = Any()
    try {
        detectDragGestures(
            onDragStart = onDragStart,
            onDragEnd = { grab?.release(token); onDragEnd() },
            onDragCancel = { grab?.release(token); onDragCancel() },
            onDrag = { change, drag ->
                if (moves()) grab?.hold(token, change)
                onDrag(change, drag)
            },
        )
    } finally {
        grab?.release(token)
    }
}
